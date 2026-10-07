package tv.anstelle.feature.settings.playback

import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.SubtitleMode
import tv.anstelle.core.model.TrackPrefs

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
