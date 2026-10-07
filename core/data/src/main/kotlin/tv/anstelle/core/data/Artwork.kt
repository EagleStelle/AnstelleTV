package tv.anstelle.core.data

import tv.anstelle.core.database.entity.MediaItemEntity

/**
 * A program's backdrop and title logo from Jellyfin, as its metadata providers filled them in.
 * Either may be missing. An episode uses its series' artwork; a movie or a series its own.
 */
data class Artwork(val backdropUrl: String?, val logoUrl: String?) {
    companion object {
        val None = Artwork(null, null)

        /** Backdrop width asked of the server: sharp under the wash on a 1080p TV, small on a phone. */
        private const val BACKDROP_WIDTH = 1280

        /** Logo width asked of the server; logos never show wider than about 400 dp. */
        private const val LOGO_WIDTH = 640

        /** [item]'s backdrop and logo, falling back to [series]' for an episode. */
        fun of(baseUrl: String?, item: MediaItemEntity, series: MediaItemEntity?): Artwork {
            if (baseUrl == null) return None
            val backdrop = when {
                item.backdropImageTag != null -> item
                series?.backdropImageTag != null -> series
                else -> null
            }
            val logo = when {
                item.logoImageTag != null -> item
                series?.logoImageTag != null -> series
                else -> null
            }
            return Artwork(
                backdropUrl = backdrop?.let { jellyfinImageUrl(baseUrl, it.remoteId, "Backdrop/0", it.backdropImageTag, maxWidth = BACKDROP_WIDTH) },
                logoUrl = logo?.let { jellyfinImageUrl(baseUrl, it.remoteId, "Logo", it.logoImageTag, maxWidth = LOGO_WIDTH) },
            )
        }
    }
}

/**
 * An image of [remoteId] from Jellyfin ([type] is "Primary", "Backdrop/0", "Logo"…), or null
 * when [tag] is. The tag makes the URL change with the image, so caches never go stale.
 */
fun jellyfinImageUrl(baseUrl: String, remoteId: String, type: String, tag: String?, maxWidth: Int? = null, maxHeight: Int? = null): String? {
    if (tag == null) return null
    val size = buildString {
        maxWidth?.let { append("&maxWidth=").append(it) }
        maxHeight?.let { append("&maxHeight=").append(it) }
    }
    return "${baseUrl.trimEnd('/')}/Items/$remoteId/Images/$type?tag=$tag$size&quality=90"
}
