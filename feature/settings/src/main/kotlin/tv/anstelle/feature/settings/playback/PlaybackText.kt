package tv.anstelle.feature.settings.playback

import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.SegmentAction
import tv.anstelle.core.model.SegmentType
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.WatchSync

internal fun languageName(code: String): String = Languages.displayName(code).replaceFirstChar { it.titlecase() }

/** The subtitle modes in the order their choices list them: Jellyfin's own names, in its order. */
internal val ModeOrder = listOf(SubtitleMode.WHEN_AUDIO_DIFFERS, SubtitleMode.ALWAYS, SubtitleMode.OFF)

internal fun modeName(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "None"
    SubtitleMode.ALWAYS -> "Always"
    SubtitleMode.WHEN_AUDIO_DIFFERS -> "Smart"
}

/** What each mode does, as Jellyfin's apps put it. */
internal fun modeHint(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "Subtitles don't load on their own. You can still turn them on while watching."
    SubtitleMode.ALWAYS -> "Subtitles in your languages load whatever the audio language."
    SubtitleMode.WHEN_AUDIO_DIFFERS -> "Subtitles in your languages load when the audio is in a foreign language."
}

/** A kind of segment as its row names it: Intros, Outros… */
internal fun segmentName(type: SegmentType): String = when (type) {
    SegmentType.INTRO -> "Intros"
    SegmentType.OUTRO -> "Outros"
    SegmentType.RECAP -> "Recaps"
    SegmentType.PREVIEW -> "Previews"
    SegmentType.COMMERCIAL -> "Commercials"
}

/** The [SegmentAction] choices in the order the Playback page lists them: Jellyfin's own, in its order. */
internal val SkipOrder = listOf(SegmentAction.ASK_TO_SKIP, SegmentAction.SKIP, SegmentAction.PLAY)

internal fun skipName(action: SegmentAction): String = when (action) {
    SegmentAction.ASK_TO_SKIP -> "Ask to skip"
    SegmentAction.SKIP -> "Skip"
    SegmentAction.PLAY -> "Play"
}

internal fun skipHint(type: SegmentType, action: SegmentAction): String = when (action) {
    SegmentAction.ASK_TO_SKIP -> "A Skip ${type.key.replaceFirstChar { it.titlecase() }} key shows while one plays."
    SegmentAction.SKIP -> "Skipped as soon as one starts. Going back into it plays it."
    SegmentAction.PLAY -> "Plays through, with nothing to press."
}

/** The [WatchSync] choices in the order the Watch history page lists them. */
internal val SyncOrder = listOf(WatchSync.STATUS, WatchSync.PROGRESS, WatchSync.BOTH, WatchSync.OFF)

internal fun syncName(sync: WatchSync): String = when (sync) {
    WatchSync.STATUS -> "Status"
    WatchSync.PROGRESS -> "Progress"
    WatchSync.BOTH -> "Both"
    WatchSync.OFF -> "Off"
}

internal fun syncDescription(place: SyncPlace, sync: WatchSync): String = when (place) {
    SyncPlace.LIVE_TV -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched after at least 30 seconds of playback, even if you joined late."
        WatchSync.PROGRESS -> "After 30 seconds of playback, where you stop replaces your On Demand spot. Watched status stays unchanged."
        WatchSync.BOTH -> "After 30 seconds of playback, what you finish is marked watched and where you stop replaces your On Demand spot."
        WatchSync.OFF -> "Live TV sends nothing to your server."
    }
    SyncPlace.ON_DEMAND -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched. Where you stop isn't saved."
        WatchSync.PROGRESS -> "Where you stop is saved, so you can resume. Nothing is marked watched."
        WatchSync.BOTH -> "Where you stop is saved and what you finish is marked watched."
        WatchSync.OFF -> "On Demand sends nothing to your server."
    }
}
