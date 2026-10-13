package tv.anstelle.core.jellyfin

import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.serializer.toUUID
import tv.anstelle.core.model.MediaSegment
import tv.anstelle.core.model.SegmentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the media segments Jellyfin keeps for an item (`/MediaSegments`, 10.10 and later). The
 * server's segment providers fill them in: the Intro Skipper plugin, once installed, is one,
 * so its intros and credits arrive here like any other provider's.
 */
@Singleton
class SegmentSource @Inject constructor(
    private val clients: JellyfinClients,
) {
    /**
     * [itemId]'s segments by start, read as the signed-in user ([device] as in
     * [JellyfinClients.api]). Unknown kinds and stretches under a second are left out.
     */
    suspend fun segments(baseUrl: String, accessToken: String, itemId: String, device: String? = null): List<MediaSegment> =
        jellyfinCall("Reading media segments") {
            clients.api(baseUrl, accessToken, device).mediaSegmentApi
                .getItemSegments(itemId.toUUID())
                .content.items
                .mapNotNull { it.toSegment() }
                .sortedBy { it.startMs }
        }

    private fun MediaSegmentDto.toSegment(): MediaSegment? {
        val kind = when (type) {
            MediaSegmentType.INTRO -> SegmentType.INTRO
            MediaSegmentType.OUTRO -> SegmentType.OUTRO
            MediaSegmentType.RECAP -> SegmentType.RECAP
            MediaSegmentType.PREVIEW -> SegmentType.PREVIEW
            MediaSegmentType.COMMERCIAL -> SegmentType.COMMERCIAL
            MediaSegmentType.UNKNOWN -> return null
        }
        val start = startTicks / TICKS_PER_MS
        val end = endTicks / TICKS_PER_MS
        return if (end - start >= MIN_MS) MediaSegment(kind, start, end) else null
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val MIN_MS = 1_000L
    }
}
