package tv.anstelle.feature.guide

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.guide.GuideRepository
import tv.anstelle.core.data.guide.ProgramDetails
import tv.anstelle.core.data.guide.ProgramStrip
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.surfNeighbours
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.ProfileAccess
import javax.inject.Inject

/** A channel row's header. */
@Immutable
data class GuideChannel(
    val id: Long,
    val number: Int,
    val name: String,
    /** The stored logo's `file:` URI, or null. */
    val logo: String?,
    val isFavorite: Boolean,
)

@Immutable
data class GuideTab(val key: String, val label: String)

@Immutable
data class GuideUiState(
    val loaded: Boolean = false,
    /** Channels on the open tab, in number order. */
    val channels: List<GuideChannel> = emptyList(),
    val tabs: List<GuideTab> = emptyList(),
    val tab: String = GuideViewModel.TAB_ALL,
    /** False when there is no channel to watch at all. */
    val hasChannels: Boolean = false,
    /** True when any channel on any tab has a logo: the channel column then widens for it, the same on every tab. */
    val hasLogos: Boolean = false,
    /** What the profile may watch: programs beyond it are blacked out. */
    val access: ProfileAccess = ProfileAccess.OPEN,
)

/**
 * The guide: channels by tab (all, favorites, groups), their cells by 6-hour window, the
 * focused program's details, and the preview window's playback.
 *
 * Windows are read from SQLite for every channel at once and kept in a small LRU cache; the
 * windows next to the visible one are fetched ahead. Schedule or library changes clear the
 * cache and the visible windows load again.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val channelRepository: ChannelRepository,
    private val guide: GuideRepository,
    private val settings: SettingsRepository,
    private val playback: ChannelPlayer,
    private val time: TimeSource,
    changes: DatabaseChanges,
) : ViewModel() {
    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    private val _strips = MutableStateFlow<Map<Long, ProgramStrip>>(emptyMap())

    /** Each channel's cells over the loaded windows. */
    val strips: StateFlow<Map<Long, ProgramStrip>> = _strips.asStateFlow()

    private val _details = MutableStateFlow<ProgramDetails?>(null)

    /** The details of the program the header describes, or null off air or while loading. */
    val details: StateFlow<ProgramDetails?> = _details.asStateFlow()

    val playbackState = playback.state
    val player = playback.player

    private val _follow = MutableStateFlow<Long?>(null)

    /**
     * A channel the preview opened on at launch, came back on from the full-screen player, or
     * changed to while the guide was out of view; the selection moves to it, its row to the
     * top. Requested on every entry, even when playback is unchanged. Cleared by [followed].
     */
    val follow: StateFlow<Long?> = _follow.asStateFlow()

    private val tab = MutableStateFlow<String?>(null)
    private var profileId = 0L
    /** What the profile may watch: back-to-back programs beyond it show as one cell. */
    private var access = ProfileAccess.OPEN
    private var tunable: List<Channel> = emptyList()
    /** True while the guide is on screen. */
    private var previewWanted = false
    private var previewStarted = false

    /** The schedule, library or lineup changed while the guide was out of view: its windows load again when it shows. */
    private var stale = false

    /** True while the full-screen player the guide opened is on top of it. */
    private var inPlayer = false

    /** Loaded windows by index (start / [WINDOW_MS]), least recently used first. */
    private val windows = LinkedHashMap<Long, Map<Long, ProgramStrip>>(16, 0.75f, true)
    private val loading = HashSet<Long>()
    private var wantedFrom = 0L
    private var wantedTo = 0L
    private var generation = 0
    private var detailsJob: Job? = null
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val profile = profiles.activeProfile()
            profileId = profile.id
            tab.value = settings.get(Settings.guideTab(profile.id))
            combine(
                channelRepository.observeChannels(),
                channelRepository.observeGroups(),
                tab,
                profiles.observeAccess(profile.id),
            ) { channels, groups, open, profileAccess ->
                Lineup(channels, groups, open ?: TAB_ALL, profileAccess)
            }.collect { (channels, groups, open, profileAccess) ->
                val accessChanged = profileAccess != access
                access = profileAccess
                tunable = profiles.tunable(channels, profileAccess)
                playback.setLineup(tunable)
                val tabs = buildList {
                    add(GuideTab(TAB_ALL, "All channels"))
                    add(GuideTab(TAB_FAVORITES, "Favorites"))
                    for (group in groups) {
                        if (tunable.any { it.groupId == group.id }) add(GuideTab(groupTab(group.id), group.name))
                    }
                }
                val key = open.takeIf { k -> tabs.any { it.key == k } } ?: TAB_ALL
                val shown = when {
                    key == TAB_FAVORITES -> tunable.filter { it.isFavorite }
                    key.startsWith(GROUP_PREFIX) -> key.removePrefix(GROUP_PREFIX).toLongOrNull().let { id -> tunable.filter { it.groupId == id } }
                    else -> tunable
                }
                _state.value = GuideUiState(
                    loaded = true,
                    channels = shown.map { GuideChannel(it.id, it.number, it.name, it.logoUri, it.isFavorite) },
                    tabs = tabs,
                    tab = key,
                    hasChannels = tunable.isNotEmpty(),
                    hasLogos = tunable.any { it.logoUri != null },
                    access = profileAccess,
                )
                // New or changed channels: their cells load with the next window read.
                if (windows.isNotEmpty() && windows.values.first().keys != tunable.mapTo(HashSet()) { it.id }) {
                    reloadWhenShown()
                } else if (accessChanged && windows.isNotEmpty()) {
                    publish()
                }
                if (previewWanted && !previewStarted) startPreview()
            }
        }
        viewModelScope.launch {
            changes.tables
                .filter { tables -> tables.any { it in GuideTables } }
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { reloadWhenShown() }
        }
    }

    /** Reads the visible windows again now, or once the guide is back on screen (seeks and syncs under the player, say). */
    private fun reloadWhenShown() {
        if (previewWanted) reload() else stale = true
    }

    fun selectTab(key: String) {
        tab.value = key
        viewModelScope.launch { settings.set(Settings.guideTab(profileId), key) }
    }

    /** The grid shows `[fromUtcMs, toUtcMs)`: load it and the windows on either side. */
    fun showing(fromUtcMs: Long, toUtcMs: Long) {
        wantedFrom = fromUtcMs
        wantedTo = toUtcMs
        if (tunable.isEmpty()) return
        val first = Math.floorDiv(fromUtcMs, WINDOW_MS) - 1
        val last = Math.floorDiv(toUtcMs, WINDOW_MS) + 1
        for (index in first..last) {
            if (index in windows || index in loading) continue
            load(index)
        }
    }

    private fun load(index: Long) {
        loading += index
        val ids = tunable.map { it.id }
        val loadGeneration = generation
        viewModelScope.launch {
            try {
                val window = guide.window(ids, index * WINDOW_MS, (index + 1) * WINDOW_MS)
                if (loadGeneration != generation) return@launch
                windows[index] = window
                evict()
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Reading the guide failed", e)
            } finally {
                if (loadGeneration == generation) loading -= index
            }
        }
    }

    private fun evict() {
        val keep = Math.floorDiv(wantedFrom, WINDOW_MS) - 1..Math.floorDiv(wantedTo, WINDOW_MS) + 1
        val iterator = windows.keys.iterator()
        while (windows.size > MAX_WINDOWS && iterator.hasNext()) {
            if (iterator.next() !in keep) iterator.remove()
        }
    }

    private fun publish() {
        val ordered = windows.entries.sortedBy { it.key }.map { it.value }
        _strips.value = tunable.associate { channel ->
            channel.id to ProgramStrip.merge(ordered.mapNotNull { it[channel.id] }).mergeRestricted(access)
        }
    }

    /** Drops every window and reads the visible ones again. */
    private fun reload() {
        generation++
        windows.clear()
        loading.clear()
        if (wantedTo > wantedFrom) showing(wantedFrom, wantedTo)
    }

    /** The details describe [mediaItemId], or an off-air stretch when it is 0. */
    fun describe(mediaItemId: Long) {
        detailsJob?.cancel()
        if (mediaItemId == 0L) {
            _details.value = null
        } else if (_details.value?.mediaItemId != mediaItemId) {
            detailsJob = viewModelScope.launch {
                delay(DETAILS_DEBOUNCE_MS)
                _details.value = guide.details(mediaItemId)
            }
        }
    }

    /** The guide is on screen: the preview window plays the channel on now, or the last one watched. */
    fun startPreview() {
        previewWanted = true
        if (stale) {
            stale = false
            reload()
        }
        if (previewStarted || tunable.isEmpty()) return
        previewStarted = true
        playback.acquire()
        inPlayer = false
        previewJob = viewModelScope.launch {
            val last = settings.get(Settings.lastChannel(profileId))
            if (!previewWanted || inPlayer) return@launch
            val channel = playback.state.value.channel?.takeIf { on -> tunable.any { it.id == on.id } }
                ?: tunable.firstOrNull { it.id == last }
                ?: tunable.firstOrNull() ?: return@launch
            playback.tune(channel.id)
            // At launch the grid picked its first selection without waiting for the last channel to be read.
            _follow.value = channel.id
        }
    }

    /** The guide is opening the full-screen player; back from it, the grid returns to the channel left on. */
    fun openingPlayer() {
        inPlayer = true
        previewJob?.cancel()
    }

    /** The selection moved to [follow]'s channel. */
    fun followed() {
        _follow.value = null
    }

    /** Shows [channelId] in the preview window and readies the row browsing heads to next. */
    fun preview(channelId: Long) {
        if (previewJob?.isActive == true || _follow.value != null) return
        if (!previewWanted || !previewStarted || inPlayer) return
        val around = neighbourRows(_state.value.channels, from = playback.state.value.channel?.id, to = channelId)
        playback.tune(channelId)
        if (around.isNotEmpty()) playback.prewarm(around)
    }

    fun stopPreview() {
        previewWanted = false
        previewJob?.cancel()
        // The channel in the preview when the guide last went out of view is followed again on every entry.
        _follow.value = null
        if (!previewStarted) return
        previewStarted = false
        playback.release()
    }

    private var gridState: GuideGridState? = null

    /**
     * The grid's hours and selection. They live here so the guide keeps its place while the
     * full-screen player or another page is on top; a new launch opens at the current half hour.
     * Each return follows the playing channel and selects what is airing now.
     */
    fun grid(pxPerMs: Float): GuideGridState =
        gridState?.takeIf { it.pxPerMs == pxPerMs }
            ?: GuideGridState(GuideGridState.earliest(time.nowUtcMs()), pxPerMs).also { gridState = it }

    override fun onCleared() {
        stopPreview()
    }

    companion object {
        private const val TAG = "Guide"
        const val TAB_ALL = "all"
        const val TAB_FAVORITES = "favorites"
        private const val GROUP_PREFIX = "group:"
        private const val WINDOW_MS = 6 * 60 * 60_000L
        private const val MAX_WINDOWS = 8
        private const val RELOAD_DEBOUNCE_MS = 1_500L
        private const val DETAILS_DEBOUNCE_MS = 120L
        private val GuideTables = setOf("schedule_slot", "media_item")

        private fun groupTab(groupId: Long) = "$GROUP_PREFIX$groupId"
    }
}

/** The rows either side of [to], the one in the direction browsing moved from [from] first (down when [from] isn't a row). */
internal fun neighbourRows(rows: List<GuideChannel>, from: Long?, to: Long): List<Long> {
    val target = rows.indexOfFirst { it.id == to }
    val step = if (rows.indexOfFirst { it.id == from } > target) -1 else 1
    return surfNeighbours(rows.map { it.id }, target, step, wrap = false)
}

/** What the guide's rows are made from. */
private data class Lineup(
    val channels: List<ChannelSummary>,
    val groups: List<ChannelGroup>,
    val tab: String,
    val access: ProfileAccess,
)
