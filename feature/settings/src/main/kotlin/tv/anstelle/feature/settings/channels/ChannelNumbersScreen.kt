package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSegment
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SectionLabel
import tv.anstelle.feature.settings.servers.SettingsPage
import javax.inject.Inject

@Immutable
data class NumberStarts(
    val segments: Map<ChannelSegment, Int> = emptyMap(),
    /** Segments someone made: groups with a start of their own. */
    val own: List<ChannelGroup> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelNumbersViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    private val profileId = profiles.activeProfileId()

    val state: StateFlow<NumberStarts> = profileId
        .flatMapLatest { id ->
            combine(repository.observeNumberStarts(id), repository.observeGroups(id)) { segments, groups ->
                NumberStarts(segments, groups.filter { it.numberStart != null })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NumberStarts())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setStart(segment: ChannelSegment, text: String) = save(text, state.value.segments[segment]) { id, start -> repository.setNumberStart(id, segment, start) }

    fun setSegmentStart(group: ChannelGroup, text: String) = save(text, group.numberStart) { _, start -> repository.setSegmentStart(group.id, start) }

    fun rename(group: ChannelGroup, name: String) = act { if (name.isNotBlank() && name.trim() != group.name) repository.renameGroup(group.id, name) }

    fun addSegment() = act { repository.createSegment(profileId.first(), "New segment") }

    fun delete(group: ChannelGroup) = act { repository.deleteGroup(group.id) }

    private fun save(text: String, current: Int?, action: suspend (Long, Int) -> Unit) {
        val start = text.trim().toIntOrNull()
        if (start == current) return
        if (start == null || start !in ChannelRepository.MIN_NUMBER..ChannelRepository.MAX_NUMBER) {
            _message.value = "Channel numbers are ${ChannelRepository.MIN_NUMBER} to ${ChannelRepository.MAX_NUMBER}."
            return
        }
        act { action(profileId.first(), start) }
    }

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

/** Where each group of channels is numbered from: 1000, 1001, 1002… */
@Composable
fun ChannelNumbersScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelNumbersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    SettingsPage(
        title = "Channel numbers",
        subtitle = "Each group counts up from its start (any number from ${ChannelRepository.MIN_NUMBER} to ${ChannelRepository.MAX_NUMBER}). " +
            "Changing a start renumbers its channels. A channel you renumber keeps its number; one you build outside a segment " +
            "takes the first free number after every start.",
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { MessageText(it, isError = true) } }
        item(key = "library") { SectionLabel("Made from your library") }
        for (segment in ChannelSegment.entries) {
            val start = state.segments[segment] ?: continue
            item(key = segment.name) {
                StartField("${segment.groupName} start at", start) { viewModel.setStart(segment, it) }
            }
        }
        if (state.own.isNotEmpty()) item(key = "own") { SectionLabel("Your segments") }
        for (group in state.own) {
            item(key = "segment-${group.id}") { SegmentFields(group, viewModel) }
        }
        item(key = "add") {
            FocusTile(
                title = "Add a segment",
                subtitle = "A named group with numbers of its own. Put channels in it with their Group setting.",
                icon = Icons.Filled.CreateNewFolder,
                onClick = viewModel::addSegment,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SegmentFields(group: ChannelGroup, viewModel: ChannelNumbersViewModel) {
    var name by rememberSaveable(group.name) { mutableStateOf(group.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    AnstelleTextField(
        value = name,
        onValueChange = { name = it },
        label = "Segment name",
        isLast = true,
        onDone = { viewModel.rename(group, name) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus && name != group.name) viewModel.rename(group, name) },
    )
    StartField("${group.name} start at", group.numberStart ?: 0) { viewModel.setSegmentStart(group, it) }
    FocusTile(
        title = if (confirmDelete) "Select again to delete ${group.name}" else "Delete ${group.name}",
        subtitle = "Its channels stay, without a group, and keep their numbers.",
        icon = Icons.Filled.Delete,
        onClick = { if (confirmDelete) viewModel.delete(group) else confirmDelete = true },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmDelete = false },
    )
}

@Composable
private fun StartField(label: String, start: Int, onSave: (String) -> Unit) {
    var text by rememberSaveable(start) { mutableStateOf(start.toString()) }
    AnstelleTextField(
        value = text,
        onValueChange = { value -> text = value.filter(Char::isDigit).take(4) },
        label = label,
        keyboardType = KeyboardType.Number,
        isLast = true,
        onDone = { onSave(text) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus && text != start.toString()) onSave(text) },
    )
}
