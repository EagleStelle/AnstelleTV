package tv.anstelle.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.common.LogEntry
import tv.anstelle.core.common.LogLevel
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.components.PageHeader
import tv.anstelle.core.theme.components.SectionLabel
import tv.anstelle.core.theme.components.pagePadding

import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor() : ViewModel() {
    /** The newest log entries; refreshed whenever a new entry is written. */
    val logs: StateFlow<List<LogEntry>> = AppLog.lastSequence
        .map { AppLog.snapshot(limit = MAX_LINES).asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val MAX_LINES = 200
    }
}

/** Sessions, sync status and stats arrive in Phase 9; the log viewer works now. */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val firstFocus = remember { FocusRequester() }
    val hasLogs = logs.isNotEmpty()
    if (isTv && hasLogs) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background),
        contentPadding = PaddingValues(horizontal = pagePadding, vertical = if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            PageHeader(
                "Dashboard",
                subtitle = "The newest log lines first. Sessions, sync status and stats join them later.",
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (logs.isEmpty()) {
            item { SectionLabel("Nothing logged yet") }
        }
        itemsIndexed(logs, key = { _, entry -> entry.sequence }) { index, entry ->
            LogRow(
                time = timeFormat.format(Instant.ofEpochMilli(entry.timeUtcMs)),
                entry = entry,
                // Focusable rows let the D-pad scroll the list on TV.
                modifier = when {
                    !isTv -> Modifier
                    index == 0 -> Modifier.focusRequester(firstFocus)
                    else -> Modifier
                },
            )
        }
    }
}

/**
 * One log line: the time in tabular figures, the level as a letter on a block (filled for
 * warnings and errors, outlined otherwise), the tag and the message. Focus fills the row.
 */
@Composable
private fun LogRow(time: String, entry: LogEntry, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val serious = entry.level >= LogLevel.WARN
    var focused by remember { mutableStateOf(false) }
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) theme.accent else if (serious) theme.surface else Color.Transparent, ControlShape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (isTv) Modifier.focusable() else Modifier)
            .padding(horizontal = 8.dp, vertical = if (isTv) 6.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val ink = if (focused) theme.onAccent else theme.text
        androidx.compose.material3.Text(time, style = typography.labelMedium, color = if (focused) ink else theme.textMuted)
        androidx.compose.material3.Text(
            entry.level.name.first().toString(),
            style = typography.labelMedium,
            color = when {
                focused -> theme.onAccent
                serious -> theme.onAccent
                else -> theme.textMuted
            },
            modifier = Modifier
                .then(if (serious && !focused) Modifier.background(theme.accent, ControlShape) else Modifier.border(1.dp, if (focused) theme.onAccent else theme.line, ControlShape))
                .padding(horizontal = 5.dp),
        )
        androidx.compose.material3.Text(
            "${entry.tag}  ${entry.message}",
            style = if (isTv) typography.bodyMedium else typography.bodySmall,
            color = if (focused || serious) ink else theme.textMuted,
            modifier = Modifier.weight(1f),
        )
    }
}
