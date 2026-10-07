package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaAttachment
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import tv.anstelle.core.database.dao.SeriesMetadataRow
import tv.anstelle.core.database.entity.MediaItemEntity
import tv.anstelle.core.jellyfin.toEpochMs
import tv.anstelle.core.jellyfin.toJellyfinId
import tv.anstelle.core.model.AudioStream
import tv.anstelle.core.model.FontAttachment
import tv.anstelle.core.model.HdrType
import tv.anstelle.core.model.Languages
import tv.anstelle.core.model.MediaTracks
import tv.anstelle.core.model.MediaType
import tv.anstelle.core.model.SubtitleStream

/** Everything the mapper needs besides the item itself. */
class SyncContext(
    val serverId: Long,
    val libraryId: Long,
    val ratings: RatingScores,
    /** Series rows by Jellyfin id, for episodes that inherit metadata. */
    val series: Map<String, SeriesMetadataRow>,
    val nowUtcMs: Long,
)

/** Maps Jellyfin DTOs to Room rows. Pure, so it is unit-tested against recorded server JSON. */
object ItemMapper {
    private const val TICKS_PER_MS = 10_000L

    /**
     * Maps one item, or returns null when its kind can't air. [existingId] keeps the local
     * row id of an item seen before, and [existingCollections] its box-set membership, which
     * item pages don't carry (reconciliation fills it in). A new episode starts with its
     * series' box sets.
     */
    fun map(item: BaseItemDto, context: SyncContext, existingId: Long = 0, existingCollections: String? = null): MediaItemEntity? {
        val seriesRemoteId = item.seriesId?.toJellyfinId()
        val series = seriesRemoteId?.let(context.series::get)
        val tags = pipeJoin(item.tags.orEmpty()).ifEmpty { series?.tags.orEmpty() }
        val type = LibraryRules.mediaType(item.type) ?: return null
        val isEpisode = type == MediaType.EPISODE || item.seriesId != null

        val source = item.mediaSources?.firstOrNull()
        val streams = source?.mediaStreams ?: item.mediaStreams.orEmpty()
        val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == source?.defaultAudioStreamIndex }
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
        val officialRating = item.officialRating?.takeIf { it.isNotBlank() } ?: series?.officialRating
        val remoteId = item.id.toJellyfinId()
        val tracks = tracks(streams, source?.defaultAudioStreamIndex, source?.mediaAttachments.orEmpty())

        return MediaItemEntity(
            id = existingId,
            serverId = context.serverId,
            libraryId = context.libraryId,
            remoteId = remoteId,
            type = type,
            title = item.name.orEmpty(),
            sortTitle = item.sortName,
            seriesRemoteId = seriesRemoteId,
            showTitle = item.seriesName,
            season = if (isEpisode) item.parentIndexNumber else null,
            episode = if (isEpisode) item.indexNumber else null,
            durationMs = (item.runTimeTicks ?: source?.runTimeTicks ?: 0L) / TICKS_PER_MS,
            genres = pipeJoin(item.genres.orEmpty()).ifEmpty { series?.genres.orEmpty() },
            studios = pipeJoin(item.studios.orEmpty().mapNotNull { it.name }).ifEmpty { series?.studios.orEmpty() },
            tags = tags,
            collections = existingCollections ?: series?.collections.orEmpty(),
            year = item.productionYear ?: item.premiereDate?.year,
            premiereDateUtcMs = item.premiereDate?.toEpochMs(),
            officialRating = officialRating,
            ratingScore = context.ratings.score(officialRating) ?: series?.ratingScore,
            overview = item.overview,
            communityRating = item.communityRating,
            hdrType = hdrType(video),
            audioFormat = audio?.let { audioFormat(it.codec, it.profile) },
            videoCodec = video?.codec,
            audioCodec = audio?.codec,
            container = source?.container ?: item.container,
            width = video?.width ?: item.width,
            height = video?.height ?: item.height,
            videoBitDepth = video?.bitDepth,
            audioChannels = audio?.channels,
            mediaSourceId = source?.id ?: remoteId.takeIf { type != MediaType.SERIES },
            tracks = tracks,
            audioLanguages = audioLanguages(tracks),
            primaryImageTag = item.imageTags?.get(ImageType.PRIMARY),
            playedOnServer = item.userData?.played ?: false,
            updatedAtUtcMs = context.nowUtcMs,
        )
    }

    /**
     * The audio and subtitle streams and the fonts an ASS subtitle may need. The audio stream
     * the server plays by default is flagged default.
     */
    fun tracks(streams: List<MediaStream>, defaultAudioIndex: Int?, attachments: List<MediaAttachment>): MediaTracks {
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }.map {
            AudioStream(it.index, it.language, isDefault = if (defaultAudioIndex != null) it.index == defaultAudioIndex else it.isDefault)
        }
        val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.map {
            SubtitleStream(it.index, it.language, it.title, it.codec, it.isDefault, it.isForced, it.isExternal)
        }
        val fonts = attachments
            .filter { FontAttachment.isFont(it.fileName, it.mimeType) }
            .mapNotNull { a -> a.fileName?.let { FontAttachment(a.index, it) } }
        return MediaTracks(audio, subtitles, fonts)
    }

    /** `|jpn|eng|`: the known audio languages, the default track's first; `""` when none is known. */
    fun audioLanguages(tracks: MediaTracks): String =
        pipeJoin(tracks.audio.sortedByDescending { it.isDefault }.mapNotNull { Languages.normalize(it.language) })

    fun hdrType(video: MediaStream?): HdrType = when (video?.videoRangeType) {
        VideoRangeType.HDR10 -> HdrType.HDR10
        VideoRangeType.HDR10_PLUS -> HdrType.HDR10_PLUS
        VideoRangeType.HLG -> HdrType.HLG
        VideoRangeType.DOVI,
        VideoRangeType.DOVI_WITH_HDR10,
        VideoRangeType.DOVI_WITH_HLG,
        VideoRangeType.DOVI_WITH_SDR,
        VideoRangeType.DOVI_WITH_EL,
        VideoRangeType.DOVI_WITH_HDR10_PLUS,
        VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
        -> HdrType.DOLBY_VISION
        else -> HdrType.SDR
    }

    /** The audio badge shown in the guide, e.g. "Atmos", "DTS-HD", "DD+". */
    fun audioFormat(codec: String?, profile: String?): String? {
        val c = codec?.lowercase() ?: return null
        val p = profile.orEmpty().lowercase()
        return when {
            "atmos" in p -> "Atmos"
            c == "dts" && ("dts:x" in p || "dts-x" in p) -> "DTS:X"
            c == "dts" && ("ma" in p || "hd" in p) -> "DTS-HD"
            c == "dts" -> "DTS"
            c == "truehd" -> "TrueHD"
            c == "eac3" -> "DD+"
            c == "ac3" -> "DD"
            c == "aac" -> "AAC"
            c == "flac" -> "FLAC"
            c == "opus" -> "Opus"
            c == "mp3" -> "MP3"
            else -> c.uppercase()
        }
    }

    /** `["Comedy", "Family"]` → `|Comedy|Family|`; an empty list → `""`. */
    fun pipeJoin(values: Collection<String>): String {
        val cleaned = values.map { it.replace('|', '/').trim() }.filter { it.isNotEmpty() }.distinct()
        return if (cleaned.isEmpty()) "" else cleaned.joinToString("|", prefix = "|", postfix = "|")
    }

    fun pipeSplit(value: String?): List<String> =
        value.orEmpty().split('|').filter { it.isNotEmpty() }
}
