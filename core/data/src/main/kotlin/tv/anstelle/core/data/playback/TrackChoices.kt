package tv.anstelle.core.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** An audio or subtitle track the viewer can pick, or "off" for subtitles ([groupIndex] -1). */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * Audio and subtitle choices. A pick applies to the program on now and becomes the profile's
 * default language, so the next programs follow it.
 */
@Singleton
@OptIn(UnstableApi::class)
class TrackChoices @Inject constructor(
    private val settings: SettingsRepository,
) {
    /** Sets the player up with the profile's languages and every track type on. */
    suspend fun applyDefaults(player: Player, profileId: Long) {
        val audio = settings.get(Settings.audioLanguage(profileId)).ifBlank { null }
        val subtitles = settings.get(Settings.subtitleLanguage(profileId)).ifBlank { null }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setPreferredAudioLanguage(audio)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitles == null)
            .setPreferredTextLanguage(subtitles)
            .build()
    }

    fun options(player: Player, type: Int): List<TrackOption> {
        val options = ArrayList<TrackOption>()
        val groups = player.currentTracks.groups
        var anySelected = false
        groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val format = group.getTrackFormat(t)
                val selected = group.isTrackSelected(t)
                if (selected) anySelected = true
                options += TrackOption(g, t, label(format, options.size + 1), format.language, selected)
            }
        }
        if (type == C.TRACK_TYPE_TEXT) options.add(0, TrackOption(-1, -1, "Off", null, !anySelected))
        return options
    }

    suspend fun select(player: Player, profileId: Long, type: Int, option: TrackOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option.groupIndex < 0) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        val language = option.language.orEmpty()
        when (type) {
            C.TRACK_TYPE_AUDIO -> {
                builder.setPreferredAudioLanguage(language.ifBlank { null })
                settings.set(Settings.audioLanguage(profileId), language)
            }
            C.TRACK_TYPE_TEXT -> {
                builder.setPreferredTextLanguage(language.ifBlank { null })
                settings.set(Settings.subtitleLanguage(profileId), if (option.groupIndex < 0) "" else language)
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    /** "English · 5.1 · DD" or "Japanese · Signs". */
    private fun label(format: Format, number: Int): String {
        val parts = ArrayList<String>(4)
        format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let {
            parts += Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        }
        format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        when (format.channelCount) {
            Format.NO_VALUE, 0 -> Unit
            1 -> parts += "mono"
            2 -> parts += "stereo"
            6 -> parts += "5.1"
            8 -> parts += "7.1"
            else -> parts += "${format.channelCount} ch"
        }
        codecName(format.sampleMimeType)?.let { parts += it }
        return parts.distinctBy { it.lowercase(Locale.ROOT) }.joinToString(" · ").ifEmpty { "Track $number" }
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "DD"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "DD+"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.APPLICATION_PGS -> "image"
        else -> null
    }
}
