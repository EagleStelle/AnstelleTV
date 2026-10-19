package tv.anstelle.feature.player

/** Both players return from a picker before Back can dismiss their controls. */
internal enum class PlayerPanel {
    CONTROLS, AUDIO, SUBTITLES, EPISODES;

    val isPicker: Boolean get() = this != CONTROLS
}

internal fun PlayerPanel?.afterBack(): PlayerPanel? =
    if (this?.isPicker == true) PlayerPanel.CONTROLS else null
