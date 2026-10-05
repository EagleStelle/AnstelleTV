package tv.anstelle.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
            .background(theme.background)
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            androidx.compose.material3.Text(
                "Dashboard · Logs",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        itemsIndexed(logs, key = { _, entry -> entry.sequence }) { index, entry ->
            androidx.compose.material3.Text(
                "${timeFormat.format(Instant.ofEpochMilli(entry.timeUtcMs))}  ${entry.level.name.first()}  ${entry.tag}: ${entry.message}",
                fontFamily = FontFamily.Monospace,
                fontSize = if (isTv) 16.sp else 12.sp,
                color = if (entry.level >= tv.anstelle.core.common.LogLevel.WARN) theme.secondary else theme.textMuted,
                // Focusable rows let the D-pad scroll the list on TV.
                modifier = when {
                    !isTv -> Modifier
                    index == 0 -> Modifier.focusRequester(firstFocus).focusable()
                    else -> Modifier.focusable()
                },
            )
        }
    }
}
