package tv.anstelle.core.theme.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import tv.anstelle.core.theme.LocalAnstelleTheme

/**
 * A single-line text field for both TV and touch. Compose for TV has no text field, so
 * this is a themed Material 3 field; on TV the D-pad focuses it and OK opens the keyboard.
 * Up and down move focus out of the field (one line has nothing to move through). The
 * keyboard's action key moves to the next field, or runs [onDone] on the last one.
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                else -> false
            }
        },
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
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { onDone() },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.text,
            unfocusedTextColor = theme.text,
            focusedBorderColor = theme.focus,
            unfocusedBorderColor = theme.textMuted,
            focusedLabelColor = theme.focus,
            unfocusedLabelColor = theme.textMuted,
            cursorColor = theme.focus,
            focusedContainerColor = theme.surface,
            unfocusedContainerColor = theme.surface,
        ),
    )
}
