package tv.anstelle.core.theme.components

import tv.anstelle.core.model.WatchChoice
import tv.anstelle.core.model.WatchKind

/** The same watch choice carries the same icon on a page, in the guide, and in a dialog. */
fun WatchChoice.icon() = when {
    startsOver -> WatchIcons.StartOver
    else -> when (kind) {
        WatchKind.LIVE -> WatchIcons.Live
        WatchKind.RESTART -> WatchIcons.StartOver
        WatchKind.ADD_TO_CHANNEL -> WatchIcons.AddToChannel
        WatchKind.PLAY -> WatchIcons.play(positionMs, fromAiring)
    }
}

/** Details opens a page; it never starts playback. */
private fun openOnDemandAction(isEpisode: Boolean, onClick: () -> Unit) = DialogAction(
    title = "Open on Demand",
    detail = "The ${if (isEpisode) "episode" else "movie"}'s page, with its synopsis.",
    icon = WatchIcons.Details,
    onClick = onClick,
)

/** All airing dialogs use the same availability rule and navigation copy. */
fun List<WatchChoice>.onDemandNavigation(isEpisode: Boolean, onClick: () -> Unit): DialogAction? =
    if (any { it.kind == WatchKind.PLAY && it.fromAiring }) openOnDemandAction(isEpisode, onClick) else null

/** The parent series page, offered from an episode's own page. */
fun goToSeriesAction(onClick: () -> Unit) = DialogAction(
    title = "Go to Series",
    detail = "Every season and episode, in one place.",
    icon = WatchIcons.Details,
    onClick = onClick,
)

/**
 * Converts watch choices once, offering the program's page for any scheduled airing.
 * Navigation follows playback and precedes channel management; callers can append cancellation.
 */
fun List<WatchChoice>.dialogActions(
    isEpisode: Boolean = false,
    onOpenDetails: (() -> Unit)? = null,
    onAction: (WatchChoice) -> Unit,
): List<DialogAction> {
    val actions = map { choice ->
        DialogAction(choice.title, choice.detail, choice.icon()) { onAction(choice) }
    }.toMutableList()
    onOpenDetails?.let { onDemandNavigation(isEpisode, it) }?.let { navigation ->
        val index = indexOfFirst { it.kind == WatchKind.ADD_TO_CHANNEL }.takeIf { it >= 0 } ?: size
        actions.add(index, navigation)
    }
    return actions
}
