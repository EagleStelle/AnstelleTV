package tv.anstelle.feature.settings.servers

import android.text.format.DateUtils
import tv.anstelle.core.model.MediaServer
import tv.anstelle.core.model.MediaType

/** "Synced 5 minutes ago", the sync error, or the running sync's progress. */
internal fun MediaServer.statusLine(progress: String?, nowUtcMs: Long): String {
    if (progress != null) return progress
    if (!isSignedIn) return "Signed out"
    lastSyncError?.let { return it }
    val ago = syncedAgo(nowUtcMs) ?: return "Not synced yet"
    return "Synced $ago"
}

/** "just now", "5 minutes ago", "Yesterday": when the last sync finished, or null before the first. */
internal fun MediaServer.syncedAgo(nowUtcMs: Long): String? = lastSyncUtcMs?.let { ago(it, nowUtcMs) }

/** "just now", "5 minutes ago", "Yesterday": how long ago [atUtcMs] was, the way every settings status says it. */
internal fun ago(atUtcMs: Long, nowUtcMs: Long): String {
    if (nowUtcMs - atUtcMs < DateUtils.MINUTE_IN_MILLIS) return "just now"
    return DateUtils.getRelativeTimeSpanString(atUtcMs, nowUtcMs, DateUtils.MINUTE_IN_MILLIS).toString()
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
