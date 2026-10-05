package tv.anstelle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
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
        Column(
            modifier = Modifier
                .background(theme.surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Text(
                "Pair a browser",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = theme.text,
            )
            androidx.compose.material3.Text(
                "Enter this PIN in ${current.clientName}",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = theme.textMuted,
            )
            androidx.compose.material3.Text(
                current.pin.chunked(3).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (isTv) 64.sp else 48.sp,
                letterSpacing = 4.sp,
                color = theme.primary,
            )
            androidx.compose.material3.Text(
                "Expires in ${secondsLeft}s",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = theme.textMuted,
            )
            if (isTv) {
                androidx.tv.material3.Button(
                    onClick = viewModel::dismiss,
                    modifier = Modifier.focusRequester(buttonFocus),
                ) { androidx.tv.material3.Text("Cancel") }
                LaunchedEffect(current.id) { runCatching { buttonFocus.requestFocus() } }
            } else {
                androidx.compose.material3.TextButton(onClick = viewModel::dismiss) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        }
    }
}

private fun secondsUntil(utcMs: Long): Long = ((utcMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
