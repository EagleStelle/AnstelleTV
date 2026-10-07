package tv.anstelle.feature.settings.servers

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import tv.anstelle.core.database.dao.TypeCount
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import tv.anstelle.core.theme.components.BackBar
import tv.anstelle.core.theme.components.PageHeader
import tv.anstelle.core.theme.components.pagePadding


/**
 * The page every settings screen uses: a header, then menu rows. On wide screens the column
 * stops at a readable width instead of stretching rows across the TV.
 */
@Composable
internal fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The header stays put while the rows scroll, so it is never left half clipped. Phones get
    // a back bar in its place; TV has the remote's Back.
    Column(modifier.fillMaxSize().background(theme.background)) {
        if (isTv) {
            PageHeader(title, subtitle = subtitle, modifier = Modifier.widthIn(max = 960.dp).padding(start = pagePadding, end = pagePadding, top = 32.dp, bottom = 10.dp))
        } else {
            BackBar(onBack = { back?.onBackPressed() }, title = title)
        }
        LazyColumn(
            modifier = Modifier.widthIn(max = 960.dp).weight(1f),
            contentPadding = PaddingValues(start = pagePadding, end = pagePadding, top = if (isTv) 4.dp else 0.dp, bottom = if (isTv) 32.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!isTv && subtitle != null) {
                item(key = "subtitle") {
                    androidx.compose.material3.Text(
                        subtitle,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = theme.textMuted,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            content()
        }
    }
}

/** On TV, focuses the requester's element once it is laid out. */
@Composable
internal fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}

@Composable
internal fun SectionLabel(text: String) = tv.anstelle.core.theme.components.SectionLabel(text)

/** A line of status or help. A problem carries a warning mark as well as the accent, so it reads without color. */
@Composable
internal fun MessageText(text: String, isError: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        if (isError) {
            androidx.compose.material3.Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Problem",
                tint = theme.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        androidx.compose.material3.Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.text else theme.textMuted,
        )
    }
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
