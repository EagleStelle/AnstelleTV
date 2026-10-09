package tv.anstelle.feature.settings.playback

import tv.anstelle.core.model.Languages
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
        WatchSync.STATUS -> "What you watch from start to end is marked watched. Joining late or skipping ahead does not count."
        WatchSync.PROGRESS -> "Where you stop is saved, replacing your spot from On Demand. Nothing is marked watched."
        WatchSync.BOTH -> "What you watch from start to end is marked watched. Where you stop replaces your On Demand spot."
        WatchSync.OFF -> "Live TV sends nothing to your server."
    }
    SyncPlace.ON_DEMAND -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched. Where you stop isn't saved."
        WatchSync.PROGRESS -> "Where you stop is saved, so you can resume. Nothing is marked watched."
        WatchSync.BOTH -> "Where you stop is saved and what you finish is marked watched."
        WatchSync.OFF -> "On Demand sends nothing to your server."
    }
}
