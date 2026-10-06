package tv.anstelle.feature.settings.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LiveTv
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
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.channels.ChannelPick
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.channels.Lineup
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SectionLabel
import tv.anstelle.feature.settings.servers.SettingsPage
import tv.anstelle.feature.settings.servers.rememberInitialFocus
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
    private val profiles: ProfileRepository,
    private val repository: ChannelRepository,
) : ViewModel() {
    /** Matches the `itemId` argument of the navigation route. */
    private val itemId: Long = checkNotNull(savedState.get<Long>("itemId"))

    private val _state = MutableStateFlow(AddToChannelUiState())
    val state: StateFlow<AddToChannelUiState> = _state.asStateFlow()

    private var profileId = 0L

    init {
        viewModelScope.launch {
            profileId = profiles.ensureActiveProfile().id
            val pick = repository.pickFor(itemId)
            val channels = repository.observeChannels(profileId).first()
            val including = pick?.let { repository.channelsIncluding(profileId, it.pick.remoteId) }.orEmpty()
            _state.value = AddToChannelUiState(pick, channels, including, loaded = true)
        }
    }

    fun addTo(channel: ChannelSummary) = act { pick ->
        repository.include(channel.channel.id, pick.pick)
        _state.update {
            it.copy(
                including = it.including + channel.channel.id,
                message = "${pick.title} now airs on ${channel.channel.number} ${channel.channel.name}, after the program on now and the next one.",
            )
        }
    }

    fun newChannel() = act { pick ->
        val id = repository.create(profileId, pick.title, Lineup(emptyList(), includes = listOf(pick.pick)), PlaybackOrder.EPISODE_ORDER, 1)
        val channels = repository.observeChannels(profileId).first()
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

/** Makes a channel always air a show or movie, or builds a new channel around it. */
@Composable
fun AddToChannelScreen(
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddToChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    val pick = state.pick
    if (pick == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val firstFocus = rememberInitialFocus()
    SettingsPage(
        title = "Add ${pick.title} to a channel",
        subtitle = "The channel always airs it, whatever its rules say. Its schedule changes after the program on now and the next one.",
        modifier = modifier,
    ) {
        item(key = "message") { state.message?.let { MessageText(it) } }
        item(key = "new") {
            FocusTile(
                title = "New channel: ${pick.title}",
                subtitle = "A channel of its own, playing it in episode order.",
                icon = Icons.Filled.AddCircle,
                onClick = viewModel::newChannel,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        if (state.channels.isNotEmpty()) item(key = "label") { SectionLabel("Your channels") }
        items(state.channels, key = { it.channel.id }) { summary ->
            val channel = summary.channel
            val included = channel.id in state.including
            FocusTile(
                title = "${channel.number}  ${channel.name}",
                subtitle = if (included) "Always airs ${pick.title}." else listOfNotNull(summary.groupName, "hidden".takeIf { channel.isHidden }).joinToString(" · ").ifEmpty { null },
                icon = if (included) Icons.Filled.CheckCircle else Icons.Filled.LiveTv,
                onClick = { if (!included) viewModel.addTo(summary) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
