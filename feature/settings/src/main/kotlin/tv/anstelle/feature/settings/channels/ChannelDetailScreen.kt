package tv.anstelle.feature.settings.channels

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
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
import tv.anstelle.core.data.channels.Segment
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ChoiceRow
import tv.anstelle.core.theme.components.DangerButton
import tv.anstelle.core.theme.components.LinkRow
import tv.anstelle.core.theme.components.MenuAction
import tv.anstelle.core.theme.components.MenuRow
import tv.anstelle.core.theme.components.Notice
import tv.anstelle.core.theme.components.OsdButton
import tv.anstelle.core.theme.components.SettingsButtons
import tv.anstelle.core.theme.components.SettingsNotice
import tv.anstelle.core.theme.components.SettingsPage
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import java.io.IOException
import javax.inject.Inject

@Immutable
data class ChannelDetailUiState(
    val channel: Channel? = null,
    val groups: List<ChannelGroup> = emptyList(),
    val segments: List<Segment> = emptyList(),
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

    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** A note and whether it is a problem. */
    val message: StateFlow<Pair<String, Boolean>?> = _message

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
            combine(repository.observeGroups(), repository.observeSegments(), ticks) { groups, segments, _ ->
                ChannelDetailUiState(channel, groups, segments, repository.upcoming(channelId, 3), summary, logo, loaded = true)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelDetailUiState())

    fun setFavorite(favorite: Boolean) = act { repository.setFavorite(channelId, favorite) }

    fun setVisible(visible: Boolean) = act { repository.setHidden(channelId, !visible) }

    fun setPlayback(choice: Pair<PlaybackOrder, Int>) = act { repository.setPlayback(channelId, choice.first, choice.second) }

    fun setGroup(groupId: Long) = act { repository.setGroup(channelId, groupId) }

    fun newGroup(name: String) = act { repository.setGroup(channelId, repository.createGroup(name)) }

    /** Its number follows from the segment. */
    fun setSegment(segment: String) = act { repository.setSegment(channelId, segment) }

    fun newSegment(name: String) = act { repository.setSegment(channelId, Segment.keyOf(repository.createSegment(name))) }

    fun rename(name: String) = act { if (name.isNotBlank()) repository.rename(channelId, name) }

    fun setLogo(uri: Uri) = act {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: throw IOException("Can't read that file")
        repository.setLogo(channelId, bytes)
        _message.value = "Logo saved." to false
    }

    fun clearLogo() = act { repository.clearLogo(channelId) }

    fun noPicker() {
        _message.value = "This device has no file picker. A browser paired with Web control can upload a logo." to true
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
                _message.value = (e.message ?: "That didn't work.") to true
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

/**
 * One channel: what airs on it now, Watch, then its settings (lineup, playback order, group,
 * segment, favorite, visibility), its name and logo, and deleting it at the foot. Its number
 * comes from its segment and name; it isn't set here.
 */
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
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::setLogo) }

    SettingsPage(
        title = "${channel.number}  ${channel.name}",
        trail = listOf("Settings", "Channels"),
        about = listOfNotNull(
            "Made from your library. Once you change it, it stops updating on its own."
                .takeIf { channel.isManaged },
        ).joinToString(" "),
        aside = { OnAir(state.airings, state.logo, channel.name) },
        modifier = modifier,
    ) {
        item(key = "message") { message?.let { (text, problem) -> SettingsNotice(text, isError = problem) } }
        // A hidden channel isn't scheduled, so there is nothing to watch; Visible brings it back.
        if (!channel.isHidden) {
            item(key = "watch") {
                SettingsButtons {
                    OsdButton("Watch", onClick = { onWatch(channel.number) }, icon = Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(firstFocus))
                }
            }
        }
        item(key = "lineup") {
            LinkRow(
                title = "Lineup",
                summary = state.lineupSummary ?: "Counting…",
                icon = Icons.Filled.Tune,
                onClick = { onEditLineup(channel.id) },
                modifier = if (channel.isHidden) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        item(key = "playback") {
            ChoiceRow(
                title = "Playback",
                value = channel.playbackOrder to channel.blockSize,
                choices = PlaybackChoices,
                label = { (order, block) -> playbackLabel(order, block) },
                detail = { (order, block) -> playbackHint(order, block) },
                onSelect = viewModel::setPlayback,
                icon = Icons.Filled.Shuffle,
            )
        }
        item(key = "group") {
            GroupChoiceRow(state.groups, channel.groupId, viewModel::setGroup, viewModel::newGroup)
        }
        item(key = "segment") {
            SegmentChoiceRow(state.segments, channel.segment, viewModel::setSegment, viewModel::newSegment)
        }
        item(key = "favorite") {
            SwitchRow(
                title = "Favorite",
                checked = channel.isFavorite,
                onCheckedChange = viewModel::setFavorite,
                icon = if (channel.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            )
        }
        item(key = "visible") {
            SwitchRow(
                title = "Visible",
                checked = !channel.isHidden,
                onCheckedChange = viewModel::setVisible,
                icon = if (channel.isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            )
        }
        section("Name and logo")
        item(key = "name") {
            TextRow(
                title = "Name",
                value = channel.name,
                about = "Shown in Live TV and On Demand. Its number follows the name A to Z.",
                onSave = { name ->
                    if (name.isBlank()) {
                        "Give the channel a name."
                    } else {
                        viewModel.rename(name)
                        null
                    }
                },
                icon = Icons.Filled.Title,
            )
        }
        item(key = "logo") {
            MenuRow(
                title = "Logo",
                summary = if (state.logo == null) "None" else "Your image",
                icon = if (state.logo == null) Icons.Filled.HideImage else Icons.Filled.Image,
                about = "A PNG, JPEG or WebP image.",
                actions = buildList {
                    add(
                        MenuAction(if (state.logo == null) "Choose image" else "Change image", Icons.Filled.Image) {
                            try {
                                pickLogo.launch("image/*")
                            } catch (e: ActivityNotFoundException) {
                                viewModel.noPicker()
                            }
                        },
                    )
                    if (channel.logoUri != null) add(MenuAction("Remove logo", Icons.Filled.HideImage, danger = true, onClick = viewModel::clearLogo))
                },
            )
        }
        foot {
            DangerButton(
                text = "Delete channel",
                question = "Delete ${channel.name}?",
                explanation = if (channel.isManaged) "Its schedule goes too. You can bring it back from Removed channels." else "Its schedule goes too.",
                confirm = "Delete",
                onConfirm = viewModel::delete,
                icon = Icons.Filled.DeleteForever,
            )
        }
    }
}

/** What airs on the channel now and next, as the banner's status lines, and its logo. */
@Composable
private fun OnAir(airings: List<Airing>, logo: ImageBitmap?, name: String) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (logo != null) Image(logo, contentDescription = "$name logo", modifier = Modifier.size(72.dp))
        val now = airings.firstOrNull()
        if (now == null) {
            Notice("No schedule yet.")
            return@Column
        }
        airings.forEachIndexed { i, airing ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (i == 0) {
                    StatusLine(onAir = true, label = if (airing.title == null) "Off air" else "Now", text = "until ${timeLabel(airing.endUtcMs)}")
                } else {
                    StatusLine(onAir = false, label = if (i == 1) "Next" else "Later", text = timeLabel(airing.startUtcMs))
                }
                airing.title?.let { title ->
                    androidx.compose.material3.Text(title, style = typography.titleMedium, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
