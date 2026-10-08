package tv.anstelle.feature.settings.playback

import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.TrackPrefs
import tv.anstelle.core.model.WatchSync

internal fun languageName(code: String): String = Languages.displayName(code).replaceFirstChar { it.titlecase() }

internal fun modeTitle(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "Subtitles: off"
    SubtitleMode.ALWAYS -> "Subtitles: always on"
    SubtitleMode.WHEN_AUDIO_DIFFERS -> "Subtitles: when audio is in another language"
}

internal fun modeHint(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "Subtitles never show."
    SubtitleMode.ALWAYS -> "Subtitles show in every program that has one of your subtitle languages."
    SubtitleMode.WHEN_AUDIO_DIFFERS -> "Subtitles show when the audio isn't in one of your subtitle languages, like Japanese audio with English subtitles."
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
        WatchSync.PROGRESS -> "Where you stop is saved, replacing your spot from On demand. Nothing is marked watched."
        WatchSync.BOTH -> "What you watch from start to end is marked watched. Where you stop replaces your On demand spot."
        WatchSync.OFF -> "Live TV sends nothing to your server."
    }
    SyncPlace.ON_DEMAND -> when (sync) {
        WatchSync.STATUS -> "What you finish is marked watched. Where you stop isn't saved."
        WatchSync.PROGRESS -> "Where you stop is saved, so you can resume. Nothing is marked watched."
        WatchSync.BOTH -> "Where you stop is saved and what you finish is marked watched."
        WatchSync.OFF -> "On demand sends nothing to your server."
    }
}

/** "Live TV: Status · On demand: Both". */
internal fun watchHistorySummary(liveTv: WatchSync, onDemand: WatchSync): String =
    "Live TV: ${syncName(liveTv)} · On demand: ${syncName(onDemand)}"

/** "Japanese, Korean, English · subtitles in English when audio is in another language". */
internal fun summary(prefs: TrackPrefs): String {
    val audio = prefs.audioLanguages.joinToString(", ", transform = ::languageName).ifEmpty { "Default audio" }
    val subtitles = prefs.subtitleLanguages.joinToString(", ", transform = ::languageName)
    val subtitleText = when {
        prefs.subtitleMode == SubtitleMode.OFF -> "subtitles off"
        subtitles.isEmpty() -> "no subtitle language chosen"
        prefs.subtitleMode == SubtitleMode.ALWAYS -> "subtitles in $subtitles"
        else -> "subtitles in $subtitles when audio is in another language"
    }
    return "$audio · $subtitleText"
}
