package tv.anstelle.core.player

import android.content.Context
import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import tv.anstelle.core.model.SubtitleCandidate
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.TrackRules

/**
 * Picks subtitles by the profile's [TrackPrefs] ([TrackRules.pickSubtitle]), which also needs
 * the audio language picked first. Audio follows the preferred audio languages set in the
 * player's parameters. A track the viewer picks (an override) still wins.
 */
@OptIn(UnstableApi::class)
class LanguageTrackSelector(context: Context) : DefaultTrackSelector(context) {
    @Volatile
    private var prefs = TrackPrefs()

    /** Sets the subtitle rules; tracks are picked again when they change. */
    fun setPrefs(prefs: TrackPrefs) {
        if (this.prefs == prefs) return
        this.prefs = prefs
        invalidate(null)
    }

    override fun selectTextTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        params: DefaultTrackSelector.Parameters,
        selectedAudioLanguage: String?,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val positions = ArrayList<IntArray>()
        val candidates = ArrayList<SubtitleCandidate>()
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_TEXT) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (t in 0 until group.length) {
                    if (!RendererCapabilities.isFormatSupported(rendererFormatSupports[renderer][g][t], false)) continue
                    val format = group.getFormat(t)
                    positions += intArrayOf(renderer, g, t)
                    candidates += SubtitleCandidate(
                        language = format.language,
                        title = format.label,
                        flaggedForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    )
                }
            }
        }
        val pick = TrackRules.pickSubtitle(candidates, prefs, selectedAudioLanguage) ?: return null
        val (renderer, g, t) = positions[pick]
        return Pair.create(ExoTrackSelection.Definition(mappedTrackInfo.getTrackGroups(renderer)[g], t), renderer)
    }
}
