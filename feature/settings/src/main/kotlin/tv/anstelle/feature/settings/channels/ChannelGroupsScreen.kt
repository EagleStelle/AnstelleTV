package tv.anstelle.feature.settings.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Numbers
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
import tv.anstelle.core.theme.components.ConfirmSheet
import tv.anstelle.core.theme.components.EditSheet
import tv.anstelle.core.theme.components.LocalSettingsSheets
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
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
 * each group's menu renames, renumbers, moves or deletes it. The library's groups only move and
 * renumber, and a group someone made is deleted once it is empty.
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

    SettingsPage(
        title = "Groups",
        trail = listOf("Settings", "Channels"),
        about = "Each group is a tab in Live TV, in this order, and numbers its channels A to Z from its start. " +
            "Library groups hold the channels made from your library. A group you made can be deleted once it is empty.",
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
        state.groups.forEachIndexed { index, (group, channelCount) ->
            item(key = "group-${group.id}") {
                val sheets = LocalSettingsSheets.current
                val channels = if (channelCount == 1) "1 channel" else "$channelCount channels"
                MenuRow(
                    title = group.name,
                    summary = listOfNotNull("Library".takeIf { group.fromLibrary }, "starts at ${group.numberStart}", channels)
                        .joinToString(", ").replaceFirstChar { it.uppercase() },
                    icon = if (group.fromLibrary) Icons.Filled.FolderSpecial else Icons.Filled.Folder,
                    actions = buildList {
                        if (!group.fromLibrary) {
                            add(
                                MenuAction("Rename", Icons.Filled.DriveFileRenameOutline) {
                                    sheets.show(
                                        EditSheet("Rename ${group.name}", null, "Name", group.name, KeyboardType.Text, { it.take(40) }) { name ->
                                            GroupRules.nameProblem(name, groups, group.id) ?: null.also { viewModel.rename(group.id, name) }
                                        },
                                    )
                                },
                            )
                        }
                        add(
                            MenuAction("Change start", Icons.Filled.Numbers) {
                                sheets.show(
                                    startSheet(group.name, group.numberStart) { start ->
                                        GroupRules.startProblem(start, groups, group.id) ?: null.also { viewModel.setStart(group.id, start) }
                                    },
                                )
                            },
                        )
                        if (index > 0) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { viewModel.move(group.id, -1) })
                        if (index < state.groups.lastIndex) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { viewModel.move(group.id, 1) })
                        if (!group.fromLibrary && channelCount == 0) {
                            add(
                                MenuAction("Delete", Icons.Filled.Delete, danger = true) {
                                    sheets.show(ConfirmSheet("Delete ${group.name}?", null, "Delete", Icons.Filled.Delete) { viewModel.delete(group.id) })
                                },
                            )
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}
