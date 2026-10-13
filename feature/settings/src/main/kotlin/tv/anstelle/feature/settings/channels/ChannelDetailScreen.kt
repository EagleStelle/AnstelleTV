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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.channels.Airing
import tv.anstelle.core.data.channels.Catalog
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.LineupMatcher
import tv.anstelle.core.data.channels.LineupMix
import tv.anstelle.core.data.channels.MatchResult
import tv.anstelle.core.data.channels.PlaybackChoices
import tv.anstelle.core.data.channels.matchSummary
import tv.anstelle.core.data.channels.moviePlaybackHint
import tv.anstelle.core.data.channels.moviePlaybackLabel
import tv.anstelle.core.data.channels.seriesPlaybackHint
import tv.anstelle.core.data.channels.seriesPlaybackLabel
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelGroup
import tv.anstelle.core.model.Playback
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
import tv.anstelle.core.theme.components.SheetSwitch
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.components.SwitchRow
import tv.anstelle.core.theme.components.TextRow
import tv.anstelle.core.theme.components.foot
import tv.anstelle.core.theme.components.rememberInitialFocus
import tv.anstelle.core.theme.components.section
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@Immutable
data class ChannelDetailUiState(
    val channel: Channel? = null,
    val groups: List<ChannelGroup> = emptyList(),
    val nextGroupStart: Int = 0,
    val airings: List<Airing> = emptyList(),
    val lineupSummary: String? = null,
    val mix: LineupMix? = null,
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
            val matched = match()
            val logo = logo(channel.logoUri)
            combine(repository.observeGroups(), repository.observeNextGroupStart(), ticks) { groups, nextStart, _ ->
                ChannelDetailUiState(
                    channel = channel,
                    groups = groups,
                    nextGroupStart = nextStart,
                    airings = repository.upcoming(channelId, 3),
                    lineupSummary = matchSummary(matched),
                    mix = LineupMix.of(matched),
                    logo = logo,
                    loaded = true,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelDetailUiState())

    fun setFavorite(favorite: Boolean) = act { repository.setFavorite(channelId, favorite) }

    fun setVisible(visible: Boolean) = act { repository.setHidden(channelId, !visible) }

    fun setSeriesPlayback(playback: Playback) = act { repository.setSeriesPlayback(channelId, playback) }

    fun setMoviePlayback(playback: Playback) = act { repository.setMoviePlayback(channelId, playback) }

    fun setOncePerDay(oncePerDay: Boolean) = act { repository.setMoviesOncePerDay(channelId, oncePerDay) }

    /** Its number follows from the group. */
    fun setGroup(groupId: Long) = act { repository.setGroup(channelId, groupId) }

    fun newGroup(name: String, start: Int) = act { repository.setGroup(channelId, repository.createGroup(name, start)) }

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

    private suspend fun match(): MatchResult {
        val catalog = catalog ?: repository.catalog().also { catalog = it }
        return LineupMatcher(repository.lineup(channelId)).match(catalog)
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
 * One channel: what airs on it now, Watch, then its settings. Library channels can only be
 * favorited or hidden; channels built by hand also have lineup, group, playback, name, logo and
 * deletion controls. Series and movies each have their own playback, offered while the lineup airs
 * them (both while it airs nothing yet). Once daily sits in the Movies sheet, under its choices,
 * and only while the lineup can keep to it.
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
        trail = listOf("Settings", "Live TV"),
        about = if (channel.fromLibrary) {
            null
        } else {
            "Each movie airs at most once a day."
        },
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
        if (!channel.fromLibrary) {
            item(key = "lineup") {
                LinkRow(
                    title = "Lineup",
                    summary = state.lineupSummary ?: "Counting…",
                    icon = Icons.Filled.Tune,
                    onClick = { onEditLineup(channel.id) },
                    modifier = if (channel.isHidden) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            item(key = "group") {
                GroupChoiceRow(state.groups, channel.groupId, state.nextGroupStart, viewModel::setGroup, viewModel::newGroup)
            }
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
        if (channel.fromLibrary) return@SettingsPage

        val mix = state.mix
        if (mix != null) {
            section("Playback")
            val nothing = !mix.series && !mix.movies
            if (mix.series || nothing) {
                item(key = "series") {
                    ChoiceRow(
                        title = "Series",
                        value = channel.series,
                        choices = PlaybackChoices,
                        label = ::seriesPlaybackLabel,
                        detail = ::seriesPlaybackHint,
                        onSelect = viewModel::setSeriesPlayback,
                        icon = Icons.Filled.Tv,
                    )
                }
            }
            if (mix.movies || nothing) {
                item(key = "movies") {
                    // Once daily belongs to the movies: their sheet carries it, and their summary says it.
                    val oncePerDay = mix.fitsOncePerDay && channel.moviesOncePerDay
                    ChoiceRow(
                        title = "Movies",
                        value = channel.movies,
                        choices = PlaybackChoices,
                        label = ::moviePlaybackLabel,
                        detail = ::moviePlaybackHint,
                        onSelect = viewModel::setMoviePlayback,
                        icon = Icons.Filled.Movie,
                        summary = moviePlaybackLabel(channel.movies) + if (oncePerDay) ", once daily" else "",
                        switch = if (mix.fitsOncePerDay) {
                            SheetSwitch("Repeats", "Once daily", "Each movie at most once in 24 hours.", channel.moviesOncePerDay, viewModel::setOncePerDay)
                        } else {
                            null
                        },
                    )
                }
            }
        }
        section("Name and logo")
        item(key = "name") {
            TextRow(
                title = "Name",
                value = channel.name,
                about = "Its number follows the name, A to Z.",
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
                explanation = "Its schedule goes too.",
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

private fun timeLabel(utcMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMs))
