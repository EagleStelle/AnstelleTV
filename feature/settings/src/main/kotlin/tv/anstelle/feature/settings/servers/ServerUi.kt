package tv.anstelle.feature.settings.servers

import android.text.format.DateUtils
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.theme.components.MenuPage
import tv.anstelle.core.theme.components.Notice

/** The page every settings screen uses: see [MenuPage]. */
@Composable
internal fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) = MenuPage(title, modifier, subtitle, content = content)

/** On TV, focuses the requester's element once it is laid out. */
@Composable
internal fun rememberInitialFocus(): FocusRequester = tv.anstelle.core.theme.components.rememberInitialFocus()

@Composable
internal fun SectionLabel(text: String) = tv.anstelle.core.theme.components.SectionLabel(text)

/** A line of status or help: see [Notice]. */
@Composable
internal fun MessageText(text: String, isError: Boolean = false) = Notice(text, isError = isError)

/** "Synced 5 minutes ago", the sync error, or the running sync's progress. */
internal fun MediaServer.statusLine(progress: String?, nowUtcMs: Long): String {
    if (progress != null) return progress
    if (!isSignedIn) return "Signed out. Select to sign in again."
    lastSyncError?.let { return it }
    val ago = syncedAgo(nowUtcMs) ?: return "Not synced yet"
    return "Synced $ago"
}

/** "just now", "5 minutes ago", "Yesterday": when the last sync finished, or null before the first. */
internal fun MediaServer.syncedAgo(nowUtcMs: Long): String? {
    val synced = lastSyncUtcMs ?: return null
    if (nowUtcMs - synced < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(synced, nowUtcMs, DateUtils.MINUTE_IN_MILLIS).toString()
}

internal fun MediaType.label(count: Int): String {
    val one = count == 1
    return when (this) {
        MediaType.SERIES -> "series"
        MediaType.EPISODE -> if (one) "episode" else "episodes"
        MediaType.MOVIE -> if (one) "movie" else "movies"
        MediaType.MUSIC -> if (one) "song" else "songs"
        MediaType.MUSIC_VIDEO -> if (one) "music video" else "music videos"
    }
}

internal fun collectionLabel(collectionType: String?): String = when (collectionType) {
    "movies" -> "Movies"
    "tvshows" -> "Series"
    "musicvideos" -> "Music videos"
    "homevideos" -> "Home videos"
    "music" -> "Music"
    "books" -> "Books"
    "photos" -> "Photos"
    "playlists" -> "Playlists"
    "livetv" -> "Live TV"
    null, "mixed", "folders", "unknown" -> "Mixed content"
    else -> collectionType.replaceFirstChar { it.uppercase() }
}
