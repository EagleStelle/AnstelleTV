package tv.anstelle.feature.dashboard

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import tv.anstelle.core.data.stats.ScreenTime
import tv.anstelle.core.data.stats.StatsPeriod
import tv.anstelle.core.data.stats.TopEntry
import tv.anstelle.core.data.stats.TopLists
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.Backdrop
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.OsdTab
import tv.anstelle.core.theme.components.PlaceholderAction
import tv.anstelle.core.theme.components.PlaceholderScreen
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.TitleLogo
import tv.anstelle.core.theme.components.pagePadding
import tv.anstelle.core.theme.components.rememberInitialFocus

/** What the ranked list ranks, in the tabs' order (user decision): channels first. */
internal enum class Ranking(val label: String) {
    CHANNELS("Channels"),
    MOVIES("Movies"),
    SERIES("Series"),
    GENRES("Genres"),
}

private fun TopLists.of(ranking: Ranking): List<TopEntry> = when (ranking) {
    Ranking.CHANNELS -> channels
    Ranking.MOVIES -> movies
    Ranking.SERIES -> series
    Ranking.GENRES -> genres
}

/**
 * The watching profile's own watch stats as a two-pane console, the Settings menu's own split:
 * what was watched most, ranked, on the ground; the meter on coal beside it (today against the
 * daily average, the period's chart, habits, and Turn off at its foot). Picking a bar on the
 * chart re-deals the ranks for that hour, day or month. Phones stack the meter under the ranks.
 * The page has no title while there is something to show, like Live TV and On Demand.
 */
@Composable
fun DashboardScreen(
    onTurnedOff: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var asking by remember { mutableStateOf(false) }
    var ranking by rememberSaveable { mutableStateOf(Ranking.CHANNELS) }
    if (state.enabled == false) LaunchedEffect(Unit) { onTurnedOff() }

    Box(modifier.fillMaxSize().background(theme.background)) {
        val screen = state.screen
        when {
            state.enabled != true || screen == null -> Unit
            screen.firstDay == null -> PlaceholderScreen(
                title = "Dashboard",
                description = "Nothing watched yet. Your watch time and most watched show here once a program plays for 30 seconds.",
                actions = listOf(PlaceholderAction("Turn off", "Stop keeping what this profile watches", Icons.Filled.PowerSettingsNew) { asking = true }),
            )
            isTv || LocalConfiguration.current.screenWidthDp >= SPLIT_DP -> Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = pagePadding, end = 28.dp, top = if (isTv) 28.dp else 20.dp, bottom = 24.dp),
                ) {
                    RankingHeader(ranking, { ranking = it }, spanName(state, screen))
                    Ranks(ranking, state.lists, Modifier.padding(top = 14.dp))
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(theme.line))
                Meter(
                    state, screen, viewModel, onTurnOff = { asking = true },
                    Modifier
                        .weight(0.44f)
                        .fillMaxHeight()
                        .background(theme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (isTv) 28.dp else 24.dp, vertical = if (isTv) 28.dp else 20.dp),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "ranking") {
                    Column(Modifier.padding(start = pagePadding, end = pagePadding, top = 12.dp, bottom = 20.dp)) {
                        RankingHeader(ranking, { ranking = it }, spanName(state, screen))
                        Ranks(ranking, state.lists, Modifier.padding(top = 12.dp))
                    }
                }
                item(key = "meter") {
                    Meter(
                        state, screen, viewModel, onTurnOff = { asking = true },
                        Modifier.fillMaxWidth().background(theme.surface).padding(horizontal = pagePadding, vertical = 20.dp),
                    )
                }
            }
        }
        if (asking) {
            DashboardOffDialog(
                onKeep = { asking = false; viewModel.turnOff(keepHistory = true) },
                onDelete = { asking = false; viewModel.turnOff(keepHistory = false) },
                onDismiss = { asking = false },
            )
        }
    }
}

/** What the ranks and the chart's reading cover: the period, or its picked hour, day or month. */
@Composable
private fun spanName(state: DashboardUiState, screen: ScreenTime): String {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val bar = state.pick.bar?.takeIf { state.pick.window == screen.window }
    return bar?.let { barName(screen.window, it, is24Hour) } ?: windowName(screen.window, state.today)
}

/**
 * The ranking tabs with the span they cover pinned at the row's end; on a screen too narrow for
 * all four, the tabs scroll sideways under it.
 */
@Composable
private fun RankingHeader(ranking: Ranking, onRanking: (Ranking) -> Unit, span: String) {
    val theme = LocalAnstelleTheme.current
    val firstFocus = rememberInitialFocus()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (option in Ranking.entries) {
                val open = option == ranking
                OsdTab(
                    option.label,
                    selected = open,
                    onClick = { onRanking(option) },
                    compact = true,
                    modifier = if (open) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        androidx.compose.material3.Text(
            span.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = theme.textMuted,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Ranks one to five: the first large on its backdrop, the rest as ruled lines. A new span or
 * ranking cross-fades in, so picking a bar on the chart reads as the ranks re-dealing.
 */
@Composable
private fun Ranks(ranking: Ranking, lists: TopLists?, modifier: Modifier) {
    val shown = lists?.of(ranking)
    val total = lists?.totalMs ?: 0L
    Crossfade(targetState = shown, animationSpec = tween(RE_DEAL_MS), label = "ranks", modifier = modifier) { entries ->
        when {
            entries == null -> Spacer(Modifier.height(1.dp))
            entries.isEmpty() -> NothingRanked(ranking)
            else -> Column {
                LeadRank(entries.first(), share(entries.first(), total), ranking)
                entries.drop(1).forEachIndexed { i, entry ->
                    if (i > 0) Hairline(Modifier.padding(start = 8.dp))
                    RankRow(rank = i + 2, entry = entry, share = share(entry, total))
                }
            }
        }
    }
}

private fun share(entry: TopEntry, totalMs: Long) = if (totalMs > 0) entry.playedMs.toFloat() / totalMs else 0f

/**
 * Number one: its backdrop under the wash in a 3 dp inset that focus fills rose, the rose rank
 * block, the logo (a channel's number block and name, a genre's name), the time and its share.
 */
@Composable
private fun LeadRank(entry: TopEntry, share: Float, ranking: Ranking) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(onClick = {}, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Backdrop(entry.artwork.backdropUrl, Modifier.padding(3.dp).fillMaxWidth().height(if (isTv) 164.dp else 184.dp), alignment = Alignment.Center) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (isTv) 18.dp else 14.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 14.dp),
            ) {
                RankBlock(1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val number = entry.number
                    if (number != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelNumber(number, focused = false)
                            androidx.compose.material3.Text(entry.title, style = typography.headlineMedium, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        TitleLogo(
                            logoUrl = entry.artwork.logoUrl.takeIf { ranking != Ranking.GENRES },
                            title = entry.title,
                            height = if (isTv) 48.dp else 40.dp,
                            maxLogoWidth = 280.dp,
                            style = typography.headlineMedium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Text(duration(entry.playedMs), style = typography.titleLarge.figures(), color = theme.text, maxLines = 1)
                        SegmentLadder(share, segments = 24, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Text(percent(share), style = typography.labelMedium, color = theme.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Ranks two to five: the rank figure, a still, the name over its share, the time. Focus fills it. */
@Composable
private fun RankRow(rank: Int, entry: TopEntry, share: Float) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    FocusSurface(onClick = {}, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = if (isTv) 8.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 12.dp),
        ) {
            androidx.compose.material3.Text(
                rank.toString(),
                style = typography.headlineSmall.figures(),
                color = if (focused) theme.onAccent else theme.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(26.dp),
            )
            Box(Modifier.width(if (isTv) 88.dp else 72.dp).aspectRatio(16f / 9f).background(theme.surfaceHigh, ControlShape)) {
                entry.artwork.backdropUrl?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.number?.let { ChannelNumber(it, focused) }
                    androidx.compose.material3.Text(entry.title, style = typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SegmentLadder(share, segments = 16, color = if (focused) theme.onAccent else theme.accent, modifier = Modifier.width(if (isTv) 136.dp else 112.dp))
            }
            androidx.compose.material3.Text(duration(entry.playedMs), style = typography.labelLarge.figures(), maxLines = 1)
        }
    }
}

@Composable
private fun NothingRanked(ranking: Ranking) {
    androidx.compose.material3.Text(
        "No ${ranking.label.lowercase()} watched here.",
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = LocalAnstelleTheme.current.textMuted,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
    )
}

/** Number one's rank as the OSD's number block: rose, ink figures. */
@Composable
private fun RankBlock(rank: Int) {
    val theme = LocalAnstelleTheme.current
    Box(Modifier.size(if (LocalIsTv.current) 56.dp else 48.dp).background(theme.accent, ControlShape), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            rank.toString(),
            style = androidx.compose.material3.MaterialTheme.typography.displaySmall.figures(),
            color = theme.onAccent,
            maxLines = 1,
        )
    }
}

/** A channel's number on its block; ink with rose figures on a focused row, so it still reads. */
@Composable
private fun ChannelNumber(number: Int, focused: Boolean) {
    val theme = LocalAnstelleTheme.current
    androidx.compose.material3.Text(
        number.toString(),
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge.figures(),
        color = if (focused) theme.accent else theme.onAccent,
        maxLines = 1,
        modifier = Modifier.background(if (focused) theme.onAccent else theme.accent, ControlShape).padding(horizontal = 6.dp),
    )
}

/**
 * The meter on coal: today against the daily average as two ladders, the period tabs and
 * stepper with the span's total, the chart and its key, the habits as ruled readings, and Turn
 * off at the foot.
 */
@Composable
private fun Meter(state: DashboardUiState, screen: ScreenTime, viewModel: DashboardViewModel, onTurnOff: () -> Unit, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val window = screen.window
    val bar = state.pick.bar?.takeIf { state.pick.window == window }
    val live = bar?.let { screen.live[it] } ?: screen.live.sum()
    val onDemand = bar?.let { screen.onDemand[it] } ?: screen.onDemand.sum()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Today(screen.todayMs, screen.weekAverageMs)
        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (period in StatsPeriod.entries) {
                OsdTab(periodName(period), selected = period == state.pick.window.period, onClick = { viewModel.setPeriod(period) }, compact = true)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier", state.canGoBack) { viewModel.step(-1) }
            androidx.compose.material3.Text(spanName(state, screen).uppercase(), style = typography.labelMedium, color = theme.text, maxLines = 1)
            StepKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later", state.canGoForward) { viewModel.step(1) }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Text(duration(live + onDemand), style = typography.headlineSmall.figures(), color = theme.text, maxLines = 1)
        }
        ScreenTimeChart(
            live = screen.live,
            onDemand = screen.onDemand,
            picked = bar,
            axis = { axisName(window, it, is24Hour) },
            axisPick = { axisPick(window, it, is24Hour) },
            description = "${periodName(window.period)} chart, ${duration(live + onDemand)} watched",
            onMove = viewModel::moveBar,
            onPick = viewModel::pickBar,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Key(theme.accent, "Live TV", duration(live))
            Key(theme.textMuted, "On Demand", duration(onDemand))
        }
        Readings(screen, Modifier.padding(top = 6.dp))
        TurnOff(onTurnOff, Modifier.padding(top = 10.dp))
    }
}

/** Today against the daily average: two ladders on one scale, the OSD's way of setting a level. */
@Composable
private fun Today(todayMs: Long, averageMs: Long?) {
    val theme = LocalAnstelleTheme.current
    val scale = maxOf(todayMs, averageMs ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Level("Today", todayMs, todayMs.toFloat() / scale, theme.accent, large = true)
        averageMs?.let { Level("Daily average", it, it.toFloat() / scale, theme.textMuted, large = false) }
    }
}

@Composable
private fun Level(label: String, valueMs: Long, fraction: Float, color: Color, large: Boolean) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            androidx.compose.material3.Text(label.uppercase(), style = typography.labelMedium, color = theme.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            androidx.compose.material3.Text(duration(valueMs), style = (if (large) typography.headlineMedium else typography.titleMedium).figures(), color = theme.text, maxLines = 1)
        }
        SegmentLadder(fraction, segments = 30, color = color)
    }
}

/** A key to the chart: the series' square, its name and its time. */
@Composable
private fun Key(color: Color, label: String, value: String) {
    val theme = LocalAnstelleTheme.current
    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color))
        androidx.compose.material3.Text(label.uppercase(), style = style, color = theme.textMuted, maxLines = 1)
        androidx.compose.material3.Text(value.uppercase(), style = style, color = theme.text, maxLines = 1)
    }
}

/** The period's habits as a readout: each a name and its reading on one line, ruled apart. */
@Composable
private fun Readings(screen: ScreenTime, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val habits = screen.habits
    val readings = buildList {
        if (screen.window.period == StatsPeriod.MONTH || screen.window.period == StatsPeriod.YEAR) {
            habits.dailyAverageMs?.let { add("Daily average" to duration(it)) }
        }
        add("Peak hour" to (habits.peakHour?.let { hourName(it, is24Hour) } ?: "None"))
        add("Current streak" to dayCount(habits.streaks.current))
        add("Best streak" to dayCount(habits.streaks.best))
        add("Longest sitting" to duration(habits.longestSittingMs))
        add("Views" to habits.views.toString())
        add("Episodes finished" to habits.episodesFinished.toString())
        add("Movies finished" to habits.moviesFinished.toString())
    }
    Column(modifier) {
        Hairline()
        for ((label, value) in readings) {
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(label.uppercase(), style = typography.labelMedium, color = theme.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                androidx.compose.material3.Text(value.uppercase(), style = typography.titleSmall.figures(), color = theme.text, maxLines = 1)
            }
            Hairline()
        }
    }
}

/** Turn off, one step up from the panel's coal. */
@Composable
private fun TurnOff(onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(onClick = onClick, color = LocalAnstelleTheme.current.surfaceHigh, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.height(if (LocalIsTv.current) 52.dp else 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
            androidx.compose.material3.Text("Turn off", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}

/** A small key that steps the period; one that can't is muted, and focus passes over it. */
@Composable
private fun StepKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    FocusSurface(onClick = onClick, enabled = enabled, color = theme.surfaceHigh, modifier = Modifier.size(if (LocalIsTv.current) 36.dp else 44.dp)) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) androidx.compose.material3.LocalContentColor.current else theme.textMuted.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.Center).size(22.dp),
        )
    }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalAnstelleTheme.current.line))
}

/** The condensed cut with tabular figures, for every number that changes in place. */
@Composable
private fun TextStyle.figures(): TextStyle = merge(TextStyle(fontFamily = LocalAnstelleTheme.current.displayFont, fontFeatureSettings = "tnum"))

private fun percent(share: Float): String = "${(share * 100).toInt().coerceIn(0, 100)}%"

/** From this width the dashboard splits into its two panes, as on TV. */
private const val SPLIT_DP = 720

/** The ranks re-deal in one hard snap. */
private const val RE_DEAL_MS = 140
