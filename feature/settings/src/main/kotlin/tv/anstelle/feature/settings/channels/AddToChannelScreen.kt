package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.anstelle.core.data.channels.ChannelPick
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.channels.Lineup
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.theme.components.CheckRow
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import javax.inject.Inject

@Immutable
data class AddToChannelUiState(
    val pick: ChannelPick? = null,
    val channels: List<ChannelSummary> = emptyList(),
    /** Channels that already always air the pick. */
    val including: Set<Long> = emptySet(),
    val message: String? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class AddToChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ChannelRepository,
) : ViewModel() {
    /** Matches the `itemId` argument of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))

    private val _state = MutableStateFlow(AddToChannelUiState())
    val state: StateFlow<AddToChannelUiState> = _state.asStateFlow()

    /** The group and segment New channel puts the channel in. */
    val placement = PlacementPicker(repository, viewModelScope) { problem -> _state.update { it.copy(message = problem) } }

    init {
        viewModelScope.launch {
            val pick = repository.pickFor(itemId)
            val channels = repository.observeChannels().first().filterNot { it.channel.fromLibrary }
            val including = pick?.let { repository.channelsIncluding(it.pick.remoteId) }.orEmpty()
            _state.value = AddToChannelUiState(pick, channels, including, loaded = true)
        }
    }

    fun addTo(channel: ChannelSummary) = act { pick ->
        repository.include(channel.channel.id, pick.pick)
        _state.update {
            it.copy(
                including = it.including + channel.channel.id,
                message = "${pick.title} now plays on ${channel.channel.number} ${channel.channel.name}, starting after the next program.",
            )
        }
    }

    fun newChannel() = act { pick ->
        val place = placement.state.value
        place.problem?.let { throw IllegalArgumentException(it) }
        val id = repository.create(pick.title, Lineup(emptyList(), includes = listOf(pick.pick)), PlaybackOrder.EPISODE_ORDER, 1, place.groupId!!, place.segment!!)
        val channels = repository.observeChannels().first().filterNot { it.channel.fromLibrary }
        val number = channels.firstOrNull { it.channel.id == id }?.channel?.number
        _state.update {
            it.copy(
                channels = channels,
                including = it.including + id,
                message = "Created channel $number, ${pick.title}.",
            )
        }
    }

    private fun act(action: suspend (ChannelPick) -> Unit) {
        val current = _state.value
        val pick = current.pick ?: return
        if (current.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                action(pick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "That didn't work.") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/**
 * Makes custom channels always air a show or movie: a checkbox per channel (checked once it
 * airs it), or New channel builds a channel around it in the group and segment above it.
 */
@Composable
fun AddToChannelScreen(
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddToChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val placement by viewModel.placement.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    val pick = state.pick
    if (pick == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Add to channel",
        showBack = true,
        about = "Checked channels always play ${pick.title}, starting after the next program. New channel makes one just for it.",
        aside = {
            androidx.compose.material3.Text(
                pick.title,
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = tv.anstelle.core.theme.LocalAnstelleTheme.current.text,
            )
        },
        modifier = modifier,
    ) {
        item(key = "message") { state.message?.let { SettingsNotice(it) } }
        item(key = "group") {
            GroupChoiceRow(placement.groups, placement.groupId, viewModel.placement::pickGroup, viewModel.placement::newGroup)
        }
        item(key = "segment") {
            SegmentChoiceRow(placement.segments, placement.segment, viewModel.placement::pickSegment, viewModel.placement::newSegment)
        }
        item(key = "new") {
            SettingsButtons {
                OsdButton("New channel", onClick = viewModel::newChannel, icon = Icons.Filled.Add, primary = true, modifier = Modifier.focusRequester(firstFocus))
            }
        }
        if (state.channels.isNotEmpty()) section("Your channels")
        items(state.channels, key = { it.channel.id }) { summary ->
            val channel = summary.channel
            val included = channel.id in state.including
            CheckRow(
                title = "${channel.number}  ${channel.name}",
                summary = if (included) "Always airs ${pick.title}" else listOfNotNull(summary.groupName, "hidden".takeIf { channel.isHidden }).joinToString(", ").ifEmpty { null },
                checked = included,
                // Taking a pick off a channel happens in its lineup.
                enabled = !included,
                onClick = { viewModel.addTo(summary) },
            )
        }
    }
}
