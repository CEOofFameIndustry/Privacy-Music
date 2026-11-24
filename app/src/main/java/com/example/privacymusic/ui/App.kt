package com.example.privacymusic.ui

import com.example.privacymusic.i18n.T


import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.navigation.NavBackStackEntry
import com.example.privacymusic.ui.theme.Dim
import com.example.privacymusic.ui.theme.Pal
import com.example.privacymusic.ui.theme.ScreenBackground
import com.example.privacymusic.ui.theme.AppBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.privacymusic.data.*
import com.example.privacymusic.playback.Playback
import com.example.privacymusic.ui.components.ItemActions
import com.example.privacymusic.ui.screens.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How songs tapped in a list should be queued. */
sealed interface PlayContext {
    /** Online list that YouTube Music can continue (album, playlist). */
    data class Remote(val playlistId: String, val title: String?) : PlayContext
    /** Local list (likes, history, downloads, local playlist): play exactly these songs. */
    data class Local(val songs: List<Song>, val title: String?) : PlayContext
}

class AppController(val nav: NavHostController, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var menu by mutableStateOf<MenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    /** Player sheet position: 0 = collapsed into the mini player, 1 = full screen. Follows the finger while dragging. */
    val sheet = Animatable(0f)
    var sheetHeight = 2000f
    private var open by mutableStateOf(false)
    var playerOpen: Boolean
        get() = open
        set(v) = settle(v)

    /** Set when the bottom bar switches tabs, so that switch uses a fade-through instead of a slide. */
    var tabSwitchAt = 0L

    /** Switch to a bottom-bar tab (keeps each tab's own back stack, like YouTube Music). */
    /** Set when the user taps a search icon: the search field takes focus and the keyboard opens right away. */
    var focusSearch by mutableStateOf(false)

    fun tab(route: String) {
        if (route == "search") focusSearch = true
        tabSwitchAt = System.currentTimeMillis()
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }

    fun settle(expand: Boolean, velocity: Float = 0f) {
        open = expand
        scope.launch { sheet.animateTo(if (expand) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f), initialVelocity = velocity) }
    }
    // Position is tracked synchronously with the finger (the Animatable is only told about it), so a release
    // always sees exactly where the finger was.
    private var dragPos = 0f
    private var dragFrom = 0f
    /** Back gesture in progress: lower the player a little with the finger. */
    fun previewClose(progress: Float) { scope.launch { sheet.snapTo(1f - progress * 0.3f) } }
    fun startSheetDrag() { dragPos = sheet.value; dragFrom = dragPos }
    /** Finger moved by [dy] px (positive = down). */
    fun dragSheet(dy: Float) {
        dragPos = (dragPos - dy / sheetHeight).coerceIn(0f, 1f)
        val target = dragPos
        scope.launch { sheet.snapTo(target) }
    }
    /** Finger lifted with [velocity] px/s (positive = down). A fling decides; otherwise moving a quarter of the
     *  screen away from where the drag started is enough (direction-aware, like YouTube Music). */
    fun releaseSheet(velocity: Float) {
        val v = -velocity / sheetHeight
        val open = when {
            v > 1.0f -> true
            v < -1.0f -> false
            dragFrom < 0.5f -> dragPos > 0.25f
            else -> dragPos > 0.75f
        }
        settle(open, v)
    }
    /** Set by the playlist menu's "Edit"; the local playlist screen switches into edit mode when it sees its id. */
    var editPlaylist by mutableStateOf<Long?>(null)

    data class MenuRequest(val item: YTItem, val localPlaylist: Long? = null, val index: Int? = null, val historyAt: Long? = null, val queueIndex: Int? = null)

    fun toast(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }

    fun browse(id: String, params: String? = null) =
        nav.navigate("browse/${Uri.encode(id)}" + (params?.let { "?p=${Uri.encode(it)}" } ?: "")) { launchSingleTop = true }

    fun search(query: String, params: String? = null) =
        nav.navigate("results?q=${Uri.encode(query)}" + (params?.let { "&p=${Uri.encode(it)}" } ?: "")) { launchSingleTop = true }

    fun endpoint(e: Endpoint, title: String? = null) {
        when (e) {
            is BrowseEndpoint -> browse(e.browseId, e.params)
            is SearchEndpoint -> search(e.query, e.params)
            is WatchEndpoint -> { Playback.playEndpoint(e, title); playerOpen = false }
        }
    }

    fun open(item: YTItem, context: PlayContext? = null) {
        when (item) {
            is Song -> when (context) {
                is PlayContext.Remote -> Playback.playEndpoint(WatchEndpoint(item.id, context.playlistId), context.title)
                is PlayContext.Local -> Playback.playQueue(context.songs, context.songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0), title = context.title)
                null -> Playback.playSong(item)
            }
            is AlbumItem -> browse(item.browseId)
            is ArtistItem -> browse(item.browseId)
            is PlaylistItem -> if (item.watch != null && !item.browseId.startsWith("VL")) Playback.playEndpoint(item.watch, item.title) else browse(item.browseId)
            is NavItem -> endpoint(item.endpoint, item.title)
            is LocalPlaylistItem -> { playerOpen = false; nav.navigate("local/${item.id}") { launchSingleTop = true } }
        }
    }

    fun actions(context: PlayContext? = null) = ItemActions(
        open = { item, _ -> open(item, context) },
        menu = { menu = MenuRequest(it) },
        endpoint = { e, t -> endpoint(e, t) },
    )
}

val LocalApp = staticCompositionLocalOf<AppController> { error("no app") }

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
private val tabs = listOf(
    Tab("home", "Головна", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("search", "Пошук", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab("library", "Медіатека", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

private val tabRoutes = setOf("home", "search", "library")

private val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun MusicApp(pendingLink: Endpoint?, consumeLink: () -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val app = remember { AppController(nav, scope, snackbar) }
    val current by Playback.current.collectAsState()
    val error by Playback.error.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val stack by nav.currentBackStack.collectAsState()
    val currentTab = stack.lastOrNull { it.destination.route in tabRoutes }?.destination?.route ?: "home"

    LaunchedEffect(pendingLink) { pendingLink?.let { app.endpoint(it); consumeLink() } }
    LaunchedEffect(error) { error?.let { app.toast(it) } }
    LaunchedEffect(Unit) { com.example.privacymusic.net.Link.events.collect { app.toast(it) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Once a day (Settings → About → "Notify about updates"): a note when a new version is out.
    LaunchedEffect(Unit) { com.example.privacymusic.net.Updater.autoCheck(ctx)?.let { app.toast(T("Доступна версія {0} — Налаштування → Перевірити оновлення", it.version)) } }
    // Music playing on the paired computer replaces the mini player (with remote controls), like Spotify Connect.
    val remoteDevice = com.example.privacymusic.ui.screens.rememberRemoteDevice()

    // Screens are transparent over one shared AppBackground, so no transition may ever show two pages on top of each other.
    // Tab switches use Material "fade-through": the old screen fades out completely BEFORE the new one fades in.
    // Going deeper/back is a page turn: both screens move together, edge to edge, so nothing ever stacks on top.
    val tabSwitch = { System.currentTimeMillis() - app.tabSwitchAt < 600 }
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch()) fadeIn(tween(150, delayMillis = 90, easing = LinearOutSlowInEasing))
        else slideInHorizontally(tween(340, easing = ease)) { it }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch()) fadeOut(tween(90, easing = FastOutLinearInEasing))
        else slideOutHorizontally(tween(340, easing = ease)) { -it }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch()) fadeIn(tween(150, delayMillis = 90)) else slideInHorizontally(tween(340, easing = ease)) { -it }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch()) fadeOut(tween(90)) else slideOutHorizontally(tween(340, easing = ease)) { it }
    }
    // Edge-swipe (predictive) back. NavHost's default here shrinks the page in place, and since screens are transparent
    // over one shared background the two pages showed through each other. Instead the page follows the finger and the
    // previous one comes in right next to it: same duration + linear easing keeps them edge to edge at every seek point.
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        slideInHorizontally(tween(340, easing = LinearEasing)) { -it }
    }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = {
        slideOutHorizontally(tween(340, easing = LinearEasing)) { it }
    }

    CompositionLocalProvider(LocalApp provides app) {
        Surface(Modifier.fillMaxSize(), color = Pal.bg, contentColor = Color.White) { Box(Modifier.fillMaxSize().onSizeChanged { app.sheetHeight = it.height.toFloat().coerceAtLeast(1f) }) {
            AppBackground()
            Scaffold(
                containerColor = Color.Transparent,
                // Screens add the status-bar inset themselves (TopBar); the Scaffold must not add it a second time.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    // Tab order is user-arrangeable (pencil on any main page); defaults differ per interface style.
                    val tabLayout by Settings.layoutTabs.state.collectAsState()
                    val orderedTabs = com.example.privacymusic.ui.components.Layouts.arrange(tabLayout, com.example.privacymusic.ui.components.defaultTabs())
                        .mapNotNull { (id, _) -> tabs.firstOrNull { it.route == id } }
                    fun onTab(route: String, selected: Boolean) {
                        // A screen belongs to the tab whose root is below it in the back stack (Settings opened from
                        // Library is still "Library"); tapping the current tab again goes back to its root.
                        if (route == "search") app.focusSearch = true
                        if (!selected) app.tab(route)
                        else if (backStack?.destination?.route != route) { app.tabSwitchAt = 0; nav.popBackStack(route, false) }
                    }
                    if (Pal.minimal) Column(Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Pal.bg.copy(alpha = .9f), Pal.bg)))
                        .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                        // Minimal: a floating mini player above a floating, rounded tab bar.
                        AnimatedVisibility(current != null || remoteDevice != null, enter = expandVertically(tween(260, easing = ease)) + fadeIn(), exit = shrinkVertically(tween(200)) + fadeOut()) {
                            Box(Modifier.padding(bottom = 8.dp).graphicsLayer { alpha = 1f - (app.sheet.value * 2.5f).coerceAtMost(1f) }
                                .shadow(10.dp, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                                if (remoteDevice != null) com.example.privacymusic.ui.screens.RemoteMiniPlayer(remoteDevice) else MiniPlayer(onOpen = { app.playerOpen = true })
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(60.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(30.dp)).background(Pal.card)
                            .border(0.5.dp, Color.White.copy(alpha = .08f), androidx.compose.foundation.shape.RoundedCornerShape(30.dp))) {
                            orderedTabs.forEach { tab ->
                                val selected = tab.route == currentTab
                                val tint by animateColorAsState(if (selected) Pal.accent else Dim, tween(180), label = "tab")
                                Column(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTab(tab.route, selected) },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(if (selected) tab.selectedIcon else tab.icon, null, Modifier.size(24.dp), tint = tint)
                                    Text(T(tab.label), color = tint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    } else Column(Modifier.background(Pal.bar)) {
                        AnimatedVisibility(current != null || remoteDevice != null, enter = expandVertically(tween(260, easing = ease)) + fadeIn(), exit = shrinkVertically(tween(200)) + fadeOut()) {
                            Box(Modifier.graphicsLayer { alpha = 1f - (app.sheet.value * 2.5f).coerceAtMost(1f) }) {
                                if (remoteDevice != null) com.example.privacymusic.ui.screens.RemoteMiniPlayer(remoteDevice) else MiniPlayer(onOpen = { app.playerOpen = true })
                            }
                        }
                        NavigationBar(containerColor = Pal.bar, tonalElevation = 0.dp) {
                            orderedTabs.forEach { tab ->
                                val selected = tab.route == currentTab
                                NavigationBarItem(selected = selected, onClick = { onTab(tab.route, selected) },
                                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) }, label = { Text(T(tab.label)) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Pal.chip, selectedIconColor = Color.White,
                                        selectedTextColor = Color.White, unselectedIconColor = Dim, unselectedTextColor = Dim))
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding),
                    enterTransition = enter, exitTransition = exit, popEnterTransition = popEnter, popExitTransition = popExit,
                    predictivePopEnterTransition = predictivePopEnter, predictivePopExitTransition = predictivePopExit) {
                    composable("home") { ScreenBackground { HomeScreen() } }
                    composable("search") { ScreenBackground { SearchScreen() } }
                    composable("library") { ScreenBackground { LibraryScreen() } }
                    composable("results?q={q}&p={p}", listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" },
                        navArgument("p") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        ScreenBackground { SearchResultsScreen(it.arguments?.getString("q").orEmpty(), it.arguments?.getString("p")) }
                    }
                    composable("browse/{id}?p={p}", listOf(navArgument("id") { type = NavType.StringType },
                        navArgument("p") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        ScreenBackground { BrowseScreen(it.arguments?.getString("id").orEmpty(), it.arguments?.getString("p")) }
                    }
                    composable("local/{id}?edit={edit}", listOf(navArgument("id") { type = NavType.LongType },
                        navArgument("edit") { type = NavType.BoolType; defaultValue = false })) {
                        ScreenBackground { LocalPlaylistScreen(it.arguments?.getLong("id") ?: 0, it.arguments?.getBoolean("edit") ?: false) }
                    }
                    composable("liked") { ScreenBackground { LikedSongsScreen() } }
                    composable("downloads") { ScreenBackground { DownloadedScreen() } }
                    composable("settings") { ScreenBackground { SettingsScreen() } }
                    composable("devices") { ScreenBackground { com.example.privacymusic.ui.screens.DevicesScreen() } }
                    composable("privacy") { ScreenBackground { PrivacyScreen() } }
                    composable("import") { ScreenBackground { ImportScreen() } }
                    composable("stats") { ScreenBackground { StatsScreen() } }
                    composable("backup") { ScreenBackground { BackupScreen() } }
                    composable("appearance") { ScreenBackground { AppearanceScreen() } }
                    composable("support") { ScreenBackground { SupportScreen() } }
                    composable("report?kind={kind}", listOf(navArgument("kind") { type = NavType.StringType; defaultValue = "bug" })) {
                        ScreenBackground { ReportScreen(it.arguments?.getString("kind") ?: "bug") }
                    }
                }
            }
            // The full player is one sheet driven by app.sheet: it tracks the finger both ways (read only in the draw phase).
            val showPlayer by remember { derivedStateOf { app.sheet.value > 0.001f } }
            if (current != null && showPlayer) Box(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - app.sheet.value) * size.height }) {
                PlayerScreen(onClose = { app.playerOpen = false })
            }
            // Registered AFTER the NavHost, so Back / the back gesture closes the player instead of popping the page
            // underneath it. With predictive back the player follows the gesture down.
            // Composed only while the player is open, so its callback is added at that moment and is always the newest
            // (= highest priority) one, even after the system re-registers callbacks on resume.
            if (app.playerOpen) PredictiveBackHandler { progress ->
                try {
                    progress.collect { e -> app.previewClose(e.progress) }
                    app.settle(false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    app.settle(true); throw e
                }
            }
            app.menu?.let { ItemMenu(it, onDismiss = { app.menu = null }) }
            app.addToPlaylist?.let { AddToPlaylistDialog(it, onDismiss = { app.addToPlaylist = null }) }
        } }
    }
}
