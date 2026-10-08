package tv.anstelle.app.ui

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
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.NavigationDrawerItem
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

import tv.anstelle.core.theme.components.AnstelleMark
import tv.anstelle.core.theme.components.Wordmark
import tv.anstelle.feature.dashboard.DashboardScreen
import tv.anstelle.feature.guide.GuideScreen
import tv.anstelle.feature.ondemand.ChannelScreen
import tv.anstelle.feature.ondemand.OnDemandScreen
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
import tv.anstelle.feature.settings.playback.WatchHistoryScreen
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
                onStartOver = { navController.navigate(PlayerRoute(it, restart = true)) },
                onPlayOnDemand = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                onAddToChannel = { navController.navigate(AddToChannelRoute(it)) },
                onOpenSettings = { navController.navigateTopLevel(TopLevelDestination.Settings) },
            )
        }
        navigation<OnDemandGraph>(startDestination = OnDemandRoute) {
            composable<OnDemandRoute> {
                OnDemandScreen(
                    onOpenChannel = { navController.navigate(OnDemandChannelRoute(it)) },
                    onOpenSettings = { navController.navigateTopLevel(TopLevelDestination.Settings) },
                )
            }
            composable<OnDemandChannelRoute> {
                ChannelScreen(
                    onWatchLive = { navController.navigate(PlayerRoute(it)) },
                    onStartOver = { navController.navigate(PlayerRoute(it, restart = true)) },
                    onPlay = { itemId, position -> navController.navigate(OnDemandPlayRoute(itemId, position)) },
                    onAddToChannel = { navController.navigate(AddToChannelRoute(it)) },
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
                    onOpenWatchHistory = { navController.navigate(WatchHistoryRoute) },
                )
            }
            composable<PlaybackLanguagesRoute> {
                PlaybackLanguagesScreen(onAddLanguage = { navController.navigate(LanguagePickerRoute(subtitles = it == LanguageList.SUBTITLES)) })
            }
            composable<WatchHistoryRoute> { WatchHistoryScreen() }
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

/**
 * Phones: a tab bar along the bottom. Tablets and other wide screens: the same tabs as a rail
 * headed by the bug. Both sit flush on the page's own ground, with no divider, no pill and no
 * hover or ripple: the open tab is the filled icon with a bright label, the rest are outlined
 * and quiet, and a press shrinks the icon for a moment.
 */
@Composable
private fun PhoneShell(
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    if (LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP) {
        Row(Modifier.fillMaxSize().background(theme.background)) {
            NavRail(selected, onNavigate, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)))
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
                for (destination in TopLevelDestination.entries) {
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
private fun NavRail(selected: TopLevelDestination?, onNavigate: (TopLevelDestination) -> Unit, modifier: Modifier = Modifier) {
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
            for (destination in TopLevelDestination.entries) {
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
    selected: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(LocalAnstelleTheme.current.background)) {
        NavRail(selected, onNavigate)
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
