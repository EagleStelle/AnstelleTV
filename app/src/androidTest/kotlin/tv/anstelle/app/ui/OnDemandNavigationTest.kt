package tv.anstelle.app.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDemandNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun navigation(test: NavHostController.() -> Unit) = instrumentation.runOnMainSync {
        val store = ViewModelStore()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val controller = NavHostController(instrumentation.targetContext).apply {
            setLifecycleOwner(owner)
            setViewModelStore(store)
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = GuideRoute) {
                composable<GuideRoute> {}
                composable<DashboardRoute> {}
                composable<ProfilesRoute> {}
                composable<ProfileEditRoute> {}
                navigation<OnDemandGraph>(startDestination = OnDemandRoute) {
                    composable<OnDemandRoute> {}
                    composable<OnDemandTitleRoute> {}
                    composable<OnDemandEpisodeRoute> {}
                    composable<OnDemandChannelRoute> {}
                }
                navigation<SettingsGraph>(startDestination = SettingsRoute) {
                    composable<SettingsRoute> {}
                    composable<ThemesRoute> {}
                    composable<ThemeEditorRoute> {}
                }
                composable<PlayerRoute> {}
                composable<OnDemandPlayRoute> {}
            }
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        try {
            controller.test()
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    private fun livePlaybackReturnsToCatalog(restart: Boolean) = navigation {
        navigateTopLevel(TopLevelDestination.OnDemand)
        navigate(OnDemandChannelRoute(20))
        navigate(PlayerRoute(20, restart = restart))

        // The live player's Back handler always returns to the guide.
        navigateTopLevel(TopLevelDestination.Guide)
        assertTrue(currentDestination!!.hasRoute(GuideRoute::class))

        navigateTopLevel(TopLevelDestination.OnDemand)
        assertTrue(currentDestination!!.hasRoute(OnDemandRoute::class))
        popBackStack()
        assertTrue(currentDestination!!.hasRoute(GuideRoute::class))
    }

    @Test
    fun watchLiveThenBackDoesNotRestorePlayerFromOnDemandTab() = livePlaybackReturnsToCatalog(restart = false)

    @Test
    fun startOverThenBackDoesNotRestorePlayerFromOnDemandTab() = livePlaybackReturnsToCatalog(restart = true)

    @Test
    fun switchingTabsWhileBrowsingReturnsToTheCatalog() = navigation {
        navigateTopLevel(TopLevelDestination.OnDemand)
        navigate(OnDemandChannelRoute(20, 123_000))

        navigateTopLevel(TopLevelDestination.Guide)
        navigateTopLevel(TopLevelDestination.OnDemand)

        assertTrue(currentDestination!!.hasRoute(OnDemandRoute::class))
        popBackStack()
        assertTrue(currentDestination!!.hasRoute(GuideRoute::class))
    }

    @Test
    fun everyTabOpensAFreshRootFromEveryOtherTabAndItself() = navigation {
        val roots = mapOf(
            TopLevelDestination.Guide to GuideRoute,
            TopLevelDestination.OnDemand to OnDemandRoute,
            TopLevelDestination.Dashboard to DashboardRoute,
            TopLevelDestination.Settings to SettingsRoute,
            TopLevelDestination.Profiles to ProfilesRoute(),
        )
        for (source in TopLevelDestination.entries) {
            for ((target, root) in roots) {
                navigateTopLevel(target)
                val previous = currentBackStackEntry!!
                previous.savedStateHandle["position"] = 42
                navigateTopLevel(source)
                when (source) {
                    TopLevelDestination.OnDemand -> {
                        navigate(OnDemandTitleRoute(100))
                        navigate(OnDemandEpisodeRoute(4))
                    }
                    TopLevelDestination.Settings -> {
                        navigate(ThemesRoute())
                        navigate(ThemeEditorRoute(1))
                    }
                    TopLevelDestination.Profiles -> navigate(ProfileEditRoute(1))
                    else -> Unit
                }

                navigateTopLevel(target)

                val current = currentBackStackEntry!!
                val scenario = "${source.label} to ${target.label}"
                assertTrue(scenario, current.destination.hasRoute(root::class))
                assertNotSame(scenario, previous, current)
                assertFalse(scenario, current.savedStateHandle.contains("position"))
                if (target != TopLevelDestination.Guide) {
                    assertTrue(scenario, popBackStack())
                    assertTrue(scenario, currentDestination!!.hasRoute(GuideRoute::class))
                }
                assertFalse(scenario, popBackStack())
                // Restore the guide after verifying there are no stale screens behind the root.
                navigate(GuideRoute)
            }
        }
    }

    @Test
    fun guidePlaybackThenBackLeavesOnDemandAtCatalog() = navigation {
        navigate(PlayerRoute(20))

        navigateTopLevel(TopLevelDestination.Guide)
        assertTrue(currentDestination!!.hasRoute(GuideRoute::class))
        navigateTopLevel(TopLevelDestination.OnDemand)
        assertTrue(currentDestination!!.hasRoute(OnDemandRoute::class))
    }

    @Test
    fun advancedEpisodeReplacesLaunchEpisodeWithoutLeavingStalePages() = navigation {
        navigate(OnDemandRoute)
        navigate(OnDemandTitleRoute(100))
        navigate(OnDemandEpisodeRoute(4))
        val playing = OnDemandPlayRoute(4)
        navigate(playing)

        leaveOnDemandPlayer(playing, 7, true)

        assertEquals(OnDemandEpisodeRoute(7), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertEquals(OnDemandTitleRoute(100), currentBackStackEntry!!.toRoute<OnDemandTitleRoute>())
    }

    @Test
    fun unchangedEpisodeReusesItsPage() = navigation {
        navigate(OnDemandEpisodeRoute(4))
        val episode = currentBackStackEntry
        val playing = OnDemandPlayRoute(4)
        navigate(playing)

        leaveOnDemandPlayer(playing, 4, true)

        assertSame(episode, currentBackStackEntry)
    }

    @Test
    fun catalogPlaybackReturnsToCurrentEpisodeThenCatalog() = navigation {
        navigate(OnDemandRoute)
        val catalog = currentBackStackEntry
        val playing = OnDemandPlayRoute(4)
        navigate(playing)

        leaveOnDemandPlayer(playing, 7, true)

        assertEquals(OnDemandEpisodeRoute(7), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertSame(catalog, currentBackStackEntry)
    }

    @Test
    fun guidePlaybackReturnsToCurrentEpisodeWithChannelContext() = navigation {
        val guide = currentBackStackEntry
        val playing = OnDemandPlayRoute(4, channelId = 20, atUtcMs = 123_000)
        navigate(playing)

        leaveOnDemandPlayer(playing, 7, true)

        assertEquals(OnDemandEpisodeRoute(7, 20, 123_000), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertSame(guide, currentBackStackEntry)
    }

    @Test
    fun channelEpisodeReplacementPreservesOriginalParentAndAiring() = navigation {
        navigate(OnDemandChannelRoute(20, 123_000))
        val channel = currentBackStackEntry
        navigate(OnDemandEpisodeRoute(4, 20, 123_000))
        val playing = OnDemandPlayRoute(4, channelId = 20, atUtcMs = 123_000)
        navigate(playing)

        leaveOnDemandPlayer(playing, 7, true)
        leaveOnDemandPlayer(playing, 7, true)

        assertEquals(OnDemandEpisodeRoute(7, 20, 123_000), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertSame(channel, currentBackStackEntry)
    }

    @Test
    fun moviesKeepTheirExistingChannelReturn() = navigation {
        navigate(OnDemandChannelRoute(20, 123_000))
        val channel = currentBackStackEntry
        val playing = OnDemandPlayRoute(4, channelId = 20, atUtcMs = 123_000)
        navigate(playing)

        leaveOnDemandPlayer(playing, 4, false)

        assertSame(channel, currentBackStackEntry)
    }

    @Test
    fun backBeforePlaybackStartsKeepsOriginalEpisodePage() = navigation {
        navigate(OnDemandEpisodeRoute(4))
        val episode = currentBackStackEntry
        val playing = OnDemandPlayRoute(4)
        navigate(playing)

        leaveOnDemandPlayer(playing, 0, false)

        assertSame(episode, currentBackStackEntry)
    }

    @Test
    fun liveMovieDetailsEntersLibraryBrowsingWithoutRetainingPlayer() = navigation {
        navigate(PlayerRoute(1))

        openOnDemandDetails(42, false)

        assertEquals(OnDemandTitleRoute(42), currentBackStackEntry!!.toRoute<OnDemandTitleRoute>())
        popBackStack()
        assertEquals(OnDemandRoute, currentBackStackEntry!!.toRoute<OnDemandRoute>())
        popBackStack()
        assertEquals(GuideRoute, currentBackStackEntry!!.toRoute<GuideRoute>())
    }

    @Test
    fun liveEpisodeDetailsOpensCurrentEpisodeThenReturnsToCatalog() = navigation {
        navigate(PlayerRoute(1))

        openOnDemandDetails(43, true)

        assertEquals(OnDemandEpisodeRoute(43), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertEquals(OnDemandRoute, currentBackStackEntry!!.toRoute<OnDemandRoute>())
    }

    @Test
    fun guideAndChannelDetailsKeepTheirParentAndAvoidDuplicateEpisodePages() = navigation {
        val guide = currentBackStackEntry
        openOnDemandDetails(42, false)
        popBackStack()
        assertSame(guide, currentBackStackEntry)

        navigate(OnDemandChannelRoute(20))
        val channel = currentBackStackEntry
        openOnDemandDetails(43, true)
        openOnDemandDetails(43, true)
        assertEquals(OnDemandEpisodeRoute(43), currentBackStackEntry!!.toRoute<OnDemandEpisodeRoute>())
        popBackStack()
        assertSame(channel, currentBackStackEntry)
    }

    @Test
    fun goToSeriesReusesItsParentWithoutLeavingTheEpisodeBehind() = navigation {
        navigate(OnDemandRoute)
        navigate(OnDemandTitleRoute(100))
        val series = currentBackStackEntry
        navigate(OnDemandEpisodeRoute(43))

        openOnDemandSeries(100)

        assertSame(series, currentBackStackEntry)
        popBackStack()
        assertEquals(OnDemandRoute, currentBackStackEntry!!.toRoute<OnDemandRoute>())
    }

    @Test
    fun goToSeriesReplacesAnEpisodeOpenedDirectlyFromTheCatalog() = navigation {
        navigate(OnDemandRoute)
        navigate(OnDemandEpisodeRoute(43))

        openOnDemandSeries(100)

        assertEquals(OnDemandTitleRoute(100), currentBackStackEntry!!.toRoute<OnDemandTitleRoute>())
        popBackStack()
        assertEquals(OnDemandRoute, currentBackStackEntry!!.toRoute<OnDemandRoute>())
    }
}
