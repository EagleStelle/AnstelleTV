package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.osdTabLabel

/** What the search slab says while nothing is typed. */
private const val PLACEHOLDER = "Search"

/** The slab's foot, the open tab's underline in the same place. */
private val RULE = 2.dp

/**
 * On Demand's search: a coal slab of its own at the end of the tab row, apart from the tabs
 * (user decision: search is not one of them) and of a kind with the keys on Search's page. It is
 * flat with no outline, a tab's exact height on the tabs' baseline, and its words are set
 * exactly as a tab's label ([osdTabLabel], the theme's display font and scale): the search mark
 * and *Search* muted at rest, like an unopened tab, the words in bone. Its foot is
 * the open tab's rule in the same place, rose while Search is open, so the row still says where
 * you are. On TV it is a stop on the D-pad like any key: focus fills it rose with ink, OK (or any
 * typed key) starts typing and opens the keyboard, up or down leave it; while typing it is coal
 * again with the rose foot and caret. Taking words opens Search's page ([onOpen]), where the
 * caller widens it; closed, it keeps the words last searched for. [startTyping] puts the caret in
 * it at once, as when a phone's row hands it the whole width; [onBack] adds a back key there.
 */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    requester: FocusRequester,
    /** Search's page is showing. */
    open: Boolean,
    /** Shows Search's page, once the slab starts taking words. */
    onOpen: () -> Unit,
    startTyping: Boolean,
    onStarted: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // On TV, typing starts with OK and ends when focus leaves; touch types whenever it has focus.
    var typing by remember { mutableStateOf(!isTv) }
    // The words with their caret; words changed from outside (cleared) put the caret at their end.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val shown = if (field.text == query) field else TextFieldValue(query, TextRange(query.length))

    fun type(text: String = shown.text) {
        typing = true
        field = TextFieldValue(text, TextRange(text.length))
        if (text != query) onQuery(text)
        if (!open) onOpen()
        keyboard?.show()
    }

    LaunchedEffect(startTyping) {
        if (!startTyping) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        withFrameNanos { }
        type()
        onStarted()
    }
    // Closed from outside (a tab tapped, which takes no focus on touch): the caret and keyboard go with it.
    LaunchedEffect(open) {
        if (!open && !isTv && focused) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    // A tab's own label and measures, so the words follow the theme exactly as the tabs do: the compact tab on TV, the full one on touch.
    val words = osdTabLabel(compact = isTv)
    val padH = if (isTv) 14.dp else 12.dp
    val label = with(LocalDensity.current) { words.lineHeight.toDp() } + (if (isTv) 6.dp else 10.dp) * 2
    // Focus is a fill, as on any key; typing, the slab is coal again with a rose caret.
    val lit = isTv && focused && !typing
    val ink = if (lit) theme.onAccent else theme.text
    val quiet = if (lit) theme.onAccent.copy(alpha = 0.76f) else theme.textMuted
    val clearable = open && query.isNotEmpty()

    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) SlabKey(Icons.AutoMirrored.Filled.ArrowBack, "Leave search", label, ink = theme.text, onClick = onBack)
        Column(
            Modifier
                .weight(1f)
                .clip(ControlShape)
                .background(if (lit) theme.accent else theme.surface),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(label)
                    .padding(start = padH, end = if (clearable) 0.dp else padH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (shown.text.isEmpty()) quiet else ink,
                    modifier = Modifier.size(if (isTv) 18.dp else 20.dp),
                )
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = theme.accent, backgroundColor = theme.accentContainer)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (shown.text.isEmpty()) {
                            androidx.compose.material3.Text(
                                PLACEHOLDER,
                                style = words,
                                color = quiet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = shown,
                            onValueChange = {
                                field = it
                                if (it.text != query) onQuery(it.text)
                            },
                            readOnly = !typing,
                            singleLine = true,
                            textStyle = words.copy(color = ink),
                            cursorBrush = SolidColor(theme.accent),
                            interactionSource = interaction,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                if (isTv) {
                                    typing = false
                                    focusManager.moveFocus(FocusDirection.Down)
                                } else {
                                    focusManager.clearFocus()
                                }
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(requester)
                                .semantics { contentDescription = PLACEHOLDER }
                                .onFocusChanged {
                                    if (isTv && !it.isFocused) typing = false
                                    // A tap on touch is the start of typing.
                                    if (!isTv && it.isFocused && !open) onOpen()
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || !isTv) return@onPreviewKeyEvent false
                                    val idle = !typing
                                    when {
                                        // One line has nothing to move through: up and down leave it.
                                        event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                        event.key == Key.DirectionUp -> true
                                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> idle.also { if (it) type() }
                                        // A hardware keyboard: the field is read-only until typing, so the key is typed here.
                                        idle && event.key == Key.Backspace -> true.also { type(shown.text.dropLast(1)) }
                                        idle && event.nativeKeyEvent.isPrintingKey && event.utf16CodePoint != 0 ->
                                            true.also { type(buildString { append(shown.text).appendCodePoint(event.utf16CodePoint) }) }
                                        else -> false
                                    }
                                },
                        )
                    }
                }
                if (clearable) {
                    SlabKey(Icons.Filled.Close, "Clear search", label, ink = quiet, small = true) {
                        onQuery("")
                        runCatching { requester.requestFocus() }
                        if (!isTv) keyboard?.show()
                    }
                }
            }
            // The foot: rose while Search is open, as an open tab's rule; focus fills the slab instead.
            Box(Modifier.fillMaxWidth().height(RULE).background(if (open && !lit) theme.accent else Color.Transparent))
        }
    }
}

/**
 * A key in the slab (clear) or before it (back): its mark alone, as tall as the slab's line,
 * filled rose on focus. [small] sets the clear mark at the search mark's size.
 */
@Composable
private fun SlabKey(icon: ImageVector, description: String, height: Dp, ink: Color, small: Boolean = false, onClick: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    FocusSurface(onClick = onClick, color = Color.Transparent, modifier = Modifier.height(height)) { focused ->
        androidx.compose.material3.Icon(
            icon,
            contentDescription = description,
            tint = if (focused) theme.onAccent else ink,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = if (isTv) 10.dp else 12.dp)
                .size(if (small) (if (isTv) 18.dp else 20.dp) else (if (isTv) 20.dp else 24.dp)),
        )
    }
}
