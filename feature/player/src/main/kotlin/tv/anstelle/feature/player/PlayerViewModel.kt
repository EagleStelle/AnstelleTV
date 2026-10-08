package tv.anstelle.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.TRACK_TYPE_AUDIO
import tv.anstelle.core.data.playback.TRACK_TYPE_TEXT
import tv.anstelle.core.data.playback.TrackOption
import tv.anstelle.core.data.playback.TuneStatus
import tv.anstelle.core.model.Channel
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    profiles: ProfileRepository,
    channels: ChannelRepository,
    private val playback: ChannelPlayer,
    private val settings: SettingsRepository,
) : ViewModel() {
    /** Matches the `channelNumber` argument of the navigation route; 0 resumes the last channel. */
    /** The channel number asked for; negative when none was (0 is a channel number like any other). */
    private val requestedNumber: Int = savedState.get<Int>("channelNumber") ?: -1

    /** Matches the `restart` argument: Start over, once, when the asked-for channel first plays. */
    private var restartPending: Boolean = savedState.get<Boolean>("restart") ?: false

    val state = playback.state
    val player = playback.player

    private val _entry = MutableStateFlow("")

    /** Digits typed so far for a channel number. */
    val entry: StateFlow<String> = _entry.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var lineup: List<Channel>? = null
    private var resumeChannelId: Long? = null
    private var visible = false
    private var entryJob: Job? = null
    private var noticeJob: Job? = null

    init {
        viewModelScope.launch {
            val profile = profiles.ensureActiveProfile()
            channels.observeChannels().collect { list ->
                val tunable = list.filter { !it.channel.isHidden && it.inSeason }.map { it.channel }
                playback.setLineup(tunable)
                if (lineup == null) {
                    val last = settings.get(Settings.lastChannel(profile.id))
                    resumeChannelId = (tunable.firstOrNull { requestedNumber >= 0 && it.number == requestedNumber } ?: tunable.firstOrNull { it.id == last } ?: tunable.firstOrNull())?.id
                    lineup = tunable
                    if (visible) start()
                }
                lineup = tunable
            }
        }
    }

    /** The screen is visible: play. */
    fun resume() {
        if (!visible) {
            visible = true
            playback.acquire()
        }
        if (lineup != null) start()
    }

    /**
     * The screen is hidden or the app went to the background: remember the channel. Playback
     * stops unless the guide's preview window takes it over.
     */
    fun pause() {
        if (!visible) return
        visible = false
        state.value.channel?.id?.let { resumeChannelId = it }
        playback.release()
    }

    private fun start() {
        val id = resumeChannelId
        if (id == null) {
            show("No channels yet. Add some in Settings, Channels.")
            return
        }
        playback.tune(id)
        if (restartPending) {
            restartPending = false
            savedState["restart"] = false
            viewModelScope.launch {
                state.first { it.status == TuneStatus.PLAYING && it.channel?.id == id }
                playback.restart()
            }
        }
    }

    fun channelUp() = playback.channelUp()

    fun channelDown() = playback.channelDown()

    /** Where the program on now is, in its media. */
    val positionMs: Long get() = playback.positionMs

    /** Seeks in the program on now; the channel's schedule moves with it. */
    fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun restart() = playback.restart()

    /** A digit of a channel number; the number tunes 2 s after the last digit, or at 4 digits. */
    fun digit(value: Int) {
        val typed = (_entry.value + value).takeLast(MAX_DIGITS)
        _entry.value = typed
        entryJob?.cancel()
        if (typed.length == MAX_DIGITS) {
            commitEntry()
        } else {
            entryJob = viewModelScope.launch {
                delay(ENTRY_TIMEOUT_MS)
                commitEntry()
            }
        }
    }

    fun commitEntry() {
        entryJob?.cancel()
        val number = _entry.value.toIntOrNull()
        _entry.value = ""
        if (number != null && !playback.tuneNumber(number)) show("There is no channel $number.")
    }

    fun audioOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_AUDIO)

    fun subtitleOptions(): List<TrackOption> = playback.trackOptions(TRACK_TYPE_TEXT)

    fun selectAudio(option: TrackOption) = playback.selectTrack(TRACK_TYPE_AUDIO, option)

    fun selectSubtitles(option: TrackOption) = playback.selectTrack(TRACK_TYPE_TEXT, option)

    private fun show(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    override fun onCleared() {
        pause()
    }

    private companion object {
        const val MAX_DIGITS = 4
        const val ENTRY_TIMEOUT_MS = 2_000L
        const val NOTICE_MS = 3_000L
    }
}
