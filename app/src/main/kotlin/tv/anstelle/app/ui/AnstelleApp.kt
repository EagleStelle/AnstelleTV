package tv.anstelle.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.NavigationDrawerItem
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.scanlines

import tv.anstelle.core.theme.components.AnstelleMark
import tv.anstelle.core.theme.components.PageFocusScope
import tv.anstelle.core.theme.components.Wordmark
import tv.anstelle.feature.dashboard.DashboardScreen
import tv.anstelle.feature.guide.GuideScreen
import tv.anstelle.feature.ondemand.ChannelScreen
import tv.anstelle.feature.ondemand.EpisodeScreen
import tv.anstelle.feature.ondemand.OnDemandScreen
import tv.anstelle.feature.ondemand.TitleScreen
import tv.anstelle.feature.settings.OnDemandRowsScreen
import tv.anstelle.feature.settings.OnDemandSettingsScreen
import tv.anstelle.feature.player.OnDemandPlayerScreen
import tv.anstelle.feature.player.PlayerScreen
import tv.anstelle.feature.profiles.ProfileEditScreen
import tv.anstelle.feature.profiles.ProfilePickerScreen
import tv.anstelle.feature.settings.appearance.ThemeEditorScreen
import tv.anstelle.feature.settings.appearance.ThemesScreen
import tv.anstelle.feature.settings.AdminOnly
import tv.anstelle.feature.settings.BackupScreen
import tv.anstelle.feature.settings.SettingsScreen
import tv.anstelle.feature.settings.WebControlScreen
import tv.anstelle.feature.settings.channels.AddToChannelScreen
import tv.anstelle.feature.settings.channels.ChannelDetailScreen
import tv.anstelle.feature.settings.channels.ChannelGroupsScreen
import tv.anstelle.feature.settings.channels.ChannelLineupScreen
import tv.anstelle.feature.settings.channels.ChannelListScreen
import tv.anstelle.feature.settings.channels.ChannelsScreen
import tv.anstelle.feature.settings.playback.LanguageList
import tv.anstelle.feature.settings.playback.LanguagePickerScreen
import tv.anstelle.feature.settings.playback.PlaybackLanguagesScreen
import tv.anstelle.feature.settings.playback.WatchHistoryScreen
import tv.anstelle.feature.settings.servers.AddServerScreen
import tv.anstelle.feature.settings.servers.ServerDetailScreen
import tv.anstelle.feature.settings.servers.ServersScreen

/**
 * The app: the profile's theme, then the shell and its screens, built anew for each profile so
 * nothing of one profile's carries over. [onLightTheme] says whether the theme's ground is
 * light, for the system bars' icons.
 */
@Composable
fun AnstelleApp(isTv: Boolean, onLightTheme: (Boolean) -> Unit) {
    val startup: StartupViewModel = hiltViewModel()
    val session by startup.session.collectAsStateWithLifecycle()
    val needsServer by startup.needsServer.collectAsStateWithLifecycle()
    val dashboard by startup.dashboard.collectAsStateWithLifecycle()
    val theme by startup.theme.collectAsStateWithLifecycle()
    LaunchedEffect(theme.spec.isLight) { onLightTheme(theme.spec.isLight) }
    AnstelleTheme(isTv = isTv, theme = theme) {
        val effects = theme.spec.effects
        // Test tags double as resource ids, so macrobenchmarks can find the guide grid.
        Box(
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .scanlines(if (effects.scanlines) effects.scanlineAlpha else 0f)
                .semantics { testTagsAsResourceId = true },
        ) {
            if (needsServer) {
                FirstServer(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
                return@Box
            }
            val current = session ?: return@Box
            key(current) {
                val navController = rememberNavController()
                val startDestination: Any = if (current.launchPicker) ProfilesRoute(atLaunch = true) else GuideRoute
                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination
                val selected = TopLevelDestination.entries.firstOrNull { top ->
                    destination?.hierarchy?.any { it.hasRoute(top.route::class) } == true
                }
                val fullScreen = destination?.hasRoute(PlayerRoute::class) == true ||
                    destination?.hasRoute(OnDemandPlayRoute::class) == true ||
                    (destination?.hasRoute(ProfilesRoute::class) == true && current.launchPicker)

                // Each profile's own dashboard, unless it turned it off.
                val destinations = TopLevelDestination.entries.filter { it != TopLevelDestination.Dashboard || dashboard }
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    AppNavHost(navController, startDestination, current.canManageApp, onLaunchPickerDone = startup::pickerDone, modifier = modifier)
                }
                when {
                    fullScreen -> content(Modifier.fillMaxSize())
                    isTv -> TvShell(destinations, if (current.launchPicker) null else selected, onNavigate = { navController.navigateTopLevel(it) }, content = content)
                    else -> PhoneShell(destinations, if (current.launchPicker) null else selected, onNavigate = { navController.navigateTopLevel(it) }, content = content)
                }
            }
            PairingOverlay()
        }
    }
}

/**
 * Before any profile exists: connecting the first server, full screen. Its user becomes the
 * owner, and the app opens on that profile. A backup copied in instead starts the app over on it.
 */
@Composable
private fun FirstServer(modifier: Modifier) {
    val activity = LocalActivity.current
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = AddServerRoute(), modifier = modifier) {
        composable<AddServerRoute> {
            AddServerScreen(
                onSignedIn = {},
                onBack = { activity?.finish() },
                trail = emptyList(),
                onRestore = { navController.navigate(BackupRoute(firstRun = true)) },
            )
        }
        composable<BackupRoute> { BackupScreen() }
    }
}

/**
 * An app-wide destination (servers, channels, web control): only the owner and admins see
 * a way in. A member who lands on one anyway goes back.
 */
private inline fun <reified T : Any> NavGraphBuilder.adminOnly(
    navController: NavHostController,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) = page<T> { entry -> AdminOnly(onLeave = { navController.leave() }) { content(entry) } }

/** A menu screen: coming back to it, focus lands on the row it was left on (see [PageFocusScope]). */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) =
    composable<T> { entry -> PageFocusScope { content(entry) } }

private fun NavHostController.leave() {
    if (!popBackStack()) navigateTopLevel(TopLevelDestination.Guide)
}

/** [canManageApp]: the owner and admins get the ways into app-wide settings; members don't see them. */
@Composable
private fun AppNavHost(navController: NavHostController, startDestination: Any, canManageApp: Boolean, onLaunchPickerDone: () -> Unit, modifier: Modifier) {
    val addToChannel: ((Long) -> Unit)? = if (canManageApp) ({ navController.navigate(AddToChannelRoute(it)) }) else null
    val openSettings: (() -> Unit)? = if (canManageApp) ({ navController.navigateTopLevel(TopLevelDestination.Settings) }) else null
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable<ProfilesRoute> { entry ->
            val atLaunch = entry.toRoute<ProfilesRoute>().atLaunch
            ProfilePickerScreen(
                atLaunch = atLaunch,
                // Another profile rebuilds every screen anyway; the same one leaves the launch picker.
                onProfileSelected = { if (atLaunch) onLaunchPickerDone() else navController.navigateTopLevel(TopLevelDestination.Guide) },
                onEditProfile = { navController.navigate(ProfileEditRoute(it)) },
                onAddFromJellyfin = { navController.navigate(AddServerRoute(it, newProfile = true)) },
            )
        }
        // The page itself asks for the profile's own PIN and closes on a profile the viewer may not change.
        page<ProfileEditRoute> {
            ProfileEditScreen(
                onOpenThemes = { navController.navigate(ThemesRoute(it)) },
                onLinkAccount = { profileId, serverId -> navController.navigate(AddServerRoute(serverId, linkProfileId = profileId)) },
                onGone = { navController.popBackStack() },
            )
        }
        composable<GuideRoute> {
            GuideScreen(
                onWatch = { navController.navigate(PlayerRoute(it)) },
                onStartOver = { navController.navigate(PlayerRoute(it, restart = true)) },
                onPlayOnDemand = { itemId, position, channelId, atUtcMs -> navController.navigate(OnDemandPlayRoute(itemId, position, channelId, atUtcMs)) },
                onAddToChannel = addToChannel,
                onOpenSettings = openSettings,
            )
        }
        navigation<OnDemandGraph>(startDestination = OnDemandRoute) {
            composable<OnDemandRoute> {
                OnDemandScreen(
                    onOpenChannel = { navController.navigate(OnDemandChannelRoute(it)) },
                    onOpenTitle = { navController.navigate(OnDemandTitleRoute(it)) },
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                    onOpenSettings = openSettings,
                    onAddToChannel = addToChannel,
                )
            }
            composable<OnDemandTitleRoute> {
                // Like a channel's page: never back to wherever it was opened from, always the on-demand main page.
                BackHandler {
                    if (!navController.popBackStack(OnDemandRoute, inclusive = false)) {
                        navController.navigate(OnDemandRoute) { popUpTo<OnDemandTitleRoute> { inclusive = true } }
                    }
                }
                TitleScreen(
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                    onOpenEpisode = { navController.navigate(OnDemandEpisodeRoute(it)) },
                    onOpenChannel = { navController.navigate(OnDemandChannelRoute(it)) },
                    onAddToChannel = addToChannel,
                    onGone = { navController.popBackStack() },
                )
            }
            // Unlike the other media pages, Back from an episode returns to the page that opened it (user decision).
            composable<OnDemandEpisodeRoute> { entry ->
                val page = entry.toRoute<OnDemandEpisodeRoute>()
                EpisodeScreen(
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position, page.channelId, page.atUtcMs)) },
                    // Another episode takes this one's place, so Back still returns to the show's or channel's page.
                    onOpenEpisode = {
                        navController.navigate(OnDemandEpisodeRoute(it, page.channelId, page.atUtcMs)) { popUpTo<OnDemandEpisodeRoute> { inclusive = true } }
                    },
                    onOpenChannel = { navController.navigate(OnDemandChannelRoute(it)) { popUpTo<OnDemandEpisodeRoute> { inclusive = true } } },
                    onAddToChannel = addToChannel,
                    onGone = { navController.popBackStack() },
                )
            }
            composable<OnDemandChannelRoute> { entry ->
                val page = entry.toRoute<OnDemandChannelRoute>()
                // Never back to wherever the page was opened from: always the on-demand main page.
                BackHandler {
                    if (!navController.popBackStack(OnDemandRoute, inclusive = false)) {
                        navController.navigate(OnDemandRoute) { popUpTo<OnDemandChannelRoute> { inclusive = true } }
                    }
                }
                ChannelScreen(
                    onWatchLive = { navController.navigate(PlayerRoute(it)) },
                    onStartOver = { navController.navigate(PlayerRoute(it, restart = true)) },
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position, page.channelId, page.atUtcMs)) },
                    onOpenEpisode = { navController.navigate(OnDemandEpisodeRoute(it, page.channelId, page.atUtcMs)) },
                    onAddToChannel = addToChannel,
                    onGone = { navController.popBackStack() },
                )
            }
        }
        composable<OnDemandPlayRoute> { entry ->
            val playing = entry.toRoute<OnDemandPlayRoute>()
            // Back to the page it was picked on (the channel's, or an episode's opened from it), or the channel's page in the player's place.
            val openOnDemand: (() -> Unit)? = if (playing.channelId == 0L) {
                null
            } else {
                {
                    val from = navController.previousBackStackEntry
                    val onPage = from != null && (
                        from.destination.hasRoute(OnDemandEpisodeRoute::class) ||
                            from.destination.hasRoute(OnDemandChannelRoute::class) && from.toRoute<OnDemandChannelRoute>().channelId == playing.channelId
                        )
                    if (onPage) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(OnDemandChannelRoute(playing.channelId, playing.atUtcMs)) { popUpTo<OnDemandPlayRoute> { inclusive = true } }
                    }
                }
            }
            // Registered before the player's own, which wins while its overlay or a panel is open.
            if (openOnDemand != null) BackHandler { openOnDemand() }
            OnDemandPlayerScreen(onOpenOnDemand = openOnDemand)
        }
        adminOnly<AddToChannelRoute>(navController) { AddToChannelScreen(onGone = { navController.popBackStack() }) }
        composable<DashboardRoute> { DashboardScreen(onTurnedOff = { navController.navigateTopLevel(TopLevelDestination.Guide) }) }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            // Each profile's own: theme, playback, watch history. The settings home shows the shared rows only to the owner and admins.
            page<ThemesRoute> { ThemesScreen(onEdit = { navController.navigate(ThemeEditorRoute(it)) }) }
            page<ThemeEditorRoute> { ThemeEditorScreen(onGone = { navController.popBackStack() }) }
            page<SettingsRoute> {
                SettingsScreen(
                    onOpenServers = { navController.navigate(ServersRoute) },
                    onOpenChannels = { navController.navigate(ChannelsRoute) },
                    onOpenOnDemand = { navController.navigate(OnDemandSettingsRoute) },
                    onOpenPlayback = { navController.navigate(PlaybackLanguagesRoute) },
                    onOpenWatchHistory = { navController.navigate(WatchHistoryRoute) },
                    onOpenThemes = { navController.navigate(ThemesRoute()) },
                    onOpenWebControl = { navController.navigate(WebControlRoute) },
                    onOpenBackup = { navController.navigate(BackupRoute()) },
                )
            }
            page<PlaybackLanguagesRoute> {
                PlaybackLanguagesScreen(onAddLanguage = { navController.navigate(LanguagePickerRoute(subtitles = it == LanguageList.SUBTITLES)) })
            }
            page<WatchHistoryRoute> { WatchHistoryScreen() }
            page<LanguagePickerRoute> { LanguagePickerScreen(onPicked = { navController.popBackStack() }) }
            // App-wide: the owner's and admins'.
            adminOnly<ChannelsRoute>(navController) {
                ChannelsScreen(
                    onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
                    onNewChannel = { navController.navigate(ChannelLineupRoute()) },
                    onOpenGroups = { navController.navigate(ChannelGroupsRoute) },
                )
            }
            adminOnly<ChannelListRoute>(navController) { entry ->
                ChannelListScreen(
                    library = entry.toRoute<ChannelListRoute>().library,
                    onOpenChannel = { navController.navigate(ChannelDetailRoute(it)) },
                )
            }
            adminOnly<ChannelDetailRoute>(navController) {
                ChannelDetailScreen(
                    onWatch = { navController.navigate(PlayerRoute(it)) },
                    onEditLineup = { navController.navigate(ChannelLineupRoute(it)) },
                    onDeleted = { navController.popBackStack() },
                )
            }
            adminOnly<ChannelLineupRoute>(navController) {
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
            adminOnly<WebControlRoute>(navController) { WebControlScreen() }
            adminOnly<BackupRoute>(navController) { BackupScreen() }
            adminOnly<ChannelGroupsRoute>(navController) { ChannelGroupsScreen() }
            adminOnly<OnDemandSettingsRoute>(navController) { OnDemandSettingsScreen(onOpenRows = { navController.navigate(OnDemandRowsRoute(it)) }) }
            adminOnly<OnDemandRowsRoute>(navController) { entry -> OnDemandRowsScreen(entry.toRoute<OnDemandRowsRoute>().kind) }
            adminOnly<ServersRoute>(navController) {
                ServersScreen(
                    onAddServer = { navController.navigate(AddServerRoute()) },
                    onOpenServer = { navController.navigate(ServerDetailRoute(it)) },
                )
            }
            adminOnly<ServerDetailRoute>(navController) {
                ServerDetailScreen(
                    onSignIn = { navController.navigate(AddServerRoute(it)) },
                    onRemoved = { navController.popBackStack() },
                )
            }
            page<AddServerRoute> { entry ->
                val linking = entry.toRoute<AddServerRoute>().linkProfileId > 0
                val screen = @Composable {
                    AddServerScreen(
                        // Back to the profile's page, or to the picker with the new profile on it.
                        onProfile = { navController.popBackStack() },
                        onSignedIn = { serverId ->
                            if (linking) {
                                // Back to the profile's page.
                                navController.popBackStack()
                            } else {
                                // Land on the server's page, with the server list behind it.
                                navController.navigate(ServerDetailRoute(serverId)) {
                                    popUpTo<ServersRoute>()
                                }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                // A profile signing in as its own user comes from that profile's page, which already
                // checked who may change it; adding a server or a profile is the owner's and admins'.
                if (linking) screen() else AdminOnly(onLeave = { navController.leave() }, content = screen)
            }
        }
        composable<PlayerRoute> {
            // Live TV always leaves to Live TV, whatever opened the player.
            BackHandler { navController.navigateTopLevel(TopLevelDestination.Guide) }
            PlayerScreen(
                // The on-demand player takes the channel's place, so back leaves to where the channel was opened from.
                onPlayOnDemand = { itemId, position, channelId, atUtcMs ->
                    navController.navigate(OnDemandPlayRoute(itemId, position, channelId, atUtcMs)) { popUpTo<PlayerRoute> { inclusive = true } }
                },
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) {
    // The start destination stays at the bottom of the stack, so going to it is going back to it.
    // Restoring state on the way would bring back the screens just popped above it whenever they
    // were opened without a tab, as the on-demand player's On Demand key opens a channel's page.
    val start = graph.findStartDestination()
    if (start.hasRoute(destination.route::class)) {
        popBackStack(start.id, inclusive = false, saveState = true)
        return
    }
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Phones: a tab bar along the bottom. Tablets and other wide screens: the same tabs as a rail
 * headed by the bug. Both sit flush on the page's own ground, with no divider, no pill and no
 * hover or ripple: the open tab is the filled icon with a bright label, the rest are outlined
 * and quiet, and a press shrinks the icon for a moment.
 */
@Composable
private fun PhoneShell(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    if (LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP) {
        Row(Modifier.fillMaxSize().background(theme.background)) {
            NavRail(destinations, selected, onNavigate, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)))
            // The rail owns the start edge; the screen keeps clear of the other system bars.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
            ) { content(Modifier.fillMaxSize()) }
        }
        return
    }
    androidx.compose.material3.Scaffold(
        containerColor = theme.background,
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(BAR_HEIGHT)
                    .selectableGroup(),
            ) {
                for (destination in destinations) {
                    NavTab(destination, destination == selected, { onNavigate(destination) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/**
 * The rail beside the screen, on tablets and TV alike: the bug, then the tabs. On TV, coming
 * in from the screen lands focus on the open destination, wherever the D-pad entered.
 */
@Composable
private fun NavRail(destinations: List<TopLevelDestination>, selected: TopLevelDestination?, onNavigate: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val requesters = remember { TopLevelDestination.entries.associateWith { FocusRequester() } }
    Box(Modifier.fillMaxHeight().background(theme.surface)) {
        Column(
            modifier
                .fillMaxHeight()
                .width(if (isTv) TV_RAIL_WIDTH else RAIL_WIDTH)
                .then(
                    if (isTv) {
                        Modifier
                            .focusProperties { onEnter = { selected?.let { requesters.getValue(it).requestFocus() } } }
                            .focusGroup()
                    } else {
                        Modifier
                    },
                )
                .selectableGroup()
                .padding(horizontal = if (isTv) 6.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(if (isTv) 4.dp else 0.dp),
        ) {
            AnstelleMark(Modifier.padding(top = if (isTv) 24.dp else 16.dp, bottom = 20.dp).size(if (isTv) 32.dp else 36.dp))
            for (destination in destinations) {
                NavTab(
                    destination,
                    destination == selected,
                    { onNavigate(destination) },
                    Modifier
                        .fillMaxWidth()
                        .height(if (isTv) TV_RAIL_ITEM_HEIGHT else RAIL_ITEM_HEIGHT)
                        .then(if (isTv) Modifier.focusRequester(requesters.getValue(destination)) else Modifier),
                )
            }
        }
    }
}

/**
 * One tab, the same on phone, tablet and TV: an icon over its label. The open tab has the
 * filled icon and a bright label; the rest are outlined and quiet. Touch gets no ripple or
 * hover, only a quick shrink of the icon on press. On TV the D-pad's focus fills the tab with
 * the accent, the focus every other TV control uses.
 */
@Composable
private fun NavTab(destination: TopLevelDestination, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "tab press",
    )
    val tint by animateColorAsState(
        when {
            focused -> theme.onAccent
            selected -> theme.text
            else -> theme.textMuted
        },
        tween(TAB_FADE_MS),
        label = "tab tint",
    )
    Column(
        modifier
            .background(if (focused) theme.accent else Color.Transparent, ControlShape)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            if (selected || focused) destination.icon else destination.outlinedIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        androidx.compose.material3.Text(
            destination.label,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** TV: the same rail as tablets, with the screen beside it. */
@Composable
private fun TvShell(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(LocalAnstelleTheme.current.background)) {
        NavRail(destinations, selected, onNavigate)
        Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize()) }
    }
}

private const val RAIL_MIN_WIDTH_DP = 600
private val RAIL_WIDTH = 88.dp
private val RAIL_ITEM_HEIGHT = 72.dp
private val TV_RAIL_WIDTH = 80.dp
private val TV_RAIL_ITEM_HEIGHT = 60.dp
private val BAR_HEIGHT = 64.dp
private const val TAB_FADE_MS = 150
