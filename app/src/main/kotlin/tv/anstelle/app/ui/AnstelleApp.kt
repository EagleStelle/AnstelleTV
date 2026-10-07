package tv.anstelle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.NavigationDrawerItem
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.feature.dashboard.DashboardScreen
import tv.anstelle.feature.guide.GuideScreen
import tv.anstelle.feature.ondemand.ItemScreen
import tv.anstelle.feature.ondemand.OnDemandScreen
import tv.anstelle.feature.ondemand.ShowScreen
import tv.anstelle.feature.player.OnDemandPlayerScreen
import tv.anstelle.feature.player.PlayerScreen
import tv.anstelle.feature.profiles.ProfilePickerScreen
import tv.anstelle.feature.settings.SettingsScreen
import tv.anstelle.feature.settings.channels.AddToChannelScreen
import tv.anstelle.feature.settings.channels.ChannelDetailScreen
import tv.anstelle.feature.settings.channels.ChannelGroupsScreen
import tv.anstelle.feature.settings.channels.ChannelLineupScreen
import tv.anstelle.feature.settings.channels.ChannelNumbersScreen
import tv.anstelle.feature.settings.channels.RemovedChannelsScreen
import tv.anstelle.feature.settings.channels.ChannelsScreen
import tv.anstelle.feature.settings.playback.LanguageList
import tv.anstelle.feature.settings.playback.LanguagePickerScreen
import tv.anstelle.feature.settings.playback.PlaybackLanguagesScreen
import tv.anstelle.feature.settings.servers.AddServerScreen
import tv.anstelle.feature.settings.servers.ServerDetailScreen
import tv.anstelle.feature.settings.servers.ServersScreen

@Composable
fun AnstelleApp(isTv: Boolean) {
    AnstelleTheme(isTv = isTv) {
        val startup: StartupViewModel = hiltViewModel()
        val state by startup.state.collectAsStateWithLifecycle()
        val background = LocalAnstelleTheme.current.background

        // Test tags double as resource ids, so macrobenchmarks can find the guide grid.
        Box(Modifier.fillMaxSize().background(background).semantics { testTagsAsResourceId = true }) {
            val ready = state as? StartupState.Ready ?: return@Box
            val navController = rememberNavController()
            val startDestination: Any = if (ready.showProfilePicker) ProfilesRoute else GuideRoute
            val backStackEntry by navController.currentBackStackEntryAsState()
            val destination = backStackEntry?.destination
            val selected = TopLevelDestination.entries.firstOrNull { top ->
                destination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
            }
            val fullScreen = destination?.hasRoute(PlayerRoute::class) == true ||
                destination?.hasRoute(OnDemandPlayRoute::class) == true ||
                (destination?.hasRoute(ProfilesRoute::class) == true && ready.showProfilePicker && selected == null)

            val content: @Composable (Modifier) -> Unit = { modifier ->
                AppNavHost(navController, startDestination, modifier)
            }
            when {
                fullScreen -> content(Modifier.fillMaxSize())
                isTv -> TvShell(selected, onNavigate = { navController.navigateTopLevel(it) }, content = content)
                else -> PhoneShell(selected, onNavigate = { navController.navigateTopLevel(it) }, content = content)
            }
            PairingOverlay()
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, startDestination: Any, modifier: Modifier) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable<ProfilesRoute> {
            ProfilePickerScreen(onProfileSelected = { navController.navigateTopLevel(TopLevelDestination.Guide) })
        }
        composable<GuideRoute> {
            GuideScreen(
                onWatch = { navController.navigate(PlayerRoute(it)) },
                onPlayOnDemand = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                onAddToChannel = { navController.navigate(AddToChannelRoute(it)) },
                onOpenSettings = { navController.navigateTopLevel(TopLevelDestination.Settings) },
            )
        }
        navigation<OnDemandGraph>(startDestination = OnDemandRoute) {
            composable<OnDemandRoute> {
                OnDemandScreen(
                    onOpenShow = { navController.navigate(OnDemandShowRoute(it)) },
                    onOpenItem = { navController.navigate(OnDemandItemRoute(it)) },
                )
            }
            composable<OnDemandShowRoute> {
                ShowScreen(
                    onOpenItem = { navController.navigate(OnDemandItemRoute(it)) },
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                    onAddToChannel = { navController.navigate(AddToChannelRoute(it)) },
                    onGone = { navController.popBackStack() },
                )
            }
            composable<OnDemandItemRoute> {
                ItemScreen(
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                    onAddToChannel = { navController.navigate(AddToChannelRoute(it)) },
                    onOpenShow = { seriesId ->
                        // Back to the show's page when it is right behind this one.
                        navController.navigate(OnDemandShowRoute(seriesId)) { popUpTo<OnDemandShowRoute> { inclusive = true } }
                    },
                    onGone = { navController.popBackStack() },
                )
            }
        }
        composable<OnDemandPlayRoute> { OnDemandPlayerScreen() }
        composable<AddToChannelRoute> { AddToChannelScreen(onGone = { navController.popBackStack() }) }
        composable<DashboardRoute> { DashboardScreen() }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenServers = { navController.navigate(ServersRoute) },
                    onOpenChannels = { navController.navigate(ChannelsRoute) },
                    onOpenLanguages = { navController.navigate(PlaybackLanguagesRoute) },
                )
            }
            composable<PlaybackLanguagesRoute> {
                PlaybackLanguagesScreen(onAddLanguage = { navController.navigate(LanguagePickerRoute(subtitles = it == LanguageList.SUBTITLES)) })
            }
            composable<LanguagePickerRoute> { LanguagePickerScreen(onPicked = { navController.popBackStack() }) }
            composable<ChannelsRoute> {
                ChannelsScreen(
                    onOpenChannel = { navController.navigate(ChannelDetailRoute(it)) },
                    onNewChannel = { navController.navigate(ChannelLineupRoute()) },
                    onOpenRemoved = { navController.navigate(RemovedChannelsRoute) },
                    onOpenNumbers = { navController.navigate(ChannelNumbersRoute) },
                    onOpenGroups = { navController.navigate(ChannelGroupsRoute) },
                )
            }
            composable<ChannelDetailRoute> {
                ChannelDetailScreen(
                    onWatch = { navController.navigate(PlayerRoute(it)) },
                    onEditLineup = { navController.navigate(ChannelLineupRoute(it)) },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable<ChannelLineupRoute> {
                ChannelLineupScreen(
                    onSaved = { channelId, isNew ->
                        if (isNew) {
                            // Land on the new channel's page, with the channel list behind it.
                            navController.navigate(ChannelDetailRoute(channelId)) { popUpTo<ChannelsRoute>() }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<RemovedChannelsRoute> { RemovedChannelsScreen() }
            composable<ChannelNumbersRoute> { ChannelNumbersScreen() }
            composable<ChannelGroupsRoute> { ChannelGroupsScreen() }
            composable<ServersRoute> {
                ServersScreen(
                    onAddServer = { navController.navigate(AddServerRoute()) },
                    onOpenServer = { navController.navigate(ServerDetailRoute(it)) },
                )
            }
            composable<ServerDetailRoute> {
                ServerDetailScreen(
                    onSignIn = { navController.navigate(AddServerRoute(it)) },
                    onRemoved = { navController.popBackStack() },
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    onSignedIn = { serverId ->
                        // Land on the server's page, with the server list behind it.
                        navController.navigate(ServerDetailRoute(serverId)) {
                            popUpTo<ServersRoute>()
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<PlayerRoute> { PlayerScreen() }
    }
}

private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Phone and tablet: Material 3 navigation bar. */
@Composable
private fun PhoneShell(
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = LocalAnstelleTheme.current.background,
        bottomBar = {
            androidx.compose.material3.NavigationBar {
                for (destination in TopLevelDestination.entries) {
                    NavigationBarItem(
                        selected = destination == selected,
                        onClick = { onNavigate(destination) },
                        icon = { androidx.compose.material3.Icon(destination.icon, contentDescription = null) },
                        label = { androidx.compose.material3.Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/** TV: Compose-for-TV navigation drawer that expands when it receives D-pad focus. */
@Composable
private fun TvShell(
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val requesters = remember { TopLevelDestination.entries.associateWith { FocusRequester() } }
    androidx.tv.material3.NavigationDrawer(
        drawerContent = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(LocalAnstelleTheme.current.surface)
                    // Coming in from the screen, focus lands on the open destination, wherever the D-pad entered.
                    .focusProperties { onEnter = { selected?.let { requesters.getValue(it).requestFocus() } } }
                    .focusGroup()
                    .padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                for (destination in TopLevelDestination.entries) {
                    NavigationDrawerItem(
                        selected = destination == selected,
                        onClick = { onNavigate(destination) },
                        leadingContent = { androidx.tv.material3.Icon(destination.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(destination)),
                    ) {
                        androidx.tv.material3.Text(destination.label)
                    }
                }
            }
        },
    ) {
        content(Modifier.fillMaxSize())
    }
}
