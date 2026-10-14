package tv.anstelle.core.player

import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
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
class PlayerSubtitles internal constructor() {
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

    /** Matroska extraction that hands the file's font attachments to libass. */
    internal val extractorsFactory = ExtractorsFactory {
        DefaultExtractorsFactory().createExtractors().also { extractors ->
            for (i in extractors.indices) {
                if (extractors[i] is MatroskaExtractor) extractors[i] = FontMatroskaExtractor(parserFactory, this)
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

    /** Adds fonts for the item just set on the player (after `setMediaItems`, which drops pending fonts). */
    fun addFonts(fonts: List<FontFile>) {
        for (font in fonts) add(font.name, font.data)
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

    private companion object {
        /** libass draws at no more than 1080p; a 4K screen scales it up. */
        const val MAX_RENDER_PIXELS = 1920 * 1080
    }
}

/** [AssMatroskaExtractor] that adds each font once and also takes fonts with a generic MIME type. */
@OptIn(UnstableApi::class)
private class FontMatroskaExtractor(
    parserFactory: SubtitleParser.Factory,
    private val subtitles: PlayerSubtitles,
) : AssMatroskaExtractor(parserFactory, subtitles.handler) {
    private var name: String? = null
    private var mimeType: String? = null

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == ID_ATTACHED_FILE) {
            name = null
            mimeType = null
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
        } else {
            input.skipFully(contentSize)
        }
    }
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
}
