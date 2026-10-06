package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@Immutable
data class GroupItem(val id: Long, val name: String, val channelCount: Int)

@Immutable
data class GroupsUiState(val groups: List<GroupItem> = emptyList(), val loaded: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelGroupsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<GroupsUiState> = profiles.activeProfileId()
        .flatMapLatest { profileId ->
            combine(repository.observeGroups(profileId), repository.observeChannels(profileId)) { groups, channels ->
                val counts = channels.groupingBy { it.channel.groupId }.eachCount()
                GroupsUiState(groups.map { GroupItem(it.id, it.name, counts[it.id] ?: 0) }, loaded = true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createGroup(profiles.activeProfileId().first(), name) }
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

/** Guide groups: create, rename, order and delete. */
@Composable
fun ChannelGroupsScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelGroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(state.loaded) { if (state.loaded) runCatching { firstFocus.requestFocus() } }
    var newName by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }

    SettingsPage(
        title = "Groups",
        subtitle = "The guide shows groups as tabs, in this order. Set a channel's group on its page.",
        modifier = modifier,
    ) {
        item(key = "new") {
            AnstelleTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "New group name",
                isLast = true,
                onDone = {
                    viewModel.create(newName)
                    newName = ""
                },
                // With groups to edit, focus starts on the first one instead of opening the keyboard.
                modifier = Modifier.fillMaxWidth().let { if (state.loaded && state.groups.isEmpty()) it.focusRequester(firstFocus) else it },
            )
        }
        item(key = "create") {
            FocusTile(
                title = "Create group",
                icon = Icons.Filled.CreateNewFolder,
                onClick = {
                    viewModel.create(newName)
                    newName = ""
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.loaded && state.groups.isEmpty()) item(key = "empty") { MessageText("No groups yet.") }
        state.groups.forEachIndexed { index, group ->
            val isOpen = open == group.id
            item(key = "group-${group.id}") {
                FocusTile(
                    title = group.name,
                    subtitle = "%d %s · select to %s".format(group.channelCount, if (group.channelCount == 1) "channel" else "channels", if (isOpen) "close" else "edit"),
                    icon = if (isOpen) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                    onClick = { open = if (isOpen) null else group.id },
                    modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(firstFocus) else it },
                )
            }
            if (isOpen) {
                item(key = "rename-${group.id}") {
                    var name by rememberSaveable(group.name) { mutableStateOf(group.name) }
                    AnstelleTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Name",
                        isLast = true,
                        onDone = { viewModel.rename(group.id, name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (index > 0) {
                    item(key = "up-${group.id}") {
                        FocusTile("Move up", onClick = { viewModel.move(group.id, -1) }, icon = Icons.Filled.ArrowUpward, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (index < state.groups.lastIndex) {
                    item(key = "down-${group.id}") {
                        FocusTile("Move down", onClick = { viewModel.move(group.id, 1) }, icon = Icons.Filled.ArrowDownward, modifier = Modifier.fillMaxWidth())
                    }
                }
                item(key = "delete-${group.id}") {
                    FocusTile(
                        title = "Delete group",
                        subtitle = "Its channels stay, without a group.",
                        icon = Icons.Filled.Delete,
                        onClick = {
                            viewModel.delete(group.id)
                            open = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
