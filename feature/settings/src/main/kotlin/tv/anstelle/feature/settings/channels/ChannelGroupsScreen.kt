package tv.anstelle.feature.settings.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.channels.ChannelRepository
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
data class GroupItem(val id: Long, val name: String, val channelCount: Int)

@Immutable
data class GroupsUiState(val groups: List<GroupItem> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class ChannelGroupsViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<GroupsUiState> = combine(repository.observeGroups(), repository.observeChannels()) { groups, channels ->
        val counts = channels.groupingBy { it.channel.groupId }.eachCount()
        GroupsUiState(groups.map { GroupItem(it.id, it.name, counts[it.id] ?: 0) }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createGroup(name) }
    }

    fun rename(groupId: Long, name: String) {
        viewModelScope.launch { repository.renameGroup(groupId, name) }
    }

    fun move(groupId: Long, delta: Int) {
        viewModelScope.launch { repository.moveGroup(groupId, delta) }
    }

    fun delete(groupId: Long) {
        viewModelScope.launch { repository.deleteGroup(groupId) }
    }
}

/** Groups in Live TV's order: New group, then each group's menu renames, moves or deletes it. */
@Composable
fun ChannelGroupsScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelGroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = rememberInitialFocus(ready = state.loaded)

    SettingsPage(
        title = "Groups",
        trail = listOf("Settings", "Channels"),
        about = "Each group is a tab in Live TV, in this order.",
        modifier = modifier,
    ) {
        item(key = "new") {
            val sheets = LocalSettingsSheets.current
            val button = remember { FocusRequester() }
            SettingsButtons {
                OsdButton(
                    "New group",
                    onClick = {
                        sheets.show(
                            EditSheet("New group", null, "Name", "", KeyboardType.Text, { it.take(40) }) { name ->
                                if (name.isBlank()) "Give the group a name." else null.also { viewModel.create(name) }
                            },
                            from = button,
                        )
                    },
                    icon = Icons.Filled.CreateNewFolder,
                    modifier = Modifier.focusRequester(button).let { if (state.groups.isEmpty()) it.focusRequester(firstFocus) else it },
                )
            }
        }
        if (state.loaded && state.groups.isEmpty()) item(key = "empty") { SettingsNotice("No groups yet.") }
        state.groups.forEachIndexed { index, group ->
            item(key = "group-${group.id}") {
                val sheets = LocalSettingsSheets.current
                MenuRow(
                    title = group.name,
                    summary = if (group.channelCount == 1) "1 channel" else "${group.channelCount} channels",
                    icon = Icons.Filled.Folder,
                    actions = buildList {
                        add(
                            MenuAction("Rename", Icons.Filled.DriveFileRenameOutline) {
                                sheets.show(
                                    EditSheet("Rename ${group.name}", null, "Name", group.name, KeyboardType.Text, { it.take(40) }) { name ->
                                        if (name.isBlank()) "Give the group a name." else null.also { viewModel.rename(group.id, name) }
                                    },
                                )
                            },
                        )
                        if (index > 0) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { viewModel.move(group.id, -1) })
                        if (index < state.groups.lastIndex) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { viewModel.move(group.id, 1) })
                        add(
                            MenuAction("Delete", Icons.Filled.Delete, danger = true) {
                                sheets.show(ConfirmSheet("Delete ${group.name}?", "Its channels stay, without a group.", "Delete", Icons.Filled.Delete) { viewModel.delete(group.id) })
                            },
                        )
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}
