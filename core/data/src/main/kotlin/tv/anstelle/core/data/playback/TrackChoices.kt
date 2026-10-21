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
import kotlinx.coroutines.flow.first
import tv.anstelle.core.data.SettingKey
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.PlaybackDefaults
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SubtitleLook
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.codeOf
import tv.anstelle.core.player.PooledPlayer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An audio or subtitle track the viewer can pick, or "off" for subtitles ([groupIndex] -1).
 * [label] tells it apart in the list; [short] is what the banner's key shows, the language alone.
 */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val short: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * Audio and subtitle choices. The profile's defaults (Settings, Playback) pick the tracks of
 * every program; a pick in the player only changes the program on now.
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

    fun observeLook(profileId: Long): Flow<SubtitleLook> = combine(
        settings.observe(Settings.subtitleSize(profileId)),
        settings.observe(Settings.subtitleFont(profileId)),
        settings.observe(Settings.subtitleColor(profileId)),
        settings.observe(Settings.subtitleEdge(profileId)),
        settings.observe(Settings.subtitleBackground(profileId)),
    ) { size, font, color, edge, background ->
        SubtitleLook(codeOf(size), codeOf(font), codeOf(color), codeOf(edge), codeOf(background))
    }

    /** What On demand does while each kind of segment plays. */
    suspend fun skipping(profileId: Long): Map<SegmentType, SegmentAction> =
        SegmentType.entries.associateWith { codeOf(settings.get(Settings.segmentAction(profileId, it))) }

    /** Everything the Playback page sets, as last saved. */
    suspend fun defaults(profileId: Long) = PlaybackDefaults(prefs(profileId), observeLook(profileId).first(), skipping(profileId))

    /** Keeps the profile's Playback settings; programs started from now on follow them, and every player shows the look at once. */
    suspend fun save(profileId: Long, defaults: PlaybackDefaults) {
        val (prefs, look, skipping) = defaults
        put(Settings.audioLanguages(profileId), Languages.encodeList(prefs.audioLanguages))
        put(Settings.subtitleMode(profileId), prefs.subtitleMode.code)
        put(Settings.subtitleLanguages(profileId), Languages.encodeList(prefs.subtitleLanguages))
        put(Settings.subtitleSize(profileId), look.size.code)
        put(Settings.subtitleFont(profileId), look.font.code)
        put(Settings.subtitleColor(profileId), look.color.code)
        put(Settings.subtitleEdge(profileId), look.edge.code)
        put(Settings.subtitleBackground(profileId), look.background.code)
        for ((type, action) in skipping) put(Settings.segmentAction(profileId, type), action.code)
    }

    /** Writes only what changed, so a save wakes no observer of a setting it left alone. */
    private suspend fun <T> put(key: SettingKey<T>, value: T) {
        if (settings.get(key) != value) settings.set(key, value)
    }

    /**
     * Sets the player up with [prefs] and every track type on, dropping any pick made in the
     * player. Called for each program, so a pick never carries over to the next one.
     */
    fun applyDefaults(pooled: PooledPlayer, prefs: TrackPrefs) {
        pooled.trackSelector.setPrefs(prefs)
        pooled.player.volume = 1f
        pooled.resetTrackParameters(prefs)
    }

    /**
     * The tracks of [type], each named by its language alone; tracks that share a language add
     * only what tells them apart ([TrackLabels]).
     */
    fun options(player: Player, type: Int): List<TrackOption> {
        val groups = player.currentTracks.groups
        val tracks = groups.indices.filter { groups[it].type == type }
            .flatMap { g -> (0 until groups[g].length).filter { groups[g].isTrackSupported(it) }.map { g to it } }
        val facts = tracks.map { (g, t) -> facts(groups[g].getTrackFormat(t)) }
        val labels = TrackLabels.of(facts)
        val options = tracks.mapIndexedTo(ArrayList()) { i, (g, t) ->
            val format = groups[g].getTrackFormat(t)
            TrackOption(g, t, labels[i], facts[i].language ?: labels[i], format.language, groups[g].isTrackSelected(t))
        }
        if (type == C.TRACK_TYPE_TEXT) {
            // Embedded subtitles arrive with the video and audio; side-loaded ones come after. While one is still
            // on its way, which one will show is unknown, so the banner key keeps its name.
            val coming = options.isEmpty() &&
                player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty().isNotEmpty()
            val off = if (groups.isEmpty() || coming) "Subtitles" else "Off"
            options.add(0, TrackOption(-1, -1, "Off", off, null, options.none { it.selected }))
        }
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

    private fun facts(format: Format) = TrackFacts(
        language = languageName(format),
        name = format.label?.takeIf { it.isNotBlank() },
        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
        channels = when (format.channelCount) {
            Format.NO_VALUE, 0 -> null
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${format.channelCount} ch"
        },
        codec = codecName(format.sampleMimeType),
    )

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "DD"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "DD+"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.APPLICATION_PGS -> "image"
        else -> null
    }
}
