package tv.anstelle.feature.settings.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.GroupRules
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.EditSheet
import tv.anstelle.core.theme.components.LocalSettingsSheets

/** Where a new channel goes: one of the groups someone made, its Live TV tab and its numbers. */
@Immutable
data class Placement(
    /** Every group, the library's too: names and starts are checked against them all. */
    val groups: List<ChannelGroup> = emptyList(),
    val nextStart: Int = 0,
    val groupId: Long? = null,
) {
    /** What still has to be picked, or null once it is. */
    val problem: String?
        get() = if (groups.none { it.id == groupId && !it.fromLibrary }) "Pick a group." else null
}

/**
 * Holds a new channel's group while it is made, starting with the one the last channel went
 * into. A group made here is picked at once.
 */
class PlacementPicker(
    private val repository: ChannelRepository,
    private val scope: CoroutineScope,
    private val onProblem: (String) -> Unit,
) {
    private val picked = MutableStateFlow<Long?>(null)

    val state: StateFlow<Placement> =
        combine(repository.observeGroups(), repository.observeNextGroupStart(), picked) { groups, nextStart, groupId ->
            Placement(groups, nextStart, groupId)
        }.stateIn(scope, SharingStarted.Eagerly, Placement())

    init {
        scope.launch {
            val last = repository.lastGroup()
            // A pick made before the last one was read stays.
            picked.update { it ?: last }
        }
    }

    fun pickGroup(groupId: Long) {
        picked.value = groupId
    }

    fun newGroup(name: String, start: Int) {
        scope.launch {
            try {
                pickGroup(repository.createGroup(name, start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProblem(e.message ?: "That didn't work.")
            }
        }
    }
}

/** Marks New group among the choices. */
private const val NEW_GROUP = -1L

/**
 * A channel's group: its tab in Live TV, which also numbers it. Only the groups someone made are
 * offered; the last choice makes a new one, which [onCreate] gets as its name and start.
 */
@Composable
internal fun GroupChoiceRow(
    groups: List<ChannelGroup>,
    selected: Long?,
    nextStart: Int,
    onSelect: (Long) -> Unit,
    onCreate: (name: String, start: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = groups.filterNot { it.fromLibrary }
    val newGroup = rememberNewGroup(groups, nextStart, onCreate)
    ChoiceRow<Long?>(
        title = "Group",
        value = selected?.takeIf { id -> own.any { it.id == id } },
        choices = own.map { it.id } + NEW_GROUP,
        label = { id -> if (id == NEW_GROUP) "New group" else own.firstOrNull { it.id == id }?.name ?: "Pick one" },
        detail = { id -> own.firstOrNull { it.id == id }?.let { "Numbers from ${it.numberStart}" } },
        about = "Its tab in Live TV. Channels in a group are numbered A to Z from its start.",
        onSelect = { id ->
            if (id == NEW_GROUP) newGroup() else if (id != null) onSelect(id)
        },
        icon = Icons.Filled.Folder,
        modifier = modifier,
    )
}

/**
 * New group: asks for a name, then where its numbers start ([nextStart] unless changed), and
 * hands both to [onCreate]. Returns what opens it.
 */
@Composable
internal fun rememberNewGroup(groups: List<ChannelGroup>, nextStart: Int, onCreate: (name: String, start: Int) -> Unit): () -> Unit {
    val sheets = LocalSettingsSheets.current
    val currentGroups by rememberUpdatedState(groups)
    val currentStart by rememberUpdatedState(nextStart)
    val create by rememberUpdatedState(onCreate)
    var named by remember { mutableStateOf<String?>(null) }
    // The start is asked once the name's sheet has closed.
    LaunchedEffect(named) {
        val name = named ?: return@LaunchedEffect
        named = null
        sheets.show(startSheet(name, currentStart) { start -> GroupRules.startProblem(start, currentGroups) ?: null.also { create(name, start) } })
    }
    return {
        sheets.show(
            EditSheet("New group", null, "Name", "", KeyboardType.Text, { it.take(40) }) { name ->
                GroupRules.nameProblem(name, currentGroups) ?: null.also { named = name.trim() }
            },
        )
    }
}

/** Asks where [name]'s numbers start; [onSave] checks the start and saves it, returning a problem or null. */
internal fun startSheet(name: String, start: Int, onSave: (Int) -> String?) = EditSheet(
    title = "$name start",
    about = "Its channels are numbered from here.",
    label = "Start",
    value = start.toString(),
    keyboardType = KeyboardType.Number,
    filter = { text -> text.filter(Char::isDigit).take(4) },
) { text ->
    val typed = text.toIntOrNull()
    if (typed == null) "A start is a number from ${GroupRules.MIN_START} to ${GroupRules.MAX_START}." else onSave(typed)
}
