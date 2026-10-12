package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * A PIN pad: [length] slots like the channel number entry's, a keypad, and the remote's own
 * digit keys. A typed digit fills its slot with a solid accent square, never the digit, and
 * the last one hands the PIN to [onPin]. Changing [attempt] clears the slots, e.g. after a
 * wrong PIN; [message] says why. Back or Cancel calls [onCancel]. While not [enabled] (a
 * check is running, or too many wrong PINs) keys do nothing.
 */
@Composable
fun PinEntry(
    title: String,
    onPin: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    message: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    attempt: Int = 0,
    length: Int = 4,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    var digits by remember(attempt) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { if (isTv) runCatching { first.requestFocus() } }

    fun type(digit: Char) {
        if (!enabled || digits.length >= length) return
        digits += digit
        if (digits.length == length) onPin(digits)
    }

    fun erase() {
        if (enabled) digits = digits.dropLast(1)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(theme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = DigitKeys[event.key]
                when {
                    digit != null -> type(digit).let { true }
                    event.key == Key.Backspace || event.key == Key.Delete -> erase().let { true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = pagePadding, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 14.dp),
        ) {
            androidx.compose.material3.Text(
                title,
                style = if (isTv) androidx.compose.material3.MaterialTheme.typography.headlineLarge else androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = theme.text,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                androidx.compose.material3.Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = theme.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            // The slots, filled in turn: solid when typed, hollow while empty. Six split into two
            // threes, the way the pairing PIN shows on the other screen.
            Row(
                Modifier.padding(vertical = 8.dp).semantics { contentDescription = "${digits.length} of $length digits entered" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val slot = Modifier.width(if (isTv) 52.dp else 44.dp).height(if (isTv) 64.dp else 56.dp)
                for (i in 0 until length) {
                    if (length == 6 && i == 3) Box(Modifier.width(6.dp))
                    if (i < digits.length) {
                        Box(slot.background(theme.accent, ControlShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).background(theme.onAccent))
                        }
                    } else {
                        Box(slot.border(1.dp, theme.line, ControlShape))
                    }
                }
            }
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (message != null) Notice(message, isError = isError)
            }
            val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (digit in row) {
                            PadKey(label = digit.toString(), onClick = { type(digit) }, modifier = if (digit == '1') Modifier.focusRequester(first) else Modifier)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PadKey(icon = Icons.Filled.Close, description = "Cancel", onClick = onCancel)
                    PadKey(label = "0", onClick = { type('0') })
                    PadKey(icon = Icons.AutoMirrored.Filled.Backspace, description = "Delete", onClick = ::erase)
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
) {
    val isTv = LocalIsTv.current
    FocusSurface(onClick = onClick, modifier = modifier.width(if (isTv) 88.dp else 80.dp).height(if (isTv) 56.dp else 52.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (label != null) {
                androidx.compose.material3.Text(label, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            } else if (icon != null) {
                androidx.compose.material3.Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private val DigitKeys = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/** "Wrong PIN. 3 more tries, then a short wait." */
fun wrongPinMessage(triesLeft: Int) = "Wrong PIN. $triesLeft more ${if (triesLeft == 1) "try" else "tries"}, then a short wait."

/** "Too many wrong PINs. Try again in 30 seconds." */
fun lockedOutMessage(waitMs: Long) = "Too many wrong PINs. Try again in ${(waitMs + 999) / 1000} seconds."
