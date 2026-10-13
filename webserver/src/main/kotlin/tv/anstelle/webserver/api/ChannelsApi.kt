package tv.anstelle.webserver.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.BuilderFields
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.channels.LibraryChannelLockedException
import tv.anstelle.core.data.channels.Lineup
import tv.anstelle.core.data.channels.LineupChoices
import tv.anstelle.core.data.channels.LineupMix
import tv.anstelle.core.data.channels.LineupReach
import tv.anstelle.core.data.channels.Pick
import tv.anstelle.core.data.channels.PlaybackChoices
import tv.anstelle.core.data.channels.Rule
import tv.anstelle.core.data.channels.RuleGroup
import tv.anstelle.core.data.channels.SinglePickOps
import tv.anstelle.core.data.channels.WeightSteps
import tv.anstelle.core.data.channels.fieldHint
import tv.anstelle.core.data.channels.fieldLabel
import tv.anstelle.core.data.channels.matchSummary
import tv.anstelle.core.data.channels.moviePlaybackHint
import tv.anstelle.core.data.channels.moviePlaybackLabel
import tv.anstelle.core.data.channels.opLabel
import tv.anstelle.core.data.channels.seriesPlaybackHint
import tv.anstelle.core.data.channels.seriesPlaybackLabel
import tv.anstelle.core.data.channels.weightHint
import tv.anstelle.core.data.channels.weightLabel
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogRepository
import tv.anstelle.core.data.ondemand.Shelf
import tv.anstelle.core.data.ondemand.ShelfOrder
import tv.anstelle.core.data.ondemand.ShelfType
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.RuleField
import tv.anstelle.core.model.RuleOp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channels, their lineups and groups, and On Demand's banners and rows, for the web UI. Missing channels and groups come back as
 * null or false; a change a library channel refuses throws [LibraryChannelLockedException], and
 * one that breaks a rule throws [IllegalArgumentException] saying why.
 */
interface ChannelsApi {
    suspend fun channels(): ChannelsDto
    suspend fun channel(channelId: Long): ChannelDetailDto?
    suspend fun update(channelId: Long, patch: ChannelPatch): Boolean
    suspend fun delete(channelId: Long): Boolean
    suspend fun create(request: NewChannelRequest): Long

    /** The channel's logo as WebP or PNG bytes, or null when it has none or doesn't exist. */
    suspend fun logo(channelId: Long): ByteArray?

    /** Stores an uploaded logo; throws [java.io.IOException] when [bytes] is not an image. */
    suspend fun setLogo(channelId: Long, bytes: ByteArray): Boolean
    suspend fun clearLogo(channelId: Long): Boolean

    suspend fun lineup(channelId: Long): LineupPreviewDto?
    suspend fun setLineup(channelId: Long, lineup: LineupDto): Boolean

    /** [lineup] with its rules described and picks titled, and what it airs. */
    suspend fun preview(lineup: LineupDto): LineupPreviewDto

    /** The fields, comparisons and weights the lineup builder offers. */
    suspend fun builder(): BuilderDto

    /** The values a rule lists (series or movies for [RuleField.SERIES] and [RuleField.ITEM]). */
    suspend fun options(field: String, op: String, query: String, from: String?): List<OptionDto>

    suspend fun groups(): GroupsDto
    suspend fun createGroup(name: String, start: Int): Long
    suspend fun updateGroup(groupId: Long, request: GroupRequest): Boolean
    suspend fun moveGroup(groupId: Long, delta: Int)
    suspend fun deleteGroup(groupId: Long)

    /** On Demand's banners and each catalog tab's rows, shared by every profile like the channels. */
    suspend fun onDemand(): OnDemandDto

    /**
     * Sets how many picks a banner deals: Home's for [kind] `home`, else that catalog kind's.
     * False for a kind that doesn't exist; a size not offered throws [IllegalArgumentException].
     */
    suspend fun setBanner(kind: String, size: Int): Boolean

    /** Stores a catalog kind's rows in the order of [keys]; false for a kind that doesn't exist. */
    suspend fun setRows(kind: String, keys: List<String>): Boolean
}

@Singleton
internal class RoomChannelsApi @Inject constructor(
    private val channels: ChannelRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) : ChannelsApi {
    private val choicesLock = Mutex()
    private var cached: Pair<LineupChoices, Long>? = null

    override suspend fun channels(): ChannelsDto = ChannelsDto(channels.observeChannels().first().map { it.toDto() })

    override suspend fun channel(channelId: Long): ChannelDetailDto? {
        val summary = summary(channelId) ?: return null
        val channel = summary.channel
        val result = choices().match(channels.lineup(channelId))
        val mix = LineupMix.of(result)
        return ChannelDetailDto(
            channel = summary.toDto(),
            series = channel.series.toDto(),
            movies = channel.movies.toDto(),
            oncePerDay = channel.moviesOncePerDay,
            airings = channels.upcoming(channelId, AIRINGS).map { AiringDto(it.startUtcMs, it.endUtcMs, it.title) },
            summary = matchSummary(result),
            airsSeries = mix.series,
            airsMovies = mix.movies,
            fitsOncePerDay = mix.fitsOncePerDay,
            seriesChoices = PlaybackChoices.map { ChoiceDto(it.toDto(), seriesPlaybackLabel(it), seriesPlaybackHint(it)) },
            movieChoices = PlaybackChoices.map { ChoiceDto(it.toDto(), moviePlaybackLabel(it), moviePlaybackHint(it)) },
        )
    }

    override suspend fun update(channelId: Long, patch: ChannelPatch): Boolean {
        channels.observeChannel(channelId).first() ?: return false
        patch.name?.let { name ->
            require(name.isNotBlank()) { "Give the channel a name." }
            channels.rename(channelId, name.trim())
        }
        patch.groupId?.let { channels.setGroup(channelId, it) }
        patch.favorite?.let { channels.setFavorite(channelId, it) }
        patch.visible?.let { channels.setHidden(channelId, !it) }
        patch.series?.let { channels.setSeriesPlayback(channelId, it.toPlayback()) }
        patch.movies?.let { channels.setMoviePlayback(channelId, it.toPlayback()) }
        patch.oncePerDay?.let { channels.setMoviesOncePerDay(channelId, it) }
        return true
    }

    override suspend fun delete(channelId: Long): Boolean {
        val channel = channels.observeChannel(channelId).first() ?: return false
        if (channel.fromLibrary) throw LibraryChannelLockedException()
        channels.delete(channelId)
        return true
    }

    override suspend fun create(request: NewChannelRequest): Long {
        val lineup = request.lineup.toLineup().requireUsable()
        return channels.create(request.name.trim().ifEmpty { "New channel" }, lineup, request.groupId)
    }

    override suspend fun logo(channelId: Long): ByteArray? {
        val file = channels.logoFile(channelId) ?: return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }

    override suspend fun setLogo(channelId: Long, bytes: ByteArray): Boolean {
        val channel = custom(channelId) ?: return false
        channels.setLogo(channel.id, bytes)
        return true
    }

    override suspend fun clearLogo(channelId: Long): Boolean {
        val channel = custom(channelId) ?: return false
        channels.clearLogo(channel.id)
        return true
    }

    override suspend fun lineup(channelId: Long): LineupPreviewDto? {
        custom(channelId) ?: return null
        return preview(channels.lineup(channelId))
    }

    override suspend fun setLineup(channelId: Long, lineup: LineupDto): Boolean {
        custom(channelId) ?: return false
        channels.setLineup(channelId, lineup.toLineup().requireUsable())
        return true
    }

    override suspend fun preview(lineup: LineupDto): LineupPreviewDto = preview(lineup.toLineup())

    override suspend fun builder(): BuilderDto = BuilderDto(
        fields = BuilderFields.map { field ->
            FieldDto(
                field = field.name,
                label = fieldLabel(field),
                hint = fieldHint(field),
                ops = Rule.opsFor(field).map { op ->
                    // Only keywords and "contains" are typed; everything else is picked from what the library has.
                    OpDto(op.name, opLabel(field, op), typed = field == RuleField.KEYWORD || op == RuleOp.CONTAINS, single = op in SinglePickOps)
                },
            )
        },
        weights = WeightSteps.map { WeightDto(it, weightLabel(it), weightHint(it)) },
    )

    override suspend fun options(field: String, op: String, query: String, from: String?): List<OptionDto> =
        choices().options(RuleField.valueOf(field), RuleOp.valueOf(op), query, from = from).map { OptionDto(it.value, it.label, it.count) }

    override suspend fun groups(): GroupsDto {
        val counts = channels.observeChannels().first().groupingBy { it.channel.groupId }.eachCount()
        return GroupsDto(
            groups = channels.observeGroups().first().map { GroupDto(it.id, it.name, it.numberStart, it.fromLibrary, counts[it.id] ?: 0) },
            nextStart = channels.observeNextGroupStart().first(),
            lastGroupId = channels.lastGroup(),
        )
    }

    override suspend fun createGroup(name: String, start: Int): Long = channels.createGroup(name, start)

    override suspend fun updateGroup(groupId: Long, request: GroupRequest): Boolean {
        val group = channels.observeGroups().first().firstOrNull { it.id == groupId } ?: return false
        request.name?.let { if (it.trim() != group.name) channels.renameGroup(groupId, it) }
        request.start?.let { channels.setGroupStart(groupId, it) }
        return true
    }

    override suspend fun moveGroup(groupId: Long, delta: Int) = channels.moveGroup(groupId, delta)

    override suspend fun deleteGroup(groupId: Long) = channels.deleteGroup(groupId)

    override suspend fun onDemand(): OnDemandDto = OnDemandDto(
        homeBanner = settings.get(Settings.OnDemandBanner),
        homeChoices = Settings.OnDemandBannerSizes.map { LabelledInt(it, "$it channels") },
        kinds = CatalogKind.entries.map { kind ->
            // Its fixed rows, then the library channels airing it, in the order stored for the tab.
            val available = kind.fixedShelves.map { Shelf(it) } + catalog.channelTitles(kind).map { (channel, _) -> Shelf(ShelfType.CHANNEL, channel) }
            val rows = ShelfOrder.arrange(available, ShelfOrder.decode(settings.get(Settings.catalogShelves(kind))))
            CatalogKindDto(
                key = kind.key,
                label = kind.label,
                banner = settings.get(Settings.catalogBanner(kind)),
                bannerChoices = Settings.OnDemandBannerSizes.map { LabelledInt(it, kind.count(it)) },
                rows = rows.map { ShelfDto(it.key, it.type.key, it.label, it.channel?.number) },
            )
        },
    )

    override suspend fun setBanner(kind: String, size: Int): Boolean {
        val key = if (kind == HOME) Settings.OnDemandBanner else CatalogKind.entries.firstOrNull { it.key == kind }?.let(Settings::catalogBanner) ?: return false
        require(size in Settings.OnDemandBannerSizes) { "Pick one of ${Settings.OnDemandBannerSizes.joinToString()}." }
        settings.set(key, size)
        return true
    }

    override suspend fun setRows(kind: String, keys: List<String>): Boolean {
        val catalogKind = CatalogKind.entries.firstOrNull { it.key == kind } ?: return false
        catalog.setShelfOrder(catalogKind, keys.map(String::trim).filter(String::isNotEmpty).distinct())
        return true
    }

    private suspend fun summary(channelId: Long): ChannelSummary? = channels.observeChannels().first().firstOrNull { it.channel.id == channelId }

    /** The channel when it exists and is one someone built; a library channel throws. */
    private suspend fun custom(channelId: Long): Channel? {
        val channel = channels.observeChannel(channelId).first() ?: return null
        if (channel.fromLibrary) throw LibraryChannelLockedException()
        return channel
    }

    private suspend fun preview(lineup: Lineup): LineupPreviewDto {
        val choices = choices()
        val reach = withContext(Dispatchers.Default) { choices.reach(lineup) }
        fun Pick.toDto() = PickDto(if (field == RuleField.ITEM) MOVIE else SERIES, remoteId, weight, choices.titleOf(remoteId) ?: NOT_IN_LIBRARY)
        return LineupPreviewDto(
            lineup = LineupDto(
                groups = lineup.groups.map { group ->
                    RuleGroupDto(group.rules.map { RuleDto(it.field.name, it.op.name, it.values, choices.describe(it)) }, group.weight)
                },
                includes = lineup.includes.map { it.toDto() },
                excludes = lineup.excludes.map { it.toDto() },
            ),
            reach = reach.toDto(),
        )
    }

    /**
     * The library as the builder sees it. Loading it reads every item, so it is kept for a
     * while: a lineup is built in many quick requests.
     */
    private suspend fun choices(): LineupChoices = choicesLock.withLock {
        val now = time.nowUtcMs()
        cached?.let { (choices, at) -> if (now - at < CHOICES_TTL_MS) return@withLock choices }
        LineupChoices(channels.catalog()).also { cached = it to now }
    }

    private fun ChannelSummary.toDto() = ChannelDto(
        id = channel.id,
        number = channel.number,
        name = channel.name,
        // Each upload gets a new file name, so the URL changes with the logo and can be cached.
        logoUrl = channel.logoUri?.let { uri -> "/api/channels/${channel.id}/logo?v=${uri.substringAfterLast('/').substringBefore('.')}" },
        isHidden = channel.isHidden,
        isFavorite = channel.isFavorite,
        groupId = channel.groupId,
        groupName = groupName,
        fromLibrary = channel.fromLibrary,
        seasonLabel = seasonLabel,
        inSeason = inSeason,
    )

    private companion object {
        const val AIRINGS = 3
        const val CHOICES_TTL_MS = 2 * 60 * 1000L
        const val HOME = "home"
        const val SERIES = "series"
        const val MOVIE = "movie"
        const val NOT_IN_LIBRARY = "Not in your library"

        fun Playback.toDto() = PlaybackDto(order.name, blockSize)

        fun PlaybackDto.toPlayback() = Playback(PlaybackOrder.valueOf(order), blockSize)

        fun LineupReach.toDto() = ReachDto(episodes, movies, videos, series, hours, episodeShare, movieShare, videoShare)

        fun LineupDto.toLineup(): Lineup {
            fun PickDto.toPick() = Pick(if (kind == MOVIE) RuleField.ITEM else RuleField.SERIES, remoteId, weight)
            return Lineup(
                groups = groups.map { group ->
                    RuleGroup(group.rules.map { Rule(RuleField.valueOf(it.field), RuleOp.valueOf(it.op), it.values.map(String::trim)) }, group.weight)
                },
                includes = includes.map { it.toPick() },
                excludes = excludes.map { it.toPick() },
            )
        }

        fun Lineup.requireUsable(): Lineup {
            require(!isEmpty) { "Add a rule or a show first." }
            problem()?.let { throw IllegalArgumentException(it) }
            return this
        }
    }
}
