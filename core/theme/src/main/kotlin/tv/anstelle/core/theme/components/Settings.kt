package tv.anstelle.core.theme.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.DangerColor
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.OnDangerColor
import tv.anstelle.core.theme.PanelShape

/*
 * Settings speak the device's own settings: on TV, Google TV's guided panel (what the page is on
 * the ground at the left, the list on a panel at the right); on phones, Android's settings list
 * under a large collapsing title. Every control says what it does by what sits at its end:
 *
 * - a chevron opens a page ([LinkRow]);
 * - a switch turns something on or off ([SwitchRow]);
 * - a drop-down mark under a value opens a radio list of choices ([ChoiceRow]);
 * - a pencil opens the value for typing ([TextRow]);
 * - the overflow mark opens an item's menu ([MenuRow], or a separate key on [OptionRow]);
 * - a radio or a checkbox at the start selects ([OptionRow], [CheckRow]);
 * - nothing at all: a fact, which nothing presses ([InfoRow]).
 *
 * Rows are things; verbs are buttons ([SettingsButtons]), and a destructive verb is a
 * [DangerButton] at the page's foot that asks first.
 */

/** Settings use the guided panel on TV and on touch screens at least this wide. */
private const val GUIDED_MIN_WIDTH_DP = 840

/** True where settings lay out as the guided panel (TV, wide tablets); false on phones. */
val settingsGuided: Boolean
    @Composable get() = LocalIsTv.current ||
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= GUIDED_MIN_WIDTH_DP.dp

/** Side padding for a field or anything else laid straight into a settings list, so it lines up with the rows' edges. */
val settingsEdge: Dp
    @Composable get() = if (settingsGuided) 0.dp else 16.dp

/**
 * Content sits on the surface step (the settings panel, a dialog), so whatever fills at rest
 * (a secondary button) steps up once more to read apart from it. A phone's settings list
 * provides it too, so Settings' buttons wear one fill on TV and phone alike (user decision).
 */
val LocalOnPanel = staticCompositionLocalOf { false }

/** A row's padding at its sides: the list's edge on phones, inside the panel on TV, the dialog's edge in a dialog. */
private val LocalRowInset = staticCompositionLocalOf { 16.dp }

/** One entry of an item's menu; [selected] marks the one in use with a check, [danger] a destructive one. */
@Immutable
class MenuAction(
    val title: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** The row being drawn is destructive: its icon carries the danger red, and focus fills it red. */
private val LocalDanger = staticCompositionLocalOf { false }

/** Text and icons on a focused row: ink on the accent, white on the danger red. */
private val focusInk: Color
    @Composable get() = if (LocalDanger.current) OnDangerColor else LocalAnstelleTheme.current.onAccent

/** A page or dialog a row opens over its settings page: see [SettingsSheets]. */
@Immutable
sealed interface SettingsSheet {
    val title: String
    val about: String?
}

/** One of several, as a radio list; [switch] adds an on/off setting about the same thing under it. */
@Immutable
class ChoiceSheet(
    override val title: String,
    override val about: String?,
    val options: List<Pair<String, String?>>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    val switch: SheetSwitch? = null,
) : SettingsSheet

/** An on/off setting under a [ChoiceSheet]'s choices, headed by [heading]. It flips in place: the sheet stays open. */
@Immutable
class SheetSwitch(
    val heading: String,
    val title: String,
    val detail: String?,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/** An item's menu. */
@Immutable
class MenuSheet(override val title: String, override val about: String?, val actions: List<MenuAction>) : SettingsSheet

/** A destructive verb, asked once more. */
@Immutable
class ConfirmSheet(
    override val title: String,
    override val about: String?,
    val confirm: String,
    val icon: ImageVector,
    val onConfirm: () -> Unit,
) : SettingsSheet

/** A value typed in; [onSave] returns a problem to show, or null once it is saved. */
@Immutable
class EditSheet(
    override val title: String,
    override val about: String?,
    val label: String,
    val value: String,
    val keyboardType: KeyboardType,
    val filter: (String) -> String,
    val onSave: (String) -> String?,
) : SettingsSheet

/** One field of a [FormSheet]. */
@Immutable
class FormField(
    val label: String,
    val value: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val filter: (String) -> String = { it.take(64) },
)

/** Several values typed in together; [onSave] gets them trimmed, in order, and returns a problem to show, or null once saved. */
@Immutable
class FormSheet(
    override val title: String,
    override val about: String?,
    val fields: List<FormField>,
    val onSave: (List<String>) -> String?,
) : SettingsSheet

/**
 * What a settings page has open over it. On TV a sheet takes the page over like Google TV's next
 * panel, and Back returns; on phones it is Android's dialog. Closing returns focus to the row or
 * button that opened it.
 */
@Stable
class SettingsSheets internal constructor() {
    internal var open by mutableStateOf<SettingsSheet?>(null)
        private set
    internal var returnTo: FocusRequester? = null

    /** Opens [sheet]; [from] takes focus back when it closes (a sheet opened from another keeps the first one's). */
    fun show(sheet: SettingsSheet, from: FocusRequester? = null) {
        if (from != null) returnTo = from
        open = sheet
    }

    internal fun close() {
        open = null
    }
}

/** The sheets of the settings page being composed. */
val LocalSettingsSheets = staticCompositionLocalOf<SettingsSheets> { error("A settings row outside a SettingsPage") }

/** A page's sheets, for a page that opens one itself: pass them to its [SettingsPage]. */
@Composable
fun rememberSettingsSheets(): SettingsSheets = remember { SettingsSheets() }

/**
 * The page every settings screen uses. [trail] names the pages above it (a breadcrumb on TV;
 * on phones a back arrow when there is one). [about] says what the page does: under the title
 * on TV, as the footer on phones, the way Android closes a page with its note. [aside] shows
 * what the page is about (a preview, a readout): under the title on TV, at the list's top on
 * phones. [sheets] lets the page open a sheet of its own, not from a row (the next step of
 * something under way).
 */
@Composable
fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    trail: List<String> = emptyList(),
    about: String? = null,
    showBack: Boolean = trail.isNotEmpty(),
    listState: LazyListState = rememberLazyListState(),
    aside: (@Composable ColumnScope.() -> Unit)? = null,
    sheets: SettingsSheets = rememberSettingsSheets(),
    content: LazyListScope.() -> Unit,
) {
    val guided = settingsGuided
    CompositionLocalProvider(LocalSettingsSheets provides sheets) {
        Box(modifier.fillMaxSize()) {
            if (guided) {
                GuidedPage(title, trail, about, showBack, listState, aside, content)
            } else {
                PhonePage(title, about, showBack, listState, aside, content)
            }
            val sheet = sheets.open
            if (sheet != null) {
                if (guided) SheetPage(sheet, trail + title, onClose = sheets::close) else SheetDialog(sheet, onClose = sheets::close)
            }
            // Closed: focus goes back to whatever opened the sheet.
            LaunchedEffect(sheet) {
                if (sheet == null) {
                    sheets.returnTo?.requestWhenReady(frames = 10)
                    sheets.returnTo = null
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhonePage(
    title: String,
    about: String?,
    showBack: Boolean,
    listState: LazyListState,
    aside: (@Composable ColumnScope.() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = theme.background,
        scrolledContainerColor = theme.background,
        navigationIconContentColor = theme.text,
        titleContentColor = theme.text,
        actionIconContentColor = theme.text,
    )
    // Only the collapsing bar takes part in the list's scroll; with no bar to collapse it would swallow it.
    val scrolling = if (showBack) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier
    Column(Modifier.fillMaxSize().background(theme.background).then(scrolling)) {
        if (showBack) {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { back?.onBackPressed() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = colors,
                // The shell already keeps the screen clear of the system bars.
                windowInsets = WindowInsets(0),
                // The large title sits straight under the back arrow, not a row's height below it.
                expandedHeight = PHONE_TITLE_EXPANDED,
                scrollBehavior = behavior,
            )
        } else {
            // With nothing to go back to there is no arrow row to sit under: the large title is the bar.
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = colors,
                windowInsets = WindowInsets(0),
            )
        }
        // Buttons here take the TV panel's fill, so a settings page's buttons match across devices.
        CompositionLocalProvider(LocalOnPanel provides true) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (aside != null) {
                    item(key = "aside") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = aside)
                    }
                }
                content()
                if (about != null) item(key = "about") { AboutFooter(about) }
            }
        }
    }
}

/** Android's closing note: an info mark and the page's explanation, quiet. */
@Composable
private fun AboutFooter(text: String) {
    val theme = LocalAnstelleTheme.current
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = theme.textMuted, modifier = Modifier.widthIn(max = 560.dp))
    }
}

@Composable
private fun GuidedPage(
    title: String,
    trail: List<String>,
    about: String?,
    showBack: Boolean,
    listState: LazyListState,
    aside: (@Composable ColumnScope.() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(Modifier.fillMaxSize().background(theme.background)) {
        Guidance(
            trail = trail,
            title = title,
            about = about,
            // The remote has Back; a wide touch screen gets the arrow.
            onBack = if (!isTv && showBack) ({ back?.onBackPressed() }) else null,
            aside = aside,
            modifier = Modifier.weight(GUIDANCE_WEIGHT).fillMaxHeight(),
        )
        Panel(Modifier.weight(PANEL_WEIGHT).fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (isTv) 40.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/**
 * The guided page's left side, on the ground: the breadcrumb, the title, what the page does and
 * what it is about. Nothing in it takes focus.
 */
@Composable
private fun Guidance(
    trail: List<String>,
    title: String,
    about: String?,
    onBack: (() -> Unit)?,
    aside: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = if (isTv) 40.dp else 24.dp, end = if (isTv) 32.dp else 24.dp, top = if (isTv) 40.dp else 24.dp, bottom = 32.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = theme.text)
            }
        }
        if (trail.isNotEmpty()) Breadcrumb(trail, Modifier.padding(bottom = 6.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, color = theme.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // What the page does sits behind the info mark, as a phone's settings close a page with it.
        if (about != null) {
            Row(Modifier.padding(top = 16.dp).widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "About", tint = theme.textMuted, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Text(about, style = MaterialTheme.typography.bodyMedium, color = theme.textMuted)
            }
        }
        if (aside != null) {
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = aside)
        }
    }
}

/** The pages above this one, joined by drawn chevrons; the last page nearest the title. */
@Composable
private fun Breadcrumb(trail: List<String>, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        trail.forEachIndexed { i, page ->
            if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(18.dp))
            Text(page, style = MaterialTheme.typography.bodyMedium, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The guided page's right side: the surface step, edge to edge in height. */
@Composable
private fun Panel(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(LocalAnstelleTheme.current.surface)) {
        CompositionLocalProvider(LocalOnPanel provides true) { content() }
    }
}

/**
 * A sheet on TV and wide screens: the page's next panel, Google TV's way. It covers the page,
 * keeps focus inside it until it closes, and Back closes it.
 */
@Composable
private fun SheetPage(sheet: SettingsSheet, trail: List<String>, onClose: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    BackHandler(onBack = onClose)
    // Its rows go with it: the page keeps the row the sheet was opened from as its place.
    CompositionLocalProvider(LocalPageFocus provides null) {
        Row(
            Modifier
                .fillMaxSize()
                .background(theme.background)
                // Taps never reach the page under it.
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Guidance(
                trail = trail,
                title = sheet.title,
                about = sheet.about,
                onBack = if (isTv) null else onClose,
                aside = null,
                modifier = Modifier.weight(GUIDANCE_WEIGHT).fillMaxHeight(),
            )
            Panel(Modifier.weight(PANEL_WEIGHT).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = if (isTv) 40.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SheetBody(sheet, onClose)
                }
            }
        }
    }
}

/** A sheet on phones: Android's own dialog, a list of rows or a field over its buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDialog(sheet: SettingsSheet, onClose: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    BasicAlertDialog(onDismissRequest = onClose) {
        Surface(shape = PanelShape, color = theme.surface, contentColor = theme.text, tonalElevation = 0.dp) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(sheet.title, style = MaterialTheme.typography.headlineSmall, color = theme.text, modifier = Modifier.padding(horizontal = 24.dp))
                sheet.about?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = theme.textMuted, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                CompositionLocalProvider(LocalOnPanel provides true, LocalRowInset provides 24.dp) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        SheetBody(sheet, onClose)
                    }
                }
                // Lists act on a tap; a field or a question takes its answer from the buttons.
                Row(
                    Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (sheet) {
                        is ConfirmSheet -> {
                            TextButton(onClick = onClose) { Text("Cancel", color = theme.text) }
                            OsdButton(sheet.confirm, onClick = { onClose(); sheet.onConfirm() }, icon = sheet.icon, danger = true)
                        }
                        is EditSheet, is FormSheet -> Unit // Its buttons sit with the fields.
                        // A switch saves as it flips, so there is nothing to cancel.
                        is ChoiceSheet -> TextButton(onClick = onClose) { Text(if (sheet.switch != null) "Done" else "Cancel", color = theme.text) }
                        else -> TextButton(onClick = onClose) { Text("Cancel", color = theme.text) }
                    }
                }
            }
        }
    }
}

/** What a sheet holds, the same on a TV panel and in a phone's dialog. */
@Composable
private fun SheetBody(sheet: SettingsSheet, onClose: () -> Unit) {
    val first = remember(sheet) { FocusRequester() }
    val guided = settingsGuided
    when (sheet) {
        is ChoiceSheet -> {
            sheet.options.forEachIndexed { i, (label, detail) ->
                OptionRow(
                    title = label,
                    summary = detail,
                    selected = i == sheet.selected,
                    onClick = {
                        onClose()
                        sheet.onSelect(i)
                    },
                    modifier = if (i == sheet.selected.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier,
                )
            }
            sheet.switch?.let { SheetSwitchRow(it) }
        }
        is MenuSheet -> sheet.actions.forEachIndexed { i, action ->
            SettingsRowFrame(
                title = action.title,
                summary = action.detail,
                onClick = {
                    // Closed first: the action may open a sheet of its own.
                    onClose()
                    action.onClick()
                },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                danger = action.danger,
                leading = action.icon?.let { icon -> { focused -> RowIcon(icon, focused) } },
                trailing = if (action.selected) ({ focused -> Check(focused) }) else null,
            )
        }
        is ConfirmSheet -> {
            // On TV the answer is the red button beside Cancel, focus starting on Cancel; a phone's dialog has its own.
            if (guided) {
                SettingsButtons {
                    OsdButton(
                        sheet.confirm,
                        onClick = {
                            onClose()
                            sheet.onConfirm()
                        },
                        icon = sheet.icon,
                        danger = true,
                    )
                    OsdButton("Cancel", onClick = onClose, modifier = Modifier.focusRequester(first))
                }
            }
        }
        is EditSheet -> EditBody(sheet, first, onClose)
        is FormSheet -> FormBody(sheet, first, onClose)
    }
    if (guided) LaunchedEffect(sheet) { first.requestWhenReady() }
}

/** A choice sheet's switch under its own heading; it keeps its state while the sheet stays open. */
@Composable
private fun SheetSwitchRow(switch: SheetSwitch) {
    var checked by remember(switch) { mutableStateOf(switch.checked) }
    SettingsSection(switch.heading)
    SwitchRow(
        title = switch.title,
        checked = checked,
        summary = switch.detail,
        onCheckedChange = {
            checked = it
            switch.onCheckedChange(it)
        },
    )
}

@Composable
private fun EditBody(sheet: EditSheet, first: FocusRequester, onClose: () -> Unit) {
    var text by remember(sheet) { mutableStateOf(sheet.value) }
    var problem by remember(sheet) { mutableStateOf<String?>(null) }
    val guided = settingsGuided
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() {
        problem = sheet.onSave(text.trim())
        if (problem == null) {
            keyboard?.hide()
            onClose()
        }
    }
    val inset = LocalRowInset.current
    Column(Modifier.padding(horizontal = if (guided) 0.dp else inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnstelleTextField(
            value = text,
            onValueChange = { text = sheet.filter(it) },
            label = sheet.label,
            keyboardType = sheet.keyboardType,
            isLast = true,
            onDone = ::save,
            modifier = Modifier.fillMaxWidth().focusRequester(first),
        )
        problem?.let { Notice(it, isError = true) }
        if (guided) {
            SettingsButtons {
                OsdButton("Save", onClick = ::save, icon = Icons.Filled.Check, primary = true)
                OsdButton("Cancel", onClick = onClose)
            }
        } else {
            val theme = LocalAnstelleTheme.current
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Cancel", color = theme.text) }
                TextButton(onClick = ::save) { Text("Save", color = theme.accent) }
            }
        }
    }
    // A phone's dialog opens with the field ready to type in.
    if (!guided) {
        LaunchedEffect(sheet) {
            first.requestWhenReady()
            keyboard?.show()
        }
    }
}

/** A [FormSheet]'s fields one under another, then Save and Cancel; the first field is ready to type in. */
@Composable
private fun FormBody(sheet: FormSheet, first: FocusRequester, onClose: () -> Unit) {
    val texts = remember(sheet) { mutableStateListOf(*sheet.fields.map { it.value }.toTypedArray()) }
    var problem by remember(sheet) { mutableStateOf<String?>(null) }
    val guided = settingsGuided
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() {
        problem = sheet.onSave(texts.map { it.trim() })
        if (problem == null) {
            keyboard?.hide()
            onClose()
        }
    }
    val inset = LocalRowInset.current
    Column(Modifier.padding(horizontal = if (guided) 0.dp else inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sheet.fields.forEachIndexed { i, field ->
            val last = i == sheet.fields.lastIndex
            AnstelleTextField(
                value = texts[i],
                onValueChange = { texts[i] = field.filter(it) },
                label = field.label,
                keyboardType = field.keyboardType,
                isLast = last,
                onDone = ::save,
                modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
            )
        }
        problem?.let { Notice(it, isError = true) }
        if (guided) {
            SettingsButtons {
                OsdButton("Save", onClick = ::save, icon = Icons.Filled.Check, primary = true)
                OsdButton("Cancel", onClick = onClose)
            }
        } else {
            val theme = LocalAnstelleTheme.current
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Cancel", color = theme.text) }
                TextButton(onClick = ::save) { Text("Save", color = theme.accent) }
            }
        }
    }
    if (!guided) {
        LaunchedEffect(sheet) {
            first.requestWhenReady()
            keyboard?.show()
        }
    }
}

/**
 * The frame every settings row shares: [leading] (an icon, a radio, a number), the title with
 * [summary] under it, then [trailing], which says what pressing the row does. Flat on its ground
 * at rest; focus fills it with the accent.
 */
@Composable
private fun SettingsRowFrame(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    danger: Boolean = false,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        color = Color.Transparent,
        focusColor = if (danger) DangerColor else theme.accent,
        focusContentColor = if (danger) OnDangerColor else theme.onAccent,
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        CompositionLocalProvider(LocalDanger provides danger) { RowLayout(title, summary, focused, leading, trailing) }
    }
}

@Composable
private fun RowLayout(
    title: String,
    summary: String?,
    focused: Boolean,
    leading: (@Composable (focused: Boolean) -> Unit)?,
    trailing: (@Composable (focused: Boolean) -> Unit)?,
) {
    val theme = LocalAnstelleTheme.current
    val guided = settingsGuided
    Row(
        Modifier
            .defaultMinSize(minHeight = if (summary == null) 56.dp else if (guided) 64.dp else 72.dp)
            .padding(horizontal = LocalRowInset.current, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke(focused)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = LocalContentColor.current,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) focusInk.copy(alpha = 0.76f) else theme.textMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(focused)
    }
}

/** A row's leading icon: quiet at rest (the danger red on a destructive row), the focus ink on focus. */
@Composable
private fun RowIcon(icon: ImageVector, focused: Boolean) {
    val theme = LocalAnstelleTheme.current
    val tint = when {
        focused -> focusInk
        LocalDanger.current -> DangerColor
        else -> theme.textMuted
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
private fun TrailingIcon(icon: ImageVector, focused: Boolean, description: String? = null) {
    val theme = LocalAnstelleTheme.current
    Icon(icon, contentDescription = description, tint = if (focused) focusInk else theme.textMuted, modifier = Modifier.size(24.dp))
}

@Composable
private fun Check(focused: Boolean) {
    val theme = LocalAnstelleTheme.current
    Icon(Icons.Filled.Check, contentDescription = "In use", tint = if (focused) focusInk else theme.accent, modifier = Modifier.size(20.dp))
}

private fun iconSlot(icon: ImageVector?, leading: (@Composable () -> Unit)?): (@Composable (Boolean) -> Unit)? = when {
    leading != null -> { _ -> leading() }
    icon != null -> { focused -> RowIcon(icon, focused) }
    else -> null
}

/** Opens a page: a chevron at its end, after the [value] when one is shown there. */
@Composable
fun LinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    SettingsRowFrame(
        title = title,
        onClick = onClick,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        leading = iconSlot(icon, leading),
        trailing = { focused ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.labelLarge, color = if (focused) theme.onAccent else theme.textMuted, maxLines = 1)
                }
                TrailingIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, focused)
            }
        },
    )
}

/** On or off: a switch at its end, and the whole row flips it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val theme = LocalAnstelleTheme.current
    SettingsRowFrame(
        title = title,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        leading = iconSlot(icon, null),
        trailing = { focused ->
            // The platform switch, themed; the row takes the input, so the switch itself takes none.
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = if (focused) {
                    SwitchDefaults.colors(
                        checkedThumbColor = theme.accent,
                        checkedTrackColor = theme.onAccent,
                        checkedBorderColor = theme.onAccent,
                        uncheckedThumbColor = theme.onAccent,
                        uncheckedTrackColor = theme.accent,
                        uncheckedBorderColor = theme.onAccent,
                    )
                } else {
                    SwitchDefaults.colors(
                        checkedThumbColor = theme.onAccent,
                        checkedTrackColor = theme.accent,
                        checkedBorderColor = theme.accent,
                        uncheckedThumbColor = theme.textMuted,
                        uncheckedTrackColor = theme.surfaceHigh,
                        uncheckedBorderColor = theme.textMuted,
                    )
                },
            )
        },
    )
}

/**
 * One of several: the choice in use under the title and a drop-down mark at the end. Pressing it
 * opens the choices as a radio list, each with [detail] under it; [about] heads the list, and
 * [switch] adds an on/off setting about the same thing under it (say it in [summary] too).
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    value: T,
    choices: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: ((T) -> String?)? = null,
    about: String? = null,
    enabled: Boolean = true,
    summary: String = label(value),
    switch: SheetSwitch? = null,
) {
    val sheets = LocalSettingsSheets.current
    val requester = remember { FocusRequester() }
    SettingsRowFrame(
        title = title,
        summary = summary,
        onClick = {
            sheets.show(
                ChoiceSheet(
                    title = title,
                    about = about,
                    options = choices.map { label(it) to detail?.invoke(it) },
                    selected = choices.indexOf(value),
                    onSelect = { onSelect(choices[it]) },
                    switch = switch,
                ),
                from = requester,
            )
        },
        modifier = modifier.focusRequester(requester),
        enabled = enabled,
        leading = iconSlot(icon, null),
        trailing = { focused -> TrailingIcon(Icons.Filled.ArrowDropDown, focused) },
    )
}

/**
 * A value typed in: the value under the title and a pencil at the end. Pressing it opens the
 * value in a field; [onSave] gets the trimmed text and returns a problem to show, or null.
 */
@Composable
fun TextRow(
    title: String,
    value: String,
    onSave: (String) -> String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String = value,
    label: String = title,
    about: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it.take(64) },
) {
    val sheets = LocalSettingsSheets.current
    val requester = remember { FocusRequester() }
    SettingsRowFrame(
        title = title,
        summary = summary,
        onClick = { sheets.show(EditSheet(title, about, label, value, keyboardType, filter, onSave), from = requester) },
        modifier = modifier.focusRequester(requester),
        leading = iconSlot(icon, null),
        trailing = { focused -> TrailingIcon(Icons.Filled.Edit, focused) },
    )
}

/** Like [TextRow], for several values edited together in one sheet: [fields] in order, [onSave] gets them trimmed. */
@Composable
fun FormRow(
    title: String,
    fields: List<FormField>,
    onSave: (List<String>) -> String?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    sheetTitle: String = title,
    about: String? = null,
) {
    val sheets = LocalSettingsSheets.current
    val requester = remember { FocusRequester() }
    SettingsRowFrame(
        title = title,
        summary = summary,
        onClick = { sheets.show(FormSheet(sheetTitle, about, fields, onSave), from = requester) },
        modifier = modifier.focusRequester(requester),
        leading = iconSlot(icon, null),
        trailing = { focused -> TrailingIcon(Icons.Filled.Edit, focused) },
    )
}

/**
 * An item with a menu of its own (move, rename, remove): the overflow mark at its end, and
 * pressing the row opens the menu, below the row on phones, as the next panel on TV.
 */
@Composable
fun MenuRow(
    title: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    about: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val sheets = LocalSettingsSheets.current
    val guided = settingsGuided
    val requester = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        SettingsRowFrame(
            title = title,
            summary = summary,
            onClick = { if (guided) sheets.show(MenuSheet(title, about, actions), from = requester) else expanded = true },
            modifier = Modifier.focusRequester(requester),
            leading = iconSlot(icon, leading),
            trailing = { focused -> TrailingIcon(Icons.Filled.MoreVert, focused, description = "More") },
        )
        if (!guided) {
            Box(Modifier.align(Alignment.TopEnd)) { ItemMenu(expanded, actions, onDismiss = { expanded = false }) }
        }
    }
}

/** An item's menu on phones: Android's overflow menu, flat on the surface step. */
@Composable
private fun ItemMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = PanelShape,
        containerColor = theme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, theme.line),
    ) {
        for (action in actions) {
            DropdownMenuItem(
                text = { Text(action.title, style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                leadingIcon = action.icon?.let { { Icon(it, contentDescription = null) } },
                trailingIcon = if (action.selected) ({ Icon(Icons.Filled.Check, contentDescription = "In use", tint = theme.accent) }) else null,
                colors = MenuDefaults.itemColors(textColor = theme.text, leadingIconColor = if (action.danger) DangerColor else theme.textMuted),
            )
        }
    }
}

/**
 * One of a list where one is chosen: a radio at its start. [leading] follows the radio (a
 * theme's swatches). With [actions], the item has a menu too, as a key of its own at the row's
 * end (right of the row on the D-pad); holding the row, or Menu on the remote, opens it as well.
 */
@Composable
fun OptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    actions: List<MenuAction>? = null,
    menuTitle: String = title,
) {
    val theme = LocalAnstelleTheme.current
    val radio: @Composable (Boolean) -> Unit = { focused ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (focused) theme.onAccent else theme.accent,
                    unselectedColor = if (focused) theme.onAccent else theme.textMuted,
                ),
            )
            leading?.invoke()
        }
    }
    if (actions == null) {
        SettingsRowFrame(title = title, summary = summary, onClick = onClick, modifier = modifier, enabled = enabled, leading = radio)
        return
    }
    val sheets = LocalSettingsSheets.current
    val guided = settingsGuided
    val more = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val openMenu = { if (guided) sheets.show(MenuSheet(menuTitle, null, actions), from = more) else expanded = true }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsRowFrame(
            title = title,
            summary = summary,
            onClick = onClick,
            onLongClick = openMenu,
            enabled = enabled,
            leading = radio,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) openMenu().let { true } else false
            },
        )
        Box {
            FocusSurface(
                onClick = openMenu,
                color = Color.Transparent,
                modifier = Modifier.size(56.dp).focusRequester(more),
            ) { focused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TrailingIcon(Icons.Filled.MoreVert, focused, description = "More for $title")
                }
            }
            if (!guided) ItemMenu(expanded, actions, onDismiss = { expanded = false })
        }
    }
}

/** One of many picked together: a checkbox at its start. */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val theme = LocalAnstelleTheme.current
    SettingsRowFrame(
        title = title,
        summary = summary,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leading = { focused ->
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = if (focused) theme.onAccent else theme.accent,
                    checkmarkColor = if (focused) theme.accent else theme.onAccent,
                    uncheckedColor = if (focused) theme.onAccent else theme.textMuted,
                    disabledCheckedColor = theme.textMuted,
                ),
            )
        },
    )
}

/** A fact: nothing presses it, nothing sits at its end. [warn] marks a problem with an icon as well as the accent. */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    warn: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val theme = LocalAnstelleTheme.current
    CompositionLocalProvider(LocalContentColor provides theme.text) {
        Box(modifier.fillMaxWidth()) {
            RowLayout(
                title = title,
                summary = summary,
                focused = false,
                leading = when {
                    leading != null -> { _ -> leading() }
                    warn -> { _ -> Icon(Icons.Filled.ErrorOutline, contentDescription = "Needs attention", tint = theme.accent, modifier = Modifier.size(24.dp)) }
                    icon != null -> { _ -> RowIcon(icon, false) }
                    else -> null
                },
                trailing = null,
            )
        }
    }
}

/**
 * A destructive key at the end of a row (remove this item), the danger red on its icon and as its
 * focus fill. It acts at once: the item is one entry of a list, easy to add again.
 */
@Composable
fun RemoveKey(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        color = Color.Transparent,
        focusColor = DangerColor,
        focusContentColor = OnDangerColor,
        modifier = modifier.size(56.dp),
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Delete, contentDescription = description, tint = if (focused) OnDangerColor else DangerColor, modifier = Modifier.size(24.dp))
        }
    }
}

/** A group's heading, in the OSD's field voice, more room above it than under it. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier) {
    SectionLabel(title, modifier.padding(start = LocalRowInset.current, bottom = 4.dp), top = 24.dp)
}

/** [SettingsSection] as a list item. */
fun LazyListScope.section(title: String, key: String = "section-$title") = item(key = key) { SettingsSection(title) }

/** A line of status or help in a settings list, lined up with the rows' icons. */
@Composable
fun SettingsNotice(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Notice(
        text,
        modifier.padding(horizontal = LocalRowInset.current, vertical = 4.dp),
        isError = isError,
    )
}

/**
 * Finds things in a long list (channels, themes): a field with a search mark, lined up with the
 * rows. On TV, OK starts typing, so moving past it never opens the keyboard.
 */
@Composable
fun SettingsSearch(query: String, onQueryChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    AnstelleTextField(
        value = query,
        onValueChange = onQueryChange,
        label = label,
        leadingIcon = Icons.Filled.Search,
        isLast = true,
        modifier = modifier.fillMaxWidth().padding(horizontal = settingsEdge, vertical = 8.dp),
    )
}

/**
 * A settings page's verbs, lined up with the rows. Buttons share their line evenly, on TV and
 * phone alike (user decision): one alone spans the list, two split it half and half. When a
 * label wouldn't fit its share, the buttons stack instead, each across the list.
 */
@Composable
fun SettingsButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = settingsEdge, vertical = 8.dp),
    ) { buttons, constraints ->
        if (buttons.isEmpty() || !constraints.hasBoundedWidth) return@Layout layout(constraints.minWidth, 0) {}
        val width = constraints.maxWidth
        val gap = BUTTON_GAP.roundToPx()
        fun share(count: Int) = ((width - gap * (count - 1)) / count).coerceAtLeast(0)
        val natural = buttons.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        // All on one line when every label fits its share; otherwise fewer to a line, down to one each.
        val perLine = (buttons.size downTo 1).firstOrNull { n -> natural.all { it <= share(n) } } ?: 1
        val lines = buttons.indices.chunked(perLine).map { line ->
            val each = share(line.size)
            val height = line.maxOf { buttons[it].maxIntrinsicHeight(each) }
            line.mapIndexed { i, b ->
                // The last takes what rounding left, so the line ends flush with the rows.
                val w = if (i == line.lastIndex) (width - (each + gap) * (line.size - 1)).coerceAtLeast(0) else each
                buttons[b].measure(Constraints.fixed(w, height))
            }
        }
        val height = lines.sumOf { line -> line.maxOf { it.height } } + gap * (lines.size - 1)
        layout(width, height) {
            var y = 0
            for (line in lines) {
                var x = 0
                for (button in line) {
                    button.placeRelative(x, y)
                    x += button.width + gap
                }
                y += line.maxOf { it.height } + gap
            }
        }
    }
}

/** The page's destructive verbs, apart at its foot under no heading. */
fun LazyListScope.foot(content: @Composable () -> Unit) = item(key = "foot") {
    SettingsButtons(Modifier.padding(top = 24.dp), content = content)
}

/**
 * A destructive verb (remove, delete): a button that asks once more, naming what goes and what
 * stays, before it acts. [confirm] is the answer that acts.
 */
@Composable
fun DangerButton(
    text: String,
    question: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    explanation: String? = null,
    confirm: String = text,
    icon: ImageVector = Icons.Filled.Delete,
) {
    val sheets = LocalSettingsSheets.current
    val requester = remember { FocusRequester() }
    OsdButton(
        text = text,
        onClick = { sheets.show(ConfirmSheet(question, explanation, confirm, icon, onConfirm), from = requester) },
        icon = icon,
        danger = true,
        modifier = modifier.focusRequester(requester),
    )
}

/** Share of the guided page's width the guidance takes; the panel takes the rest. */
private const val GUIDANCE_WEIGHT = 0.42f
private const val PANEL_WEIGHT = 0.58f

/** A phone sub-page's bar while expanded: the back arrow's row, then the large title's. */
private val PHONE_TITLE_EXPANDED = 120.dp

/** Between buttons sharing a line, and between stacked lines of them. */
private val BUTTON_GAP = 8.dp
