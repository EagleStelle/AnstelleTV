package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.GroupRules
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.theme.components.FormField
import tv.anstelle.core.theme.components.FormRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.ReorderRow
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.rememberReorderList
import tv.anstelle.core.theme.components.reorderItems
import javax.inject.Inject

@Immutable
data class GroupItem(val group: ChannelGroup, val channelCount: Int)

@Immutable
data class GroupsUiState(
    val groups: List<GroupItem> = emptyList(),
    val nextStart: Int = 0,
    val loaded: Boolean = false,
)

@HiltViewModel
class ChannelGroupsViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<GroupsUiState> =
        combine(repository.observeGroups(), repository.observeChannels(), repository.observeNextGroupStart()) { groups, channels, nextStart ->
            val counts = channels.groupingBy { it.channel.groupId }.eachCount()
            GroupsUiState(groups.map { GroupItem(it, counts[it.id] ?: 0) }, nextStart, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun create(name: String, start: Int) = act { repository.createGroup(name, start) }

    fun rename(groupId: Long, name: String) = act { repository.renameGroup(groupId, name) }

    fun setStart(groupId: Long, start: Int) = act { repository.setGroupStart(groupId, start) }

    fun move(groupId: Long, delta: Int) = act { repository.moveGroup(groupId, delta) }

    fun delete(groupId: Long) = act { repository.deleteGroup(groupId) }

    private fun act(action: suspend () -> Unit) {
        _message.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "That didn't work."
            }
        }
    }
}

/**
 * The groups in Live TV's order, each numbering its channels from its start: New group, then
 * the groups, each moved by its handle while its menu renames, renumbers or deletes it. The
 * library's groups only move and renumber, and a group someone made is deleted once it is empty.
 */
@Composable
fun ChannelGroupsScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelGroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)
    val groups = state.groups.map { it.group }
    val listState = rememberLazyListState()
    val order = rememberReorderList(state.groups, listState, key = { "group-${it.group.id}" }) { item, from, to ->
        viewModel.move(item.group.id, to - from)
    }

    SettingsPage(
        title = "Groups",
        trail = listOf("Settings", "Live TV"),
        about = "Each group is a tab in Live TV. Drag a group by its handle to reorder.",
        listState = listState,
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { SettingsNotice(it, isError = true) } }
        item(key = "new") {
            // Inside the page: New group opens its sheets.
            val newGroup = rememberNewGroup(groups, state.nextStart, viewModel::create)
            SettingsButtons {
                OsdButton(
                    "New group",
                    onClick = newGroup,
                    icon = Icons.Filled.CreateNewFolder,
                    modifier = if (state.groups.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        if (state.loaded && state.groups.isEmpty()) item(key = "empty") { SettingsNotice("No groups yet.") }
        reorderItems(order) { index, item ->
            val (group, channelCount) = item
            val channels = if (channelCount == 1) "1 channel" else "$channelCount channels"
            ReorderRow(
                list = order,
                item = item,
                title = group.name,
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            ) { rowModifier ->
                // A pencil, straight to the fields: a group someone made has its Name above its Start, a library group only the Start.
                val startField = FormField("Start", group.numberStart.toString(), KeyboardType.Number) { text -> text.filter(Char::isDigit).take(4) }
                val custom = !group.fromLibrary
                FormRow(
                    title = group.name,
                    fields = if (custom) listOf(FormField("Name", group.name) { it.take(40) }, startField) else listOf(startField),
                    summary = listOfNotNull("Library".takeIf { group.fromLibrary }, "starts at ${group.numberStart}", channels)
                        .joinToString(", ").replaceFirstChar { it.uppercase() },
                    icon = if (group.fromLibrary) Icons.Filled.FolderSpecial else Icons.Filled.Folder,
                    sheetTitle = "Edit ${group.name}",
                    about = "Numbering starts here.",
                    modifier = rowModifier,
                    onSave = { values ->
                        val name = if (custom) values[0] else group.name
                        val start = values.last().toIntOrNull()
                        when {
                            start == null -> "A start is a number from ${GroupRules.MIN_START} to ${GroupRules.MAX_START}."
                            else -> (if (custom) GroupRules.nameProblem(name, groups, group.id) else null)
                                ?: GroupRules.startProblem(start, groups, group.id)
                                ?: null.also {
                                    if (custom && name != group.name) viewModel.rename(group.id, name)
                                    if (start != group.numberStart) viewModel.setStart(group.id, start)
                                }
                        }
                    },
                )
            }
        }
    }
}
