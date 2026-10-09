package tv.anstelle.feature.settings.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FormatListNumbered
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
import tv.anstelle.core.data.channels.ChannelSegment
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
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

@Immutable
data class NumberStarts(
    val segments: Map<ChannelSegment, Int> = emptyMap(),
    /** Segments someone made: groups with a start of their own. */
    val own: List<ChannelGroup> = emptyList(),
)

@HiltViewModel
class ChannelNumbersViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {
    val state: StateFlow<NumberStarts> = combine(repository.observeNumberStarts(), repository.observeGroups()) { segments, groups ->
        NumberStarts(segments, groups.filter { it.numberStart != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NumberStarts())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setStart(segment: ChannelSegment, text: String) = save(text) { start -> repository.setNumberStart(segment, start) }

    fun setSegmentStart(group: ChannelGroup, text: String) = save(text) { start -> repository.setSegmentStart(group.id, start) }

    fun rename(group: ChannelGroup, name: String) = act { if (name.isNotBlank() && name.trim() != group.name) repository.renameGroup(group.id, name) }

    fun addSegment() = act { repository.createSegment("New segment") }

    fun delete(group: ChannelGroup) = act { repository.deleteGroup(group.id) }

    /** Saves a start typed in; returns the problem with it, or null. */
    private fun save(text: String, action: suspend (Int) -> Unit): String? {
        val start = text.trim().toIntOrNull()
        if (start == null || start !in ChannelRepository.MIN_NUMBER..ChannelRepository.MAX_NUMBER) {
            return "A start is a number from ${ChannelRepository.MIN_NUMBER} to ${ChannelRepository.MAX_NUMBER}."
        }
        act { action(start) }
        return null
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

/** Segments, where each one's channel numbers start: the library's four, then the user's own with Add segment. */
@Composable
fun ChannelNumbersScreen(
    modifier: Modifier = Modifier,
    viewModel: ChannelNumbersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val digits = { text: String -> text.filter(Char::isDigit).take(4) }
    val firstFocus = rememberInitialFocus(ready = state.segments.isNotEmpty())
    SettingsPage(
        title = "Segments",
        trail = listOf("Settings", "Channels"),
        about = "A segment numbers its channels A to Z from its start. Change a start to move them all.",
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { SettingsNotice(it, isError = true) } }
        section("Library segments")
        for (segment in ChannelSegment.entries) {
            val start = state.segments[segment] ?: continue
            item(key = segment.name) {
                TextRow(
                    title = segment.groupName,
                    value = start.toString(),
                    summary = "Starts at $start",
                    label = "Start",
                    keyboardType = KeyboardType.Number,
                    filter = digits,
                    onSave = { viewModel.setStart(segment, it) },
                    icon = Icons.Filled.FormatListNumbered,
                    modifier = if (segment == ChannelSegment.entries.first { it in state.segments }) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        section("Custom segments")
        for (group in state.own) {
            item(key = "segment-${group.id}") {
                val sheets = LocalSettingsSheets.current
                val start = group.numberStart ?: 0
                MenuRow(
                    title = group.name,
                    summary = "Starts at $start",
                    icon = Icons.Filled.FormatListNumbered,
                    actions = listOf(
                        MenuAction("Rename", Icons.Filled.DriveFileRenameOutline) {
                            sheets.show(
                                EditSheet("Rename ${group.name}", null, "Name", group.name, KeyboardType.Text, { it.take(40) }) { name ->
                                    if (name.isBlank()) "Give the segment a name." else null.also { viewModel.rename(group, name) }
                                },
                            )
                        },
                        MenuAction("Change start", Icons.Filled.Numbers) {
                            sheets.show(EditSheet("${group.name} start", "Its channels are numbered from here.", "Start", start.toString(), KeyboardType.Number, digits) { viewModel.setSegmentStart(group, it) })
                        },
                        MenuAction("Delete", Icons.Filled.Delete, danger = true) {
                            sheets.show(ConfirmSheet("Delete ${group.name}?", "Its channels stay but lose this group and get new numbers.", "Delete", Icons.Filled.Delete) { viewModel.delete(group) })
                        },
                    ),
                )
            }
        }
        item(key = "add") {
            SettingsButtons {
                OsdButton("Add segment", onClick = viewModel::addSegment, icon = Icons.Filled.CreateNewFolder)
            }
        }
    }
}
