package tv.anstelle.feature.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.stats.DashboardHistory
import tv.anstelle.core.data.stats.DashboardStats
import tv.anstelle.core.data.stats.ScreenTime
import tv.anstelle.core.data.stats.StatsPeriod
import tv.anstelle.core.data.stats.StatsWindow
import tv.anstelle.core.data.stats.TopLists
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/** The period on the chart and the bar picked in it; null covers the whole period. */
data class Pick(val window: StatsWindow, val bar: Int?)

@Immutable
data class DashboardUiState(
    /** Null until known. */
    val enabled: Boolean? = null,
    val pick: Pick,
    /**
     * The chart and habits of [pick]'s window, or of the one before while its read runs (it
     * carries its own window); null while the first read runs.
     */
    val screen: ScreenTime? = null,
    /** The lists for [pick]; null while the first read runs. */
    val lists: TopLists? = null,
    val today: LocalDate,
) {
    val canGoBack: Boolean get() = screen?.firstDay?.let { it < pick.window.firstDay } == true
    val canGoForward: Boolean get() = pick.window.last < today
}

/**
 * The watching profile's own dashboard. Figures are read only while it is open, again whenever a
 * view stops; picking a bar reads only the lists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val stats: DashboardStats,
    private val history: DashboardHistory,
    profiles: ProfileRepository,
) : ViewModel() {
    private val profileId = viewModelScope.async(start = CoroutineStart.LAZY) { profiles.activeProfile().id }
    private val weekStart = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val today get() = LocalDate.ofEpochDay(stats.today())

    private val pick = MutableStateFlow(Pick(StatsWindow.of(StatsPeriod.WEEK, today, weekStart), bar = null))
    private val changes = stats.changes().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val screen = combine(changes, pick.map { it.window }.distinctUntilChanged()) { _, window -> window }
        .mapLatest { stats.screenTime(profileId.await(), it) }

    private val lists = combine(changes, pick) { _, pick -> pick }
        .mapLatest { stats.topLists(profileId.await(), it.window.span(it.bar, ZoneId.systemDefault())) }

    private val enabled = flow { emit(profileId.await()) }.flatMapLatest { history.observeEnabled(it) }

    val state: StateFlow<DashboardUiState> = combine(
        enabled,
        pick,
        screen.map<ScreenTime, ScreenTime?> { it }.onStart { emit(null) },
        lists.map<TopLists, TopLists?> { it }.onStart { emit(null) },
    ) { enabled, pick, screen, lists ->
        DashboardUiState(enabled, pick, screen, lists, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState(pick = pick.value, today = today))

    fun setPeriod(period: StatsPeriod) {
        if (period == pick.value.window.period) return
        pick.value = Pick(StatsWindow.of(period, today, weekStart), bar = null)
    }

    /** The period before or after; never past today's. */
    fun step(by: Int) {
        val state = state.value
        if (by < 0 && !state.canGoBack || by > 0 && !state.canGoForward) return
        pick.value = Pick(pick.value.window.step(by), bar = null)
    }

    /**
     * Moves the picked bar [by] one, onto the period before or after at either end. With none
     * picked, it picks the bar for now, or the period's last. False when there is nowhere to go.
     */
    fun moveBar(by: Int): Boolean {
        val (window, bar) = pick.value
        val nowBar = currentBar(window)
        if (bar == null) {
            pick.update { it.copy(bar = nowBar ?: (window.bars - 1)) }
            return true
        }
        val next = bar + by
        when {
            next < 0 -> if (state.value.canGoBack) window.step(-1).let { pick.value = Pick(it, it.bars - 1) } else return false
            next > (nowBar ?: (window.bars - 1)) -> if (state.value.canGoForward) pick.value = Pick(window.step(1), 0) else return false
            else -> pick.update { it.copy(bar = next) }
        }
        return true
    }

    /** Picks [bar], or the whole period again when it was already picked. */
    fun pickBar(bar: Int?) {
        pick.update { it.copy(bar = if (bar == it.bar) null else bar) }
    }

    fun clearBar() = pick.update { it.copy(bar = null) }

    fun turnOff(keepHistory: Boolean) {
        viewModelScope.launch { history.turnOff(profileId.await(), keepHistory) }
    }

    /** The bar holding now, when [window] does; later bars have nothing yet. */
    private fun currentBar(window: StatsWindow): Int? {
        val today = today
        if (today.toEpochDay() !in window) return null
        return if (window.period == StatsPeriod.DAY) LocalTime.now().hour else window.barOf(today.toEpochDay())
    }
}
