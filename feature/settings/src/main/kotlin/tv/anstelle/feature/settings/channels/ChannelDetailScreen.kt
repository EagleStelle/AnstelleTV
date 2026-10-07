package tv.anstelle.feature.settings.channels

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.channels.Airing
import tv.anstelle.core.data.channels.Catalog
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.LineupMatcher
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.theme.components.AnstelleTextField
import tv.anstelle.core.theme.components.FocusTile
import tv.anstelle.feature.settings.servers.MessageText
import tv.anstelle.feature.settings.servers.SectionLabel
import tv.anstelle.feature.settings.servers.SettingsPage
import tv.anstelle.feature.settings.servers.rememberInitialFocus
import java.io.IOException
import javax.inject.Inject

@Immutable
data class ChannelDetailUiState(
    val channel: Channel? = null,
    val groups: List<ChannelGroup> = emptyList(),
    val airings: List<Airing> = emptyList(),
    val lineupSummary: String? = null,
    val logo: ImageBitmap? = null,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: ChannelRepository,
    private val time: TimeSource,
) : ViewModel() {
    /** Matches the `channelId` argument of the navigation route. */
    val channelId: Long = checkNotNull(savedState.get<Long>("channelId"))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var catalog: Catalog? = null
    private var logoCache: Pair<String, ImageBitmap?>? = null

    /** Re-reads the airings now and then: schedules are written in the background after edits. */
    private val ticks = flow {
        while (true) {
            emit(time.nowUtcMs())
            delay(REFRESH_MS)
        }
    }

    val state: StateFlow<ChannelDetailUiState> = repository.observeChannel(channelId)
        .flatMapLatest { channel ->
            if (channel == null) return@flatMapLatest flowOf(ChannelDetailUiState(loaded = true))
            val summary = summary()
            val logo = logo(channel.logoUri)
            combine(repository.observeGroups(channel.profileId), ticks) { groups, _ ->
                ChannelDetailUiState(channel, groups, repository.upcoming(channelId, 3), summary, logo, loaded = true)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelDetailUiState())

    fun toggleFavorite() = act { repository.setFavorite(channelId, !it.isFavorite) }

    fun toggleHidden() = act { repository.setHidden(channelId, !it.isHidden) }

    /** Steps to the next playback order. */
    fun nextPlayback() = act { channel ->
        val index = PlaybackChoices.indexOf(channel.playbackOrder to channel.blockSize)
        val (order, blockSize) = PlaybackChoices[(index + 1).mod(PlaybackChoices.size)]
        repository.setPlayback(channelId, order, blockSize)
    }

    fun setGroup(groupId: Long?) = act { repository.setGroup(channelId, groupId) }

    fun rename(name: String) = act { if (name.isNotBlank()) repository.rename(channelId, name) }

    fun setNumber(text: String) = act { channel ->
        val number = text.trim().toIntOrNull()
        if (number == null || number !in ChannelRepository.MIN_NUMBER..ChannelRepository.MAX_NUMBER) {
            _message.value = "Channel numbers are ${ChannelRepository.MIN_NUMBER} to ${ChannelRepository.MAX_NUMBER}."
        } else if (number != channel.number) {
            repository.setNumber(channelId, number)
            _message.value = "Now channel $number."
        }
    }

    fun setLogo(uri: Uri) = act {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: throw IOException("Can't read that file")
        repository.setLogo(channelId, bytes)
        _message.value = "Logo saved."
    }

    fun clearLogo() = act { repository.clearLogo(channelId) }

    fun noPicker() {
        _message.value = "This device has no file picker."
    }

    /** The screen closes when the channel is gone. */
    fun delete() {
        viewModelScope.launch { repository.delete(channelId) }
    }

    private fun act(action: suspend (Channel) -> Unit) {
        val channel = state.value.channel ?: return
        _message.value = null
        viewModelScope.launch {
            try {
                action(channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "That didn't work."
            }
        }
    }

    private suspend fun summary(): String {
        val catalog = catalog ?: repository.catalog().also { catalog = it }
        return matchSummary(LineupMatcher(repository.lineup(channelId)).match(catalog))
    }

    private suspend fun logo(uri: String?): ImageBitmap? {
        if (uri == null) return null
        logoCache?.let { (cached, bitmap) -> if (cached == uri) return bitmap }
        val bitmap = withContext(Dispatchers.IO) {
            repository.logoFile(channelId)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
        }
        logoCache = uri to bitmap
        return bitmap
    }

    private companion object {
        const val REFRESH_MS = 15_000L
    }
}

/** One channel: what's on, its lineup, playback order, number, group, logo and flags. */
@Composable
fun ChannelDetailScreen(
    onWatch: (channelNumber: Int) -> Unit,
    onEditLineup: (Long) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val channel = state.channel
    if (channel == null) {
        if (state.loaded) LaunchedEffect(Unit) { onDeleted() }
        return
    }
    val firstFocus = rememberInitialFocus()
    var name by rememberSaveable(channel.name) { mutableStateOf(channel.name) }
    var number by rememberSaveable(channel.number) { mutableStateOf(channel.number.toString()) }
    var pickingGroup by remember { mutableStateOf(false) }
    // Picking a group closes the list under the Group tile; focus goes back to the tile.
    val groupFocus = remember { FocusRequester() }
    val pickGroup: (Long?) -> Unit = { groupId ->
        viewModel.setGroup(groupId)
        pickingGroup = false
        runCatching { groupFocus.requestFocus() }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::setLogo) }

    SettingsPage(title = "${channel.number}  ${channel.name}", modifier = modifier) {
        item(key = "airings") {
            val now = state.airings.firstOrNull()
            MessageText(
                when {
                    now == null -> "No schedule yet."
                    now.title == null -> "Off air until ${timeLabel(now.endUtcMs)}."
                    else -> "Now: ${now.title}, until ${timeLabel(now.endUtcMs)}."
                },
            )
            state.airings.drop(1).forEach { airing ->
                MessageText("${timeLabel(airing.startUtcMs)}  ${airing.title ?: "Off air"}")
            }
        }
        if (channel.isManaged) {
            item(key = "managed") { MessageText("Made from your library. Editing it keeps your changes and stops automatic updates.") }
        }
        item(key = "message") { message?.let { MessageText(it, isError = true) } }
        item(key = "watch") {
            FocusTile(
                title = "Watch",
                subtitle = if (channel.isHidden) "Hidden channels can't be watched." else "Tune in full screen.",
                icon = Icons.Filled.PlayArrow,
                onClick = { if (!channel.isHidden) onWatch(channel.number) },
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
        }
        item(key = "lineup") {
            FocusTile(
                title = "Edit lineup",
                subtitle = state.lineupSummary ?: "Counting…",
                icon = Icons.Filled.Tune,
                onClick = { onEditLineup(channel.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "playback") {
            FocusTile(
                title = "Playback: ${playbackLabel(channel.playbackOrder, channel.blockSize)}",
                subtitle = playbackHint(channel.playbackOrder, channel.blockSize) + " Select to change.",
                icon = Icons.Filled.Shuffle,
                onClick = viewModel::nextPlayback,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "favorite") {
            FocusTile(
                title = if (channel.isFavorite) "Favorite" else "Not a favorite",
                subtitle = "Favorites can be filtered in the guide. Select to change.",
                icon = if (channel.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                onClick = viewModel::toggleFavorite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "hidden") {
            FocusTile(
                title = if (channel.isHidden) "Hidden from the guide" else "Shown in the guide",
                subtitle = if (channel.isHidden) "Hidden channels are skipped and not scheduled. Select to show." else "Select to hide.",
                icon = if (channel.isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                onClick = viewModel::toggleHidden,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "group") {
            val group = state.groups.firstOrNull { it.id == channel.groupId }
            FocusTile(
                title = "Group: ${group?.name ?: "none"}",
                subtitle = if (pickingGroup) "Choose a group below." else "Select to change.",
                icon = if (group == null) Icons.Filled.FolderOff else Icons.Filled.Folder,
                onClick = { pickingGroup = !pickingGroup },
                modifier = Modifier.fillMaxWidth().focusRequester(groupFocus),
            )
        }
        if (pickingGroup) {
            item(key = "group-none") {
                FocusTile(
                    title = "No group",
                    icon = Icons.Filled.FolderOff,
                    onClick = { pickGroup(null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(state.groups, key = { "group-${it.id}" }) { option ->
                FocusTile(
                    title = option.name,
                    icon = Icons.Filled.Folder,
                    onClick = { pickGroup(option.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "names") { SectionLabel("Name and number") }
        item(key = "name") {
            AnstelleTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                isLast = true,
                onDone = { viewModel.rename(name) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus && name != channel.name) viewModel.rename(name) },
            )
        }
        item(key = "number") {
            AnstelleTextField(
                value = number,
                onValueChange = { value -> number = value.filter(Char::isDigit).take(4) },
                label = "Channel number (a channel that has it swaps with this one)",
                keyboardType = KeyboardType.Number,
                isLast = true,
                onDone = { viewModel.setNumber(number) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.hasFocus && number != channel.number.toString()) viewModel.setNumber(number) },
            )
        }
        item(key = "logos") { SectionLabel("Logo") }
        state.logo?.let { logo ->
            item(key = "logo-image") { Image(logo, contentDescription = "${channel.name} logo", modifier = Modifier.size(96.dp)) }
        }
        item(key = "logo") {
            FocusTile(
                title = if (state.logo == null) "Choose a logo" else "Change the logo",
                subtitle = "A PNG, JPEG or WebP image. Large images are scaled down.",
                icon = Icons.Filled.Image,
                onClick = {
                    try {
                        pickLogo.launch("image/*")
                    } catch (e: ActivityNotFoundException) {
                        viewModel.noPicker()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (channel.logoUri != null) {
            item(key = "logo-clear") {
                FocusTile(
                    title = "Remove the logo",
                    icon = Icons.Filled.HideImage,
                    onClick = viewModel::clearLogo,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "danger") { SectionLabel("Channel") }
        item(key = "delete") {
            FocusTile(
                title = if (confirmDelete) "Select again to delete ${channel.name}" else "Delete channel",
                subtitle = if (channel.isManaged) "Removes the channel and its schedule. Removed channels can bring it back." else "Removes the channel and its schedule.",
                icon = Icons.Filled.DeleteForever,
                onClick = { if (confirmDelete) viewModel.delete() else confirmDelete = true },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) confirmDelete = false },
            )
        }
    }
}
