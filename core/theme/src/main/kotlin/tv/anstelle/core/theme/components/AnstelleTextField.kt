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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

/**
 * A single-line text field for both TV and touch. Compose for TV has no text field, so
 * this is a themed Material 3 field. On TV the D-pad can pass over it: it stays read-only
 * while focused, and OK starts typing and opens the keyboard. A hardware keyboard needs no OK:
 * a character or Backspace typed on the focused field starts typing with it. Typing starts at
 * the end of the text. Up and down move focus out of the field (one line has nothing to move
 * through). The keyboard's action key moves to the next field, or runs [onDone] on the last one.
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
    leadingIcon: ImageVector? = null,
    onDone: () -> Unit = {},
) {
    val theme = LocalAnstelleTheme.current
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    // On TV, typing starts with OK (or a typed key) and ends when focus leaves.
    var typing by remember { mutableStateOf(!isTv) }
    // The text with its cursor; a value changed from outside puts the cursor at its end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))

    fun startTyping(text: String = shown.text) {
        typing = true
        field = TextFieldValue(text, TextRange(text.length))
        if (text != value) onValueChange(text)
    }

    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        modifier = modifier
            .onFocusChanged { if (isTv && !it.isFocused) typing = false }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val startsTyping = isTv && !typing && enabled
                when {
                    event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    event.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter ->
                        startsTyping.also { if (it) startTyping() }
                    // A hardware keyboard: the read-only field would drop the key, so it is typed here.
                    startsTyping && event.key == Key.Backspace -> true.also { startTyping(shown.text.dropLast(1)) }
                    startsTyping && event.nativeKeyEvent.isPrintingKey && event.utf16CodePoint != 0 ->
                        true.also { startTyping(buildString { append(shown.text).appendCodePoint(event.utf16CodePoint) }) }
                    else -> false
                }
            },
        readOnly = !typing,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = leadingIcon?.let { { androidx.compose.material3.Icon(it, contentDescription = null) } },
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
            focusedLeadingIconColor = theme.accent,
            unfocusedLeadingIconColor = theme.textMuted,
            cursorColor = theme.accent,
            selectionColors = TextSelectionColors(handleColor = theme.accent, backgroundColor = theme.accentContainer),
            focusedContainerColor = theme.surface,
            unfocusedContainerColor = theme.surface,
            disabledContainerColor = theme.background,
        ),
    )
}
