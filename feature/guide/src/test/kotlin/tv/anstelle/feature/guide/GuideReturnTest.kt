package tv.anstelle.feature.guide

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tv.anstelle.core.common.TimeSource
import tv.anstelle.core.data.DatabaseChanges
import tv.anstelle.core.data.ProfileRepository
import tv.anstelle.core.data.Settings
import tv.anstelle.core.data.SettingsRepository
import tv.anstelle.core.data.channels.ChannelRepository
import tv.anstelle.core.data.channels.ChannelSummary
import tv.anstelle.core.data.guide.GuideRepository
import tv.anstelle.core.data.playback.ChannelPlayer
import tv.anstelle.core.data.playback.PlaybackState
import tv.anstelle.core.database.dao.SettingDao
import tv.anstelle.core.database.entity.SettingEntity
import tv.anstelle.core.model.Channel
import tv.anstelle.core.model.ChannelKind
import tv.anstelle.core.model.Playback
import tv.anstelle.core.model.PlaybackOrder
import tv.anstelle.core.model.Profile
import tv.anstelle.core.model.ProfileAccess
import tv.anstelle.core.model.ProfileRole
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GuideReturnTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val channels = (1L..3L).map { id ->
        Channel(id, id.toInt(), "Channel $id", null, ChannelKind.GENERATED,
            Playback(PlaybackOrder.SHUFFLE), Playback(PlaybackOrder.SHUFFLE),
            moviesOncePerDay = false, isHidden = false, isFavorite = id == 1L,
            groupId = 1, fromLibrary = false)
    }
    private val summaries = channels.map { ChannelSummary(it, "Group", null, inSeason = true) }

    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private class Fixture(
        val model: GuideViewModel,
        val playback: ChannelPlayer,
        val playing: MutableStateFlow<PlaybackState>,
        val lineup: MutableStateFlow<List<ChannelSummary>>,
        val settings: SettingDao,
    )

    private suspend fun fixture(loaded: Boolean = true, playingIndex: Int? = 1, last: Long = 0): Fixture {
        val profiles = mock(ProfileRepository::class.java)
        val repository = mock(ChannelRepository::class.java)
        val playback = mock(ChannelPlayer::class.java)
        val changes = mock(DatabaseChanges::class.java)
        val dao = mock(SettingDao::class.java)
        val lineup = MutableStateFlow(if (loaded) summaries else emptyList())
        val playing = MutableStateFlow(PlaybackState(channel = playingIndex?.let(channels::get)))
        `when`(profiles.activeProfile()).thenReturn(Profile(1, "Viewer", null, ProfileRole.OWNER, null, false, null))
        `when`(profiles.observeAccess(1)).thenReturn(flowOf(ProfileAccess.OPEN))
        `when`(profiles.tunable(summaries, ProfileAccess.OPEN)).thenReturn(channels)
        `when`(profiles.tunable(emptyList(), ProfileAccess.OPEN)).thenReturn(emptyList())
        `when`(repository.observeChannels()).thenReturn(lineup)
        `when`(repository.observeGroups()).thenReturn(flowOf(emptyList()))
        `when`(playback.state).thenReturn(playing)
        `when`(playback.player).thenReturn(MutableStateFlow<Player?>(null))
        `when`(changes.tables).thenReturn(MutableSharedFlow())
        `when`(dao.get(Settings.lastChannel(1).name)).thenReturn(last.toString())
        val model = GuideViewModel(profiles, repository, mock(GuideRepository::class.java),
            SettingsRepository(dao), playback, TimeSource { 0L }, changes)
        store.put("guide", model)
        return Fixture(model, playback, playing, lineup, dao)
    }

    @Test fun everyReturnFollowsUnchangedPlaybackWithoutRepeatingWhileBrowsing() = runTest(dispatcher) {
        val f = fixture()
        runCurrent()
        repeat(3) {
            f.model.startPreview()
            runCurrent()
            assertEquals(2L, f.model.follow.value)
            f.model.followed()
            f.model.startPreview()
            runCurrent()
            assertNull(f.model.follow.value)
            f.model.preview(3)
            runCurrent()
            assertNull(f.model.follow.value)
            f.model.stopPreview()
        }
    }

    @Test fun fullscreenReturnFollowsChannelChangedInPlayer() = runTest(dispatcher) {
        val f = fixture()
        runCurrent()
        f.model.startPreview(); runCurrent()
        f.model.followed()
        f.model.openingPlayer()
        f.model.stopPreview()
        f.playing.value = PlaybackState(channel = channels.last())
        f.model.startPreview(); runCurrent()
        assertEquals(3L, f.model.follow.value)
    }

    @Test fun delayedLineupStartsPreviewAndKeepsFollowPendingUntilConsumed() = runTest(dispatcher) {
        val f = fixture(loaded = false)
        f.model.startPreview(); runCurrent()
        assertNull(f.model.follow.value)
        verify(f.playback, never()).acquire()
        f.lineup.value = summaries
        runCurrent()
        assertEquals(2L, f.model.follow.value)
        f.model.selectTab(GuideViewModel.TAB_FAVORITES); runCurrent()
        assertEquals(listOf(1L), f.model.state.value.channels.map { it.id })
        assertEquals(2L, f.model.follow.value)
        // Browsing must not replace the playing channel while its return request is pending.
        f.model.preview(1)
        verify(f.playback, never()).tune(1)
        f.model.selectTab(GuideViewModel.TAB_ALL); runCurrent()
        assertEquals(2L, f.model.follow.value)
        verify(f.settings).put(SettingEntity(Settings.guideTab(1).name, GuideViewModel.TAB_ALL))
        f.model.followed()
        assertNull(f.model.follow.value)
    }

    @Test fun noPlaybackUsesLastWatchedThenFirstAvailableChannel() = runTest(dispatcher) {
        val last = fixture(playingIndex = null, last = 3)
        runCurrent(); last.model.startPreview(); runCurrent()
        assertEquals(3L, last.model.follow.value)
        val first = fixture(playingIndex = null, last = 99)
        runCurrent(); first.model.startPreview(); runCurrent()
        assertEquals(1L, first.model.follow.value)
    }

    @Test fun previewReadiesTheRowsAroundTheOneBrowsedTo() = runTest(dispatcher) {
        val f = fixture(playingIndex = 2)
        runCurrent(); f.model.startPreview(); runCurrent(); f.model.followed()
        f.model.preview(2)
        verify(f.playback).tune(2)
        verify(f.playback).prewarm(listOf(1L, 3L))
        f.playing.value = PlaybackState(channel = channels.first())
        f.model.preview(2)
        verify(f.playback).prewarm(listOf(3L, 1L))
    }

    @Test fun neighbourRowsStopAtEitherEndAndGoDownWithoutADirection() {
        val rows = channels.map { GuideChannel(it.id, it.number, it.name, null, it.isFavorite) }
        assertEquals(listOf(2L), neighbourRows(rows, from = null, to = 1))
        assertEquals(listOf(2L), neighbourRows(rows, from = 2, to = 3))
        assertEquals(listOf(2L), neighbourRows(rows, from = 2, to = 1))
        assertEquals(emptyList<Long>(), neighbourRows(rows, from = 1, to = 99))
    }

    @Test fun leavingBeforeStartupFinishesCannotTuneOrEmitAStaleFollow() = runTest(dispatcher) {
        val f = fixture()
        runCurrent()
        f.model.startPreview()
        f.model.stopPreview()
        runCurrent()
        assertNull(f.model.follow.value)
        verify(f.playback, never()).tune(2)
        f.model.startPreview(); runCurrent()
        assertEquals(2L, f.model.follow.value)
    }
}
