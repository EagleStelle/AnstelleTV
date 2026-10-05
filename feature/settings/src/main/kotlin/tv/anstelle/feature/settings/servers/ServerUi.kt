package tv.anstelle.feature.settings.servers

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tv.anstelle.core.database.dao.TypeCount
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/** The scrolling page layout shared by the server screens. */
@Composable
internal fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(theme.background),
        contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
            )
            if (subtitle != null) {
                androidx.compose.material3.Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = theme.textMuted,
                )
            }
        }
        content()
    }
}

@Composable
internal fun SectionLabel(text: String) {
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        color = LocalAnstelleTheme.current.text,
    )
}

@Composable
internal fun MessageText(text: String, isError: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = if (isError) theme.secondary else theme.textMuted,
    )
}

/** "Synced 5 minutes ago", the sync error, or the running sync's progress. */
internal fun MediaServer.statusLine(progress: String?, nowUtcMs: Long): String {
    if (progress != null) return progress
    if (!isSignedIn) return "Signed out. Select to sign in again."
    lastSyncError?.let { return it }
    val synced = lastSyncUtcMs ?: return "Not synced yet"
    if (nowUtcMs - synced < DateUtils.MINUTE_IN_MILLIS) return "Synced just now"
    return "Synced " + DateUtils.getRelativeTimeSpanString(synced, nowUtcMs, DateUtils.MINUTE_IN_MILLIS)
}

internal fun countsLine(counts: List<TypeCount>): String =
    counts.filter { it.count > 0 }.joinToString(" · ") { "%,d %s".format(it.count, it.type.label(it.count)) }
        .ifEmpty { "No items yet" }

private fun MediaType.label(count: Int): String {
    val one = count == 1
    return when (this) {
        MediaType.SERIES -> if (one) "show" else "shows"
        MediaType.EPISODE -> if (one) "episode" else "episodes"
        MediaType.MOVIE -> if (one) "movie" else "movies"
        MediaType.MUSIC -> if (one) "song" else "songs"
        MediaType.MUSIC_VIDEO -> if (one) "music video" else "music videos"
    }
}

internal fun collectionLabel(collectionType: String?): String = when (collectionType) {
    "movies" -> "Movies"
    "tvshows" -> "TV shows"
    "musicvideos" -> "Music videos"
    "homevideos" -> "Home videos"
    "music" -> "Music"
    "boxsets" -> "Collections"
    "books" -> "Books"
    "photos" -> "Photos"
    "playlists" -> "Playlists"
    "livetv" -> "Live TV"
    null, "mixed", "folders", "unknown" -> "Mixed content"
    else -> collectionType.replaceFirstChar { it.uppercase() }
}
