package tv.anstelle.feature.ondemand

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.anstelle.core.data.ondemand.EpisodeEntry
import tv.anstelle.core.data.Artwork
import tv.anstelle.core.data.ondemand.CatalogKind
import tv.anstelle.core.data.ondemand.CatalogWatch
import tv.anstelle.core.data.ondemand.SearchFilters
import tv.anstelle.core.data.ondemand.SearchResults
import tv.anstelle.core.data.ondemand.Title
import tv.anstelle.core.data.ondemand.WatchMarks
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.components.FocusSurface
import tv.anstelle.core.theme.components.LocalRailFocus
import tv.anstelle.core.theme.components.RailFocus
import tv.anstelle.core.theme.components.enterContentFromRail
import tv.anstelle.core.theme.components.requestWhenReady

@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    @Test fun verticalMovesRememberEachRowsOffscreenItem() {
        compose.setContent { Browse() }
        press(Key.DirectionDown)
        press(Key.DirectionRight, 8)
        compose.onNodeWithTag("0/8").assertIsFocused()
        press(Key.DirectionDown)
        press(Key.DirectionRight, 3)
        press(Key.DirectionUp)
        compose.onNodeWithTag("0/8").assertIsFocused()
        press(Key.PageDown)
        compose.onNodeWithTag("1/3").assertIsFocused()
        press(Key.ChannelUp)
        compose.onNodeWithTag("0/8").assertIsFocused()
    }

    @Test fun headerRailAndRowEdgesHaveExplicitDestinations() {
        compose.setContent { Browse() }
        press(Key.DirectionUp)
        compose.onNodeWithTag("header").assertIsFocused()
        press(Key.DirectionDown, 2)
        press(Key.DirectionLeft)
        compose.onNodeWithTag("rail").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithTag("header").assertIsFocused()
        press(Key.DirectionDown)
        repeat(16) { index ->
            press(Key.DirectionRight)
            compose.onNodeWithTag("0/${(index + 1).coerceAtMost(15)}").assertIsFocused()
        }
        compose.onNodeWithTag("0/15").assertIsFocused()
        press(Key.DirectionDown, 8)
        compose.onNodeWithTag("6/0").assertIsFocused()
    }

    @Test fun focusHoldsTheRowAtTheTopAndItsCardOnTheKeyline() {
        compose.setContent { Browse() }
        press(Key.DirectionDown, 2)
        press(Key.DirectionRight, 3)
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row/1").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("1/3").assertIsFocused()
            .assertTopPositionInRootIsEqualTo(page.top)
            .assertLeftPositionInRootIsEqualTo(row.left)
    }

    @Test fun returningFromChildRestoresTheSameLazyCard() {
        val shown = mutableStateOf(true)
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            if (shown.value) saved.SaveableStateProvider("browse") { Browse() }
        }
        press(Key.DirectionDown, 5)
        press(Key.DirectionRight, 9)
        compose.onNodeWithTag("4/9").assertIsFocused()
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("4/9").assertIsFocused()
    }

    @Test fun rowsWithoutABannerStillTakeInitialFocus() {
        compose.setContent { Browse(banner = false) }
        compose.onNodeWithTag("0/0").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun seasonAndEpisodeMovesKeepTheirPlace() {
        compose.setContent { Detail() }
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        compose.onNodeWithText("Season 1", substring = true).assertIsFocused()
        press(Key.DirectionRight, 2)
        compose.onNodeWithText("Season 2", substring = true).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        compose.onNodeWithText("S2E1").assertIsFocused()
        press(Key.DirectionRight, 4)
        compose.onNodeWithText("Episode 5").assertIsFocused()
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        compose.onNodeWithText("Episode 5").assertIsFocused()
        press(Key.DirectionUp, 2)
        compose.onNodeWithText("SECOND").assertIsFocused()
    }

    @Test fun singleSeasonSkipsTheNoninteractiveHeading() {
        compose.setContent { Detail(seasonCount = 1) }
        press(Key.DirectionDown)
        compose.onNodeWithText("Episode 1").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText("PLAY").assertIsFocused()
    }

    @Test fun openingAnEpisodeAndReturningRestoresItsFocus() {
        val shown = mutableStateOf(true)
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            if (shown.value) saved.SaveableStateProvider("detail") { Detail(onOpen = { shown.value = false }) }
        }
        press(Key.DirectionDown, 2)
        press(Key.DirectionRight, 4)
        press(Key.DirectionCenter)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("Episode 5").assertIsFocused()
    }

    @Test fun returningFromPlaybackRestoresTheWatchKey() {
        val shown = mutableStateOf(true)
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            if (shown.value) saved.SaveableStateProvider("detail") { Detail(onOpen = { shown.value = false }) }
        }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("SECOND").assertIsFocused()
    }

    @Test fun railRevealsDetailControlsAfterScrollingToEpisodes() {
        compose.setContent {
            AnstelleTheme(isTv = true) {
                val rail = remember { RailFocus() }
                CompositionLocalProvider(LocalRailFocus provides rail) {
                    Row {
                        FocusSurface({}, Modifier.size(60.dp).testTag("rail").focusRequester(rail.rail)
                            .enterContentFromRail(rail)) { Text("Rail") }
                        Detail()
                    }
                }
            }
        }
        press(Key.DirectionDown, 2)
        press(Key.DirectionLeft)
        compose.onNodeWithTag("rail").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("PLAY").assertIsFocused()
    }

    @Test fun searchMovesFromResultsThroughFiltersToHeader() {
        compose.setContent { Search(query = "movie") }
        press(Key.DirectionDown)
        compose.onNodeWithText("Movie 1").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText("SORT BY").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithTag("header").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithText("SORT BY").assertIsFocused()
    }

    @Test fun recentlyAddedMovesDirectlyBetweenResultsAndHeader() {
        compose.setContent { Search(query = "") }
        press(Key.DirectionDown)
        compose.onNodeWithText("Movie 1").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithTag("header").assertIsFocused()
    }

    @Composable
    private fun Browse(banner: Boolean = true) {
        RemoteInput()
        AnstelleTheme(isTv = true) {
            val rail = remember { RailFocus() }
            CompositionLocalProvider(LocalRailFocus provides rail) {
                val header = remember { FocusRequester() }
                val focus = remember { TabFocus(header, autoFocus = true) }
                TvRailEntry(header)
                val specs = buildList {
                    if (banner) add(TvBrowseRow("banner", listOf("banner"), scrollable = false))
                    repeat(7) { add(TvBrowseRow(it.toString(), (0..15).map(Int::toString))) }
                }
                val navigation = rememberTvBrowseNavigation(specs, focus)
                Row(Modifier.fillMaxSize()) {
                    FocusSurface({}, Modifier.size(60.dp).testTag("rail").focusRequester(rail.rail)
                        .enterContentFromRail(rail)) { Text("Rail") }
                    Column {
                        FocusSurface({}, Modifier.height(48.dp).fillMaxWidth().testTag("header").focusRequester(header)
                            .onPreviewKeyEvent {
                                when (it.key) {
                                    Key.DirectionDown -> {
                                        if (it.type == KeyEventType.KeyDown) focus.enter?.invoke()
                                        true
                                    }
                                    Key.DirectionUp -> true
                                    else -> false
                                }
                            }) { Text("Header") }
                        BrowseList(navigation, Modifier.height(260.dp)) {
                            for (spec in specs) {
                                item(key = spec.key) {
                                    val row = navigation.row(spec.key)
                                    if (!spec.scrollable) {
                                        RotatingBanner(2, { null }, {}, Modifier.tvFocusItem(row, "banner", horizontalEdges = false).testTag("banner")) { Text("Slide $it") }
                                    } else {
                                        FocusRow(row, padding = 0.dp, spacing = 0.dp, modifier = Modifier.testTag("row/${spec.key}")) {
                                            items(spec.items, key = { it }) { id ->
                                                FocusSurface({}, Modifier.tvFocusItem(row, id).size(90.dp).testTag("${spec.key}/$id")) { Text(id) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Detail(seasonCount: Int = 2, onOpen: () -> Unit = {}) {
        RemoteInput()
        AnstelleTheme(isTv = true) {
            val list = rememberLazyListState()
            val returnFocus = remember { FocusRequester() }
            var opened by rememberSaveable { mutableStateOf<Long?>(null) }
            val first = rememberProgrammeFocus(Unit, list, opened, returnFocus, hasSynopsis = true)
            val episodes = remember {
                (1..seasonCount).flatMap { season ->
                    (1..10).map { number ->
                        EpisodeEntry(season * 100L + number, "$season/$number", season,
                            "S${season}E$number - Episode $number", null, null, null, 60_000)
                    }
                }
            }
            val seasons = episodes.groupBy { it.seasonLabel }
            var season by rememberSaveable { mutableStateOf("Season 1") }
            val navigation = rememberEpisodeNavigation(list, true, seasonCount, first)
            Page(null, hero = {
                WatchKeys(listOf("Play", "Second").map { SheetAction(it, Icons.Filled.PlayArrow) { onOpen() } }, list, first,
                    navigation = navigation, onFocused = { opened = null })
            }, listState = list) {
                item("synopsis") { Synopsis("Synopsis. ".repeat(120)) }
                item("episodes-heading") { EpisodesHeading(seasons, season, WatchMarks(), navigation = navigation) { season = it } }
                item("episodes") {
                    EpisodeCarousel(seasons.getValue(season), WatchMarks(), opened, { opened = it.id; onOpen() }, {},
                        returnTo = opened, returnFocus = returnFocus, navigation = navigation)
                }
            }
        }
    }

    @Composable
    private fun Search(query: String) {
        RemoteInput()
        AnstelleTheme(isTv = true) {
            val header = remember { FocusRequester() }
            val focus = remember { TabFocus(header, autoFocus = false) }
            val titles = remember {
                (1L..30L).map { id -> Title(id, 1, "$id", CatalogKind.MOVIES, "Movie $id", "movie $id",
                    null, null, emptyList(), null, 60_000, null, Artwork.None, 0) }
            }
            LaunchedEffect(Unit) { header.requestWhenReady() }
            Column(Modifier.fillMaxSize()) {
                FocusSurface({}, Modifier.height(48.dp).fillMaxWidth().testTag("header").focusRequester(header)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionDown) {
                            if (it.type == KeyEventType.KeyDown) focus.enter?.invoke()
                            true
                        } else false
                    }) { Text("Header") }
                SearchTab(query, SearchFilters(), {}, SearchResults(titles, titles.size), CatalogWatch(),
                    CatalogActions({}, {}, { _, _ -> }, { _, _ -> }, null), focus)
            }
        }
    }

    @Composable
    private fun RemoteInput() {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
    }
}
