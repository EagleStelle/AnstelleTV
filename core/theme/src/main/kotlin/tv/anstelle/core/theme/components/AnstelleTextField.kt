package tv.anstelle.core.theme.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * A single-line text field for both TV and touch. Compose for TV has no text field, so
 * this is a themed Material 3 field. On TV the D-pad can pass over it: it stays read-only
 * while focused, and only OK starts typing and opens the keyboard. Up and down move focus out
 * of the field (one line has nothing to move through). The keyboard's action key moves to the
 * next field, or runs [onDone] on the last one.
 */
@Composable
fun AnstelleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    val theme = LocalAnstelleTheme.current
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    // On TV, typing starts with OK and ends when focus leaves.
    var typing by remember { mutableStateOf(!isTv) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { if (isTv && !it.isFocused) typing = false }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (isTv && !typing) {
                        typing = true
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            },
        readOnly = !typing,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = theme.textMuted) } },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
            imeAction = if (isLast) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                if (isTv) typing = false
                focusManager.moveFocus(FocusDirection.Down)
            },
            onDone = {
                if (isTv) typing = false
                onDone()
            },
        ),
        shape = ControlShape,
        textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.text,
            unfocusedTextColor = theme.text,
            disabledTextColor = theme.textMuted,
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.line,
            disabledBorderColor = theme.line,
            focusedLabelColor = theme.accent,
            unfocusedLabelColor = theme.textMuted,
            cursorColor = theme.accent,
            selectionColors = TextSelectionColors(handleColor = theme.accent, backgroundColor = theme.accentContainer),
            focusedContainerColor = theme.surface,
            unfocusedContainerColor = theme.surface,
            disabledContainerColor = theme.background,
        ),
    )
}
