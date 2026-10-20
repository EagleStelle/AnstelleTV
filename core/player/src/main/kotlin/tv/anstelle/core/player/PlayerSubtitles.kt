package tv.anstelle.core.player

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import tv.anstelle.core.model.FontAttachment
import java.util.WeakHashMap
import java.util.zip.CRC32

/** A font file for libass. */
class FontFile(val name: String, val data: ByteArray)

/**
 * libass for one player: ASS and SSA subtitles are drawn over the video exactly as authored,
 * with the fonts a Matroska file carries or the server sends.
 *
 * libass holds one item at a time: its tracks are dropped whenever the player moves to another
 * item, so an item with ASS subtitles is never queued behind another one (see
 * [ProgramStream.chainsWith]). libass also keeps every font it is given until the player is
 * released, so each font goes in only once.
 */
@OptIn(UnstableApi::class)
class PlayerSubtitles internal constructor(private val cache: FontCache) {
    internal val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig(maxRenderPixels = MAX_RENDER_PIXELS))
    internal val parserFactory = AssSubtitleParserFactory(handler)

    /** Fonts libass has. */
    private val added = HashSet<String>()

    /** Fonts waiting for the item's first ASS track, with their sizes; dropped if the item changes before that. */
    private val pending = HashMap<String, Int>()

    /**
     * Bytes of font data libass holds. It keeps a copy of every font until the player is
     * released, so a channel airing show after show gathers them ([PlayerPool] renews the player).
     */
    @Volatile
    var fontBytes = 0L
        private set

    /** The fonts the item being loaded attaches in its own file; null for a transcoded stream. */
    @Volatile
    private var carried: Carried? = null

    /** Matroska extraction that hands the file's font attachments to libass, or skips them when it has them all. */
    internal val extractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            DefaultExtractorsFactory().createExtractors(uri, responseHeaders).also { extractors ->
                for (i in extractors.indices) {
                    if (extractors[i] is MatroskaExtractor) extractors[i] = FontMatroskaExtractor(parserFactory, this@PlayerSubtitles, uri.toString())
                }
            }
    }

    internal fun attach(player: ExoPlayer) {
        // Registered before libass' own listener, so it sees whether the pending fonts went in.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = settlePending()
        })
        handler.init(player)
    }

    /**
     * Adds [fonts] for [stream], just set on the player (after `setMediaItems`, which drops pending
     * fonts). A Matroska file played directly carries its fonts ahead of its first frames: given
     * all of them here, the player skips those megabytes unread; otherwise it reads them and keeps
     * them in the [FontCache] for the next time.
     */
    fun addFonts(fonts: List<FontFile>, stream: ProgramStream) {
        for (font in fonts) add(font.name, font.data)
        carried = if (stream.transcoded) null else Carried(stream.url, stream.fonts, given = stream.fonts.isNotEmpty() && fonts.size == stream.fonts.size)
    }

    /** Whether libass was given every font the file at [uri] attaches. */
    internal fun given(uri: String): Boolean = carried?.let { it.given && it.url == uri } == true

    /** Keeps [data], the font [name] the file at [uri] attaches, for the next time it plays. */
    internal fun keep(uri: String, name: String, data: ByteArray) {
        val carried = carried?.takeIf { !it.given && it.url == uri } ?: return
        carried.fonts.firstOrNull { it.fileName == name }?.let { cache.keep(it, data) }
    }

    @Synchronized
    internal fun add(name: String, data: ByteArray) {
        val key = "$name:${data.size}:${CRC32().apply { update(data) }.value}"
        if (key in added || key in pending) return
        val direct = handler.hasTracks()
        handler.addFont(name, data)
        if (direct) {
            added += key
            fontBytes += data.size
        } else {
            pending[key] = data.size
        }
    }

    @Synchronized
    private fun settlePending() {
        // With a track, libass took the pending fonts; without one, they are dropped.
        if (handler.hasTracks()) {
            added += pending.keys
            fontBytes += pending.values.sum()
        }
        pending.clear()
    }

    internal fun release() = handler.release()

    private class Carried(val url: String, val fonts: List<FontRequest>, val given: Boolean)

    private companion object {
        /** libass draws at no more than 1080p; a 4K screen scales it up. */
        const val MAX_RENDER_PIXELS = 1920 * 1080
    }
}

/**
 * Matroska extraction through [AssMatroskaExtractor] that adds each font once, also takes fonts
 * with a generic MIME type, and skips the file's attachments when libass already has them all
 * ([PlayerSubtitles.given]). They come before the first frames, so the file is requested again
 * from their end instead. `MatroskaExtractor.read` is final, so the skip is a seek returned from
 * here once its reader, between two elements, reads again.
 */
@OptIn(UnstableApi::class)
private class FontMatroskaExtractor(
    parserFactory: SubtitleParser.Factory,
    private val subtitles: PlayerSubtitles,
    private val uri: String,
) : Extractor {
    /** Where the attachments being skipped end. */
    private var skipTo = C.INDEX_UNSET.toLong()
    private var input: Skippable? = null

    private val matroska = object : AssMatroskaExtractor(parserFactory, subtitles.handler) {
        private var name: String? = null
        private var mimeType: String? = null

        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            when (id) {
                ID_ATTACHED_FILE -> {
                    name = null
                    mimeType = null
                }
                ID_ATTACHMENTS -> if (subtitles.given(uri)) skipTo = contentPosition + contentSize
            }
            super.startMasterElement(id, contentPosition, contentSize)
        }

        override fun stringElement(id: Int, value: String) {
            when (id) {
                ID_FILE_NAME -> name = value
                ID_FILE_MIME_TYPE -> mimeType = value
            }
            super.stringElement(id, value)
        }

        override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
            if (id != ID_FILE_DATA) return super.binaryElement(id, contentSize, input)
            val fontName = name
            if (fontName != null && (mimeType in fontMimeTypes || FontAttachment.isFont(fontName, mimeType))) {
                val data = ByteArray(contentSize)
                input.readFully(data, 0, contentSize)
                subtitles.add(fontName, data)
                subtitles.keep(uri, fontName, data)
            } else {
                input.skipFully(contentSize)
            }
        }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        val skippable = this.input?.takeIf { it.input === input } ?: Skippable(input).also { this.input = it }
        matroska.read(skippable, seekPosition)
    } catch (_: SkipAttachments) {
        seekPosition.position = skipTo
        skipTo = C.INDEX_UNSET.toLong()
        Extractor.RESULT_SEEK
    }

    override fun sniff(input: ExtractorInput): Boolean = matroska.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = matroska.sniffFailureDetails

    override fun init(output: ExtractorOutput) = matroska.init(output)

    override fun seek(position: Long, timeUs: Long) {
        skipTo = C.INDEX_UNSET.toLong()
        matroska.seek(position, timeUs)
    }

    override fun release() = matroska.release()

    override fun getUnderlyingImplementation(): Extractor = matroska

    /** The input, which stops the reader before its next element while the attachments are to be skipped. */
    private inner class Skippable(val input: ExtractorInput) : ExtractorInput by input {
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (skipTo != C.INDEX_UNSET.toLong()) throw SkipAttachments
            return input.readFully(target, offset, length, allowEndOfInput)
        }
    }

    private object SkipAttachments : RuntimeException()
}

/**
 * Puts each player's libass layer into the video views showing it. The full-screen player
 * and the guide's preview can show the same player for a moment; the view attached last
 * draws, and gets the frames back when the other one goes. Main thread only.
 */
@OptIn(UnstableApi::class)
object SubtitleOverlays {
    private val players = WeakHashMap<Player, PlayerSubtitles>()
    private val views = WeakHashMap<ViewGroup, AssSubtitleView>()
    private val owners = WeakHashMap<AssSubtitleView, PlayerSubtitles>()

    internal fun register(player: Player, subtitles: PlayerSubtitles) {
        players[player] = subtitles
    }

    internal fun unregister(player: Player) {
        players.remove(player)
    }

    /** Shows [player]'s ASS subtitles in [container], a `PlayerView`'s subtitle view. */
    @MainThread
    fun attach(container: ViewGroup, player: Player?) {
        val subtitles = player?.let(players::get)
        val current = views[container]
        if (current != null && owners[current] === subtitles) return
        detach(container)
        if (subtitles == null) return
        val view = AssSubtitleView(container.context, subtitles.handler)
        view.addOnAttachStateChangeListener(FramesBeforeRenderThread(subtitles.handler))
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        views[container] = view
        owners[view] = subtitles
    }

    @MainThread
    fun detach(container: ViewGroup) {
        val view = views.remove(container) ?: return
        val subtitles = owners.remove(view)
        // Leaving the window stops libass' frames to this view…
        container.removeView(view)
        // …so another view of the same player takes them back by attaching again.
        val other = views.values.firstOrNull { owners[it] === subtitles && it.isAttachedToWindow } ?: return
        val parent = other.parent as? ViewGroup ?: return
        val params = other.layoutParams
        parent.removeView(other)
        parent.addView(other, params)
    }

    /**
     * libass' GL view publishes its render thread before starting it, so a video frame arriving
     * in between asks a thread without a handler to draw, and playback fails. Each attach sets
     * libass' frame callback; this wraps it so such a frame only skips its subtitles.
     * shortcut: works around a race inside ass-media 0.5.1; drop once libass starts the thread before publishing it.
     */
    private class FramesBeforeRenderThread(private val handler: AssHandler) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val draw = handler.videoTimeCallback ?: return
            handler.videoTimeCallback = { time ->
                try { draw(time) } catch (_: UninitializedPropertyAccessException) {}
            }
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }
}
