package tv.anstelle.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AnstelleMark
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.OsdPanel
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.webserver.pairing.PairingManager
import tv.anstelle.webserver.pairing.PairingRequest
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairing: PairingManager,
) : ViewModel() {
    val request: StateFlow<PairingRequest?> = pairing.activeRequest

    fun dismiss() = pairing.cancel()
}

/** Shows the 6-digit PIN on the TV while a browser is pairing. */
@Composable
fun PairingOverlay(viewModel: PairingViewModel = hiltViewModel()) {
    val request by viewModel.request.collectAsStateWithLifecycle()
    val current = request ?: return
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var secondsLeft by remember(current.id) { mutableLongStateOf(secondsUntil(current.expiresAtUtcMs)) }
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(current.id) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = secondsUntil(current.expiresAtUtcMs)
        }
        viewModel.dismiss()
    }

    Dialog(onDismissRequest = viewModel::dismiss) {
        OsdPanel(Modifier.widthIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnstelleMark(Modifier.size(28.dp))
                androidx.compose.material3.Text(
                    "Pair a browser",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = theme.text,
                )
            }
            androidx.compose.material3.Text(
                "Enter this PIN in ${current.clientName}.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = theme.textMuted,
            )
            // Six digits in two groups of three, each on its own block, like the channel number entry.
            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                current.pin.forEachIndexed { i, digit ->
                    if (i == 3) Box(Modifier.width(14.dp))
                    NumberBlock(
                        digit.toString(),
                        style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                        modifier = Modifier.width(if (isTv) 60.dp else 44.dp).height(if (isTv) 76.dp else 60.dp),
                    )
                }
            }
            SegmentLadder(secondsLeft / PIN_LIFETIME_S.toFloat(), segments = 30)
            androidx.compose.material3.Text(
                "Expires in ${secondsLeft} s",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = theme.textMuted,
            )
            OsdButton("Cancel", onClick = viewModel::dismiss, modifier = Modifier.focusRequester(buttonFocus))
            if (isTv) LaunchedEffect(current.id) { runCatching { buttonFocus.requestFocus() } }
        }
    }
}

private fun secondsUntil(utcMs: Long): Long = ((utcMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

/** A PIN lives 2 minutes (see `PairingManager`); the ladder drains over that span. */
private const val PIN_LIFETIME_S = 120
