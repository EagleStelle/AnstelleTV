package tv.anstelle.core.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.player.PooledPlayer
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
 * Audio and subtitle choices. The profile's defaults (Settings, Audio and subtitles) pick the
 * tracks of every program; a pick in the player only changes the program on now.
 */
@Singleton
@OptIn(UnstableApi::class)
class TrackChoices @Inject constructor(
    private val settings: SettingsRepository,
) {
    /** The profile's audio and subtitle defaults. */
    suspend fun prefs(profileId: Long): TrackPrefs = TrackPrefs(
        audioLanguages = Languages.decodeList(settings.get(Settings.audioLanguages(profileId))),
        subtitleMode = codeOf(settings.get(Settings.subtitleMode(profileId))),
        subtitleLanguages = Languages.decodeList(settings.get(Settings.subtitleLanguages(profileId))),
    )

    fun observePrefs(profileId: Long): Flow<TrackPrefs> = combine(
        settings.observe(Settings.audioLanguages(profileId)),
        settings.observe(Settings.subtitleMode(profileId)),
        settings.observe(Settings.subtitleLanguages(profileId)),
    ) { audio, mode, subtitles ->
        TrackPrefs(Languages.decodeList(audio), codeOf(mode), Languages.decodeList(subtitles))
    }

    /** Changes the profile's defaults; programs started from now on follow them. */
    suspend fun update(profileId: Long, change: (TrackPrefs) -> TrackPrefs) {
        val old = prefs(profileId)
        val new = change(old)
        if (new.audioLanguages != old.audioLanguages) settings.set(Settings.audioLanguages(profileId), Languages.encodeList(new.audioLanguages))
        if (new.subtitleMode != old.subtitleMode) settings.set(Settings.subtitleMode(profileId), new.subtitleMode.code)
        if (new.subtitleLanguages != old.subtitleLanguages) settings.set(Settings.subtitleLanguages(profileId), Languages.encodeList(new.subtitleLanguages))
    }

    /**
     * Sets the player up with [prefs] and every track type on, dropping any pick made in the
     * player. Called for each program, so a pick never carries over to the next one.
     */
    fun applyDefaults(pooled: PooledPlayer, prefs: TrackPrefs) {
        pooled.trackSelector.setPrefs(prefs)
        val player = pooled.player
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredAudioLanguages(*prefs.audioLanguages.toTypedArray())
            .build()
    }

    /**
     * The tracks of [type], each named by its language alone. Tracks that share a language, or
     * don't name one, show their details instead so they can be told apart.
     */
    fun options(player: Player, type: Int): List<TrackOption> {
        val groups = player.currentTracks.groups
        val tracks = groups.indices.filter { groups[it].type == type }
            .flatMap { g -> (0 until groups[g].length).filter { groups[g].isTrackSupported(it) }.map { g to it } }
        val formats = tracks.map { (g, t) -> groups[g].getTrackFormat(t) }
        val languages = formats.map(::languageName)
        val counts = languages.groupingBy { it }.eachCount()
        val options = tracks.mapIndexedTo(ArrayList()) { i, (g, t) ->
            val language = languages[i]
            val label = if (language != null && counts[language] == 1) language else details(formats[i], i + 1)
            TrackOption(g, t, label, formats[i].language, groups[g].isTrackSelected(t))
        }
        if (type == C.TRACK_TYPE_TEXT) options.add(0, TrackOption(-1, -1, "Off", null, options.none { it.selected }))
        return options
    }

    /** Plays [option] for the program on now; the next program goes back to the defaults. */
    fun select(player: Player, type: Int, option: TrackOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option.groupIndex < 0) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    /** "English", or null when the track doesn't say. */
    private fun languageName(format: Format): String? =
        format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        }

    /** "English · 5.1 · DD" or "Japanese · Signs". */
    private fun details(format: Format, number: Int): String {
        val parts = ArrayList<String>(5)
        languageName(format)?.let { parts += it }
        format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "forced"
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
