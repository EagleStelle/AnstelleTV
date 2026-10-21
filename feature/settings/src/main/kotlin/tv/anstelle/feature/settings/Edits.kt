package tv.anstelle.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuSheet
import tv.anstelle.core.theme.components.OptionRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.PageFocusScope
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.SettingsSheets

/** What a page shows ([value]) beside what was last kept ([kept]). */
data class Draft<T>(val value: T, val kept: T) {
    val unsaved: Boolean get() = value != kept
}

/**
 * A settings page's edits, kept only by [save]: [draft] is null until [load]. [write] keeps a
 * value, on [appScope] so a save finishes even if the page closes.
 */
class Edits<T : Any>(private val appScope: CoroutineScope, private val write: suspend (T) -> Unit) {
    private val _draft = MutableStateFlow<Draft<T>?>(null)
    val draft: StateFlow<Draft<T>?> = _draft.asStateFlow()

    fun load(kept: T) {
        _draft.value = Draft(kept, kept)
    }

    /** Changes what the page shows; nothing is kept until [save]. */
    fun change(transform: (T) -> T) = _draft.update { it?.copy(value = transform(it.value)) }

    /** Drops the edits: the page shows what was last kept. */
    fun discard() = _draft.update { it?.copy(value = it.kept) }

    /** Keeps the edits, then runs [then]. A failed write leaves them unsaved and skips [then]. */
    fun save(then: suspend () -> Unit = {}) {
        appScope.launch {
            val draft = _draft.value ?: return@launch
            if (draft.unsaved && runCatching { write(draft.value) }.isFailure) return@launch
            _draft.update { it?.copy(kept = draft.value) }
            then()
        }
    }
}

/**
 * The pair across the top of every page and pane whose edits wait: [keep] (Save, Done) beside a
 * red Discard, sharing the line evenly, both on only while there are edits ([changed]).
 */
@Composable
fun KeepButtons(keep: String, icon: ImageVector, changed: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit) {
    OsdButton(keep, onClick = onKeep, icon = icon, primary = true, enabled = changed)
    OsdButton("Discard", onClick = onDiscard, icon = Icons.Filled.Delete, enabled = changed, danger = true)
}

/** A page's [KeepButtons]: Save keeps its edits, Discard drops them. */
@Composable
fun SaveButtons(unsaved: Boolean, onSave: () -> Unit, onDiscard: () -> Unit) = KeepButtons("Save", Icons.Filled.Save, unsaved, onSave, onDiscard)

/**
 * While there are edits, Back asks what becomes of them: Save keeps them, Discard drops them,
 * and either leaves ([onLeave]). So Save is one key away wherever the page is scrolled.
 */
@Composable
fun SaveOnBack(unsaved: Boolean, sheets: SettingsSheets, onSave: () -> Unit, onLeave: () -> Unit) {
    BackHandler(enabled = unsaved) {
        sheets.show(
            MenuSheet(
                "Save changes?",
                null,
                listOf(
                    MenuAction("Save", Icons.Filled.Save) {
                        onSave()
                        onLeave()
                    },
                    MenuAction("Discard", Icons.Filled.Delete, danger = true, onClick = onLeave),
                ),
            ),
        )
    }
}

/**
 * A page and the panes it opens: [pane], while one is open, takes the page's place. The page
 * keeps its scroll and focused row meanwhile, so closing the pane lands back on it.
 */
@Composable
fun PanedPage(pane: (@Composable () -> Unit)?, modifier: Modifier = Modifier, page: @Composable () -> Unit) {
    val panes = rememberSaveableStateHolder()
    Box(modifier) {
        if (pane != null) PageFocusScope { pane() } else panes.SaveableStateProvider("page") { PageFocusScope { page() } }
    }
}

/**
 * A pane: part of a page's edits on a page of its own, holding [working], a copy of [value].
 * Its [KeepButtons]: Done hands the copy back ([onApply]); Discard, or Back, drops it. Save on
 * the page keeps what panes hand back.
 */
@Composable
fun <T> PaneFrame(
    title: String,
    trail: List<String>,
    value: T,
    working: T,
    onApply: (T) -> Unit,
    onClose: () -> Unit,
    about: String? = null,
    aside: (@Composable ColumnScope.() -> Unit)? = null,
    pinAside: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    SettingsPage(
        title = title,
        trail = trail,
        about = about,
        listState = listState,
        aside = aside,
        pinAside = pinAside,
        actions = { KeepButtons("Done", Icons.Filled.Check, working != value, onKeep = { onApply(working) }, onDiscard = onClose) },
        content = content,
    )
}

/**
 * A [PaneFrame] that keeps its own copy of [value], one per [id]. [content] reads the copy and
 * changes it through `edit`; [aside] shows it.
 */
@Composable
fun <T> EditPane(
    title: String,
    id: String,
    value: T,
    trail: List<String>,
    onApply: (T) -> Unit,
    onClose: () -> Unit,
    about: String? = null,
    aside: (@Composable (T) -> Unit)? = null,
    pinAside: Boolean = false,
    content: LazyListScope.(working: T, edit: ((T) -> T) -> Unit) -> Unit,
) {
    var working by remember(id) { mutableStateOf(value) }
    PaneFrame(
        title = title,
        trail = trail,
        value = value,
        working = working,
        onApply = onApply,
        onClose = onClose,
        about = about,
        aside = if (aside == null) null else ({ aside(working) }),
        pinAside = pinAside,
    ) { content(working) { transform -> working = transform(working) } }
}

/**
 * One of [choices] as a pane's radio list, each with [detail] under it; a pick shows at once and
 * Done keeps it. [firstFocus] goes to the selected one, or to the first when none is.
 */
fun <T> LazyListScope.choiceRows(
    key: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    detail: ((T) -> String?)? = null,
    firstFocus: FocusRequester? = null,
) {
    val focusOn = selected.takeIf { it in choices } ?: choices.first()
    for (choice in choices) {
        item(key = "$key-$choice") {
            OptionRow(
                title = label(choice),
                summary = detail?.invoke(choice),
                selected = choice == selected,
                onClick = { onPick(choice) },
                modifier = if (firstFocus != null && choice == focusOn) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}
