package tv.anstelle.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An item's audio and subtitle streams and its font attachments, as synced from Jellyfin.
 * Indexes are Jellyfin's stream and attachment indexes.
 */
@Serializable
data class MediaTracks(
    val audio: List<AudioStream> = emptyList(),
    val subtitles: List<SubtitleStream> = emptyList(),
    val fonts: List<FontAttachment> = emptyList(),
) {
    /** Whether any subtitle stream is ASS or SSA. */
    val hasAss: Boolean get() = subtitles.any { it.isAss }

    fun encode(): String = if (this == EMPTY) "" else json.encodeToString(serializer(), this)

    companion object {
        val EMPTY = MediaTracks()

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): MediaTracks =
            if (raw.isEmpty()) EMPTY else runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(EMPTY)
    }
}

@Serializable
data class AudioStream(
    val index: Int,
    val language: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class SubtitleStream(
    val index: Int,
    val language: String? = null,
    val title: String? = null,
    /** Jellyfin's codec name: `ass`, `subrip`, `pgssub`… */
    val codec: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** A file next to the video rather than a stream inside it. */
    val isExternal: Boolean = false,
) {
    val isAss: Boolean get() = codec.equals("ass", ignoreCase = true) || codec.equals("ssa", ignoreCase = true)

    /** Text subtitles can be delivered as a file; image ones (PGS, VobSub, DVB) can't. */
    val isText: Boolean get() = codec?.lowercase() !in IMAGE_CODECS

    private companion object {
        val IMAGE_CODECS = setOf("pgssub", "pgs", "dvdsub", "dvd_subtitle", "dvbsub", "dvb_subtitle", "vobsub", "xsub")
    }
}

@Serializable
data class FontAttachment(
    val index: Int,
    val fileName: String,
) {
    companion object {
        /** Whether an attachment is a font, by its MIME type or, when that is generic, its file name. */
        fun isFont(fileName: String?, mimeType: String?): Boolean {
            val mime = mimeType?.lowercase().orEmpty()
            val name = fileName?.lowercase().orEmpty()
            return mime.startsWith("font/") || "truetype" in mime || "opentype" in mime || "font-" in mime ||
                FONT_EXTENSIONS.any { name.endsWith(it) }
        }

        private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".otc")
    }
}

/** A profile's audio and subtitle defaults. Languages are ISO 639-2/T codes in order of preference. */
data class TrackPrefs(
    val audioLanguages: List<String> = emptyList(),
    val subtitleMode: SubtitleMode = SubtitleMode.OFF,
    val subtitleLanguages: List<String> = emptyList(),
)

/**
 * How a profile's plain subtitles look (SRT, plain WebVTT): white letters in a black outline
 * with no box by default. ASS keeps its authored look and image subtitles their pictures.
 */
data class SubtitleLook(
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val font: SubtitleFont = SubtitleFont.SANS,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val background: SubtitleBackground = SubtitleBackground.NONE,
)

/** Everything a profile's Playback settings set, loaded and saved together. */
data class PlaybackDefaults(
    val prefs: TrackPrefs,
    val look: SubtitleLook,
    /** What On demand does while each kind of segment plays. */
    val skipping: Map<SegmentType, SegmentAction>,
)

/** A subtitle track as the rules see it: its language, its own [title] and its flags. */
data class SubtitleCandidate(
    val language: String?,
    val title: String?,
    private val flaggedForced: Boolean,
    val isDefault: Boolean,
) {
    /**
     * Flagged forced, or titled so with the words Jellyfin reads in forced files' names: many
     * files name a forced track "Forced" without setting the flag, which Jellyfin then misses too.
     */
    val isForced: Boolean get() = flaggedForced || title?.let(FORCED_TITLE::containsMatchIn) == true

    private companion object {
        val FORCED_TITLE = Regex("""\b(forced|foreign)\b""", RegexOption.IGNORE_CASE)
    }
}

/** Which audio and subtitle tracks play by default. Pure, so the same rules serve every player. */
object TrackRules {
    /**
     * The subtitle track to show, as an index into [candidates], or null for none.
     *
     * - *Off* shows none.
     * - *Always* shows a full track, never a forced one.
     * - *Forced* shows a forced track, never a full one.
     * - *Smart* shows a forced track in the audio's language when [audioLanguage] is one of the
     *   subtitle languages, otherwise a full track; unknown audio counts as foreign.
     *
     * The first subtitle language with a track of that kind wins, and within it the
     * default-flagged one. No listed language, none.
     */
    fun pickSubtitle(candidates: List<SubtitleCandidate>, prefs: TrackPrefs, audioLanguage: String?): Int? {
        val languages = prefs.subtitleLanguages.mapNotNull(Languages::normalize)
        return when (prefs.subtitleMode) {
            SubtitleMode.OFF -> null
            SubtitleMode.ALWAYS -> pick(candidates, languages, forced = false)
            SubtitleMode.FORCED -> pick(candidates, languages, forced = true)
            SubtitleMode.SMART -> {
                val audio = Languages.normalize(audioLanguage)
                if (audio != null && audio in languages) pick(candidates, listOf(audio), forced = true) else pick(candidates, languages, forced = false)
            }
        }
    }

    private fun pick(candidates: List<SubtitleCandidate>, languages: List<String>, forced: Boolean): Int? {
        for (language in languages) {
            var best = -1
            candidates.forEachIndexed { i, candidate ->
                if (candidate.isForced != forced || Languages.normalize(candidate.language) != language) return@forEachIndexed
                if (best < 0 || (candidate.isDefault && !candidates[best].isDefault)) best = i
            }
            if (best >= 0) return best
        }
        return null
    }

    /**
     * The audio stream for a transcoded stream, where the server sends only one: the first
     * listed language the item has (its default-flagged stream first), or null to leave the
     * choice to the server.
     */
    fun pickAudio(streams: List<AudioStream>, languages: List<String>): AudioStream? {
        for (language in languages.mapNotNull(Languages::normalize)) {
            val matching = streams.filter { Languages.normalize(it.language) == language }
            matching.firstOrNull { it.isDefault }?.let { return it }
            matching.firstOrNull()?.let { return it }
        }
        return null
    }
}
