package com.idlix.tv.ui.navigation

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.navigation.NavType
import androidx.navigation.navDeepLink
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.idlix.tv.MainActivity
import com.idlix.tv.ui.components.TopBarTab
import com.idlix.tv.ui.components.tvDpadFocusRecovery
import com.idlix.tv.ui.theme.NetflixBlack
import com.idlix.tv.ui.components.TvTopBar
import com.idlix.tv.ui.components.TvTopBarHeight
import com.idlix.tv.ui.screens.detail.DetailScreen
import com.idlix.tv.ui.screens.devtools.DevToolsScreen
import com.idlix.tv.ui.screens.collections.CollectionDetailScreen
import com.idlix.tv.ui.screens.genres.GenreDetailScreen
import com.idlix.tv.ui.screens.genres.GenresScreen
import com.idlix.tv.ui.screens.history.ContinueWatchingScreen
import com.idlix.tv.ui.screens.home.HomeScreen
import com.idlix.tv.ui.screens.movies.MoviesScreen
import com.idlix.tv.ui.screens.player.PlayerScreen
import com.idlix.tv.ui.screens.search.SearchScreen
import com.idlix.tv.ui.screens.series.SeriesScreen
import com.idlix.tv.ui.screens.settings.SettingsScreen
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val selectedTab = TopBarTab.fromRoute(currentRoute)
    val settingsSelected = currentRoute.startsWith(Screen.Settings.route)

    val isFullScreenRoute = currentRoute.startsWith("player") || currentRoute.startsWith("detail")

    // Same-card refocus keys, one per tab. Hoisted ABOVE the dual-NavHost
    // switch: navigating to detail swaps the whole host, disposing the tab
    // screens AND their remember/rememberSaveable state — an in-screen key
    // dies there and back-nav always falls back to hero Play. AppNavigation
    // itself stays composed across the swap, so plain remember survives.
    var homeRefocusKey by remember { mutableStateOf<String?>(null) }
    var moviesRefocusKey by remember { mutableStateOf<String?>(null) }
    var seriesRefocusKey by remember { mutableStateOf<String?>(null) }
    var genresRefocusKey by remember { mutableStateOf<String?>(null) }
    var genreDetailRefocusKey by remember { mutableStateOf<String?>(null) }
    var collectionDetailRefocusKey by remember { mutableStateOf<String?>(null) }
    // Same-card return from DevTools: Settings disposes under DevTools, so the
    // key lives here (mirrors the detail-swap hoisting above).
    var settingsRefocusKey by remember { mutableStateOf<String?>(null) }
    var devToolsRefocusKey by remember { mutableStateOf<String?>(null) }
    var searchRefocusKey by remember { mutableStateOf<String?>(null) }
    var continueWatchingRefocusKey by remember { mutableStateOf<String?>(null) }

    // Scroll states, hoisted for the same reason as the refocus keys: the tab
    // screens dispose on detail, so their lazy scroll would reset to 0 and an
    // off-screen originating card would never recompose to take focus back.
    // The return effect scrolls these back to the originating rail + card.
    val homeListState = rememberLazyListState()
    val moviesListState = rememberLazyListState()
    val seriesListState = rememberLazyListState()
    val genresGridState = rememberLazyGridState()
    val genreDetailListState = rememberLazyListState()
    val collectionDetailListState = rememberLazyListState()
    val searchGridState = rememberLazyGridState()
    val continueWatchingGridState = rememberLazyGridState()
    // Column scroll states, hoisted for the same reason: Settings disposes
    // under DevTools (and both dispose on detail/player), so a local
    // rememberScrollState would reset to 0 and an off-screen return target
    // would never recompose to take focus back. Return effects scroll these
    // back before requesting focus.
    val settingsScrollState = rememberScrollState()
    val devToolsScrollState = rememberScrollState()
    // Rail states by "tab:railId", created on demand and stable thereafter.
    val railListStates = remember { mutableMapOf<String, LazyListState>() }
    val homeRailStates = remember { { railId: String -> railListStates.getOrPut("home:$railId") { LazyListState() } } }
    val moviesRailStates = remember { { railId: String -> railListStates.getOrPut("movies:$railId") { LazyListState() } } }
    val seriesRailStates = remember { { railId: String -> railListStates.getOrPut("series:$railId") { LazyListState() } } }
    val genreDetailRailStates = remember { { railId: String -> railListStates.getOrPut("genre:$railId") { LazyListState() } } }
    val collectionDetailRailStates = remember { { railId: String -> railListStates.getOrPut("collection:$railId") { LazyListState() } } }

    // Bar-to-content entry link: plain focus search dies crossing the
    // floating-bar overlay boundary into lazy content, so every tab/gear
    // DOWN targets this requester. Each bar-route screen attaches it to its
    // entry node (alongside its local requester) — one destination is composed
    // at a time, so the shared instance is unambiguous.
    val contentEntryLink = remember { FocusRequester() }
    // Content-to-bar escape link: content under the floating bar escapes
    // preview-UP to the selected tab/gear. Attached by TvTopBar to the
    // selected tab only — again one node at a time.
    val barTabLink = remember { FocusRequester() }
    // Cold-start deep link (idlix:// scheme, SEARCH intents): routed once the
    // graph is composed. Warm intents while running re-deliver via
    // MainActivity.onNewIntent → setIntent; the next cold start picks them up.
    val context = LocalContext.current
    val deepLinkIntent = remember {
        (context as? Activity)?.intent?.takeIf { it.action == Intent.ACTION_VIEW }
    }
    LaunchedEffect(deepLinkIntent) {
        val intent = deepLinkIntent ?: return@LaunchedEffect
        try {
            navController.handleDeepLink(intent)
        } catch (_: Exception) {
        }
    }
    // Voice-search + worker-update routing (see MainActivity): cold-start
    // intents land before the graph composes (drained once below); warm
    // intents re-deliver via onNewIntent → MainActivity dispatches to these
    // listeners. SEARCH QUERY and play-from-search EXTRA_MEDIA_TITLE both
    // land on Search via the initialQuery arg; the worker pending-update
    // flag routes to Settings (payload rides PendingUpdateStore, consumed
    // by SettingsViewModel on init).
    val activity = context as? MainActivity
    LaunchedEffect(navController) {
        activity?.onSearchQuery = { query ->
            navController.navigate(Screen.Search.createRoute(query)) {
                launchSingleTop = true
                restoreState = true
            }
        }
        activity?.onPendingUpdate = {
            navController.navigate(Screen.Settings.route) {
                launchSingleTop = true
            }
        }
        activity?.onDeepLink = { intent ->
            try {
                navController.handleDeepLink(intent)
            } catch (_: Exception) {
            }
        }
        activity?.drainPendingDeepLink()?.let { pending ->
            try {
                navController.handleDeepLink(pending)
            } catch (_: Exception) {
            }
        }
        val pending = activity?.drainPendingIntents()
        val query = pending?.first
        if (!query.isNullOrBlank()) {
            navController.navigate(Screen.Search.createRoute(query)) {
                launchSingleTop = true
                restoreState = true
            }
        } else if (pending?.second == true) {
            navController.navigate(Screen.Settings.route) {
                launchSingleTop = true
            }
        }
    }
    // Gamepad B mirrors system Back on routes where Back otherwise does
    // nothing for gamepads. Remote paths untouched.
    val gamepadBackHandler = Modifier.onKeyEvent { keyEvent ->
        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            if (navController.previousBackStackEntry != null) {
                navController.popBackStack()
                true
            } else false
        } else false
    }

    // Shell safe area: 48dp L/R + 27dp T/B content padding lives inside the
    // screens (rails carry 48dp insets; the overscan floor is 32dp). The shell
    // itself only honors the platform systemBars insets (overscan/status)
    // and consumes them so children don't re-apply.
    val shellInsets = Modifier
        .windowInsetsPadding(WindowInsets.systemBars)
        .consumeWindowInsets(WindowInsets.systemBars)

    if (isFullScreenRoute) {
        // Render without top bar
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = modifier
                .background(NetflixBlack)
                .then(shellInsets)
                .then(gamepadBackHandler)
                .tvDpadFocusRecovery(contentEntryLink)
        ) {
            setupNavRoutes(
                navController,
                currentRoute,
                homeRefocusKey, { homeRefocusKey = it },
                moviesRefocusKey, { moviesRefocusKey = it },
                seriesRefocusKey, { seriesRefocusKey = it },
                genresRefocusKey, { genresRefocusKey = it },
                genreDetailRefocusKey, { genreDetailRefocusKey = it },
                collectionDetailRefocusKey, { collectionDetailRefocusKey = it },
                settingsRefocusKey, { settingsRefocusKey = it },
                devToolsRefocusKey, { devToolsRefocusKey = it },
                searchRefocusKey, { searchRefocusKey = it },
                continueWatchingRefocusKey, { continueWatchingRefocusKey = it },
                homeListState, homeRailStates,
                moviesListState, moviesRailStates,
                seriesListState, seriesRailStates,
                genresGridState,
                genreDetailListState, genreDetailRailStates,
                collectionDetailListState, collectionDetailRailStates,
                searchGridState,
                continueWatchingGridState,
                settingsScrollState,
                devToolsScrollState,
                contentEntryLink, barTabLink
            )
        }
    } else {
        // Non-fullscreen routes: hero routes bleed all the way to the top edge (y = 0)
        // behind the translucent TvTopBar, while non-hero routes stay padded below TvTopBarHeight.
        val isHeroBleedRoute = currentRoute == Screen.Home.route || currentRoute == Screen.Movies.route || currentRoute == Screen.Series.route
        Box(
            modifier = modifier
                .background(NetflixBlack)
                .then(shellInsets)
                .then(gamepadBackHandler)
                .tvDpadFocusRecovery(contentEntryLink)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (isHeroBleedRoute) 0.dp else TvTopBarHeight)
            ) {
                setupNavRoutes(
                    navController,
                    currentRoute,
                    homeRefocusKey, { homeRefocusKey = it },
                    moviesRefocusKey, { moviesRefocusKey = it },
                    seriesRefocusKey, { seriesRefocusKey = it },
                    genresRefocusKey, { genresRefocusKey = it },
                    genreDetailRefocusKey, { genreDetailRefocusKey = it },
                    collectionDetailRefocusKey, { collectionDetailRefocusKey = it },
                    settingsRefocusKey, { settingsRefocusKey = it },
                    devToolsRefocusKey, { devToolsRefocusKey = it },
                    searchRefocusKey, { searchRefocusKey = it },
                    continueWatchingRefocusKey, { continueWatchingRefocusKey = it },
                    homeListState, homeRailStates,
                    moviesListState, moviesRailStates,
                    seriesListState, seriesRailStates,
                    genresGridState,
                    genreDetailListState, genreDetailRailStates,
                    collectionDetailListState, collectionDetailRailStates,
                    searchGridState,
                    continueWatchingGridState,
                    settingsScrollState,
                    devToolsScrollState,
                    contentEntryLink, barTabLink
                )
            }
            TvTopBar(
                selectedTab = selectedTab,
                downFocusRequester = contentEntryLink,
                tabFocusLink = barTabLink,
                onTabSelected = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(Screen.Home.route) { inclusive = tab == TopBarTab.HOME }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                settingsSelected = settingsSelected,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.setupNavRoutes(
    navController: androidx.navigation.NavHostController,
    currentRoute: String,
    homeRefocusKey: String?,
    onHomeRefocusKeyChange: (String?) -> Unit,
    moviesRefocusKey: String?,
    onMoviesRefocusKeyChange: (String?) -> Unit,
    seriesRefocusKey: String?,
    onSeriesRefocusKeyChange: (String?) -> Unit,
    genresRefocusKey: String?,
    onGenresRefocusKeyChange: (String?) -> Unit,
    genreDetailRefocusKey: String?,
    onGenreDetailRefocusKeyChange: (String?) -> Unit,
    collectionDetailRefocusKey: String?,
    onCollectionDetailRefocusKeyChange: (String?) -> Unit,
    settingsRefocusKey: String?,
    onSettingsRefocusKeyChange: (String?) -> Unit,
    devToolsRefocusKey: String?,
    onDevToolsRefocusKeyChange: (String?) -> Unit,
    searchRefocusKey: String?,
    onSearchRefocusKeyChange: (String?) -> Unit,
    continueWatchingRefocusKey: String?,
    onContinueWatchingRefocusKeyChange: (String?) -> Unit,
    homeListState: LazyListState,
    homeRailStates: (String) -> LazyListState,
    moviesListState: LazyListState,
    moviesRailStates: (String) -> LazyListState,
    seriesListState: LazyListState,
    seriesRailStates: (String) -> LazyListState,
    genresGridState: LazyGridState,
    genreDetailListState: LazyListState,
    genreDetailRailStates: (String) -> LazyListState,
    collectionDetailListState: LazyListState,
    collectionDetailRailStates: (String) -> LazyListState,
    searchGridState: LazyGridState,
    continueWatchingGridState: LazyGridState,
    settingsScrollState: ScrollState,
    devToolsScrollState: ScrollState,
    contentEntryLink: FocusRequester,
    barTabLink: FocusRequester
) {
    composable(Screen.Home.route) {
        HomeScreen(
            onNavigateToDetail = { type, slug ->
                navController.navigate(Screen.Detail.createRoute(type, slug))
            },
            onNavigateToPlayer = { type, id, title, poster, backdrop, slug, season, episode ->
                navController.navigate(Screen.Player.createRoute(type, id, title, poster, backdrop, slug, season, episode))
            },
            onNavigateToCollection = { slug, name ->
                navController.navigate(Screen.CollectionDetail.createRoute(slug, name))
            },
            refocusKey = homeRefocusKey,
            onRefocusKeyChange = onHomeRefocusKeyChange,
            listState = homeListState,
            railListStates = homeRailStates,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(Screen.ContinueWatching.route) {
        // Tab root: consume system Back (scaffold-less route owns its handler).
        BackHandler(enabled = true) { }
        ContinueWatchingScreen(
            onNavigateToPlayer = { type, id, title, poster, backdrop, slug, season, episode ->
                navController.navigate(Screen.Player.createRoute(type, id, title, poster, backdrop, slug, season, episode))
            },
            onNavigateToHome = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                }
            },
            refocusKey = continueWatchingRefocusKey,
            onRefocusKeyChange = onContinueWatchingRefocusKeyChange,
            gridState = continueWatchingGridState,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(Screen.Movies.route) {
        MoviesScreen(
            onNavigateToDetail = { type, slug ->
                navController.navigate(Screen.Detail.createRoute(type, slug))
            },
            onNavigateToPlayer = { type, id, title, poster, backdrop, slug ->
                navController.navigate(Screen.Player.createRoute(type, id, title, poster, backdrop, slug))
            },
            refocusKey = moviesRefocusKey,
            onRefocusKeyChange = onMoviesRefocusKeyChange,
            listState = moviesListState,
            railListStates = moviesRailStates,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(Screen.Series.route) {
        SeriesScreen(
            onNavigateToDetail = { type, slug ->
                navController.navigate(Screen.Detail.createRoute(type, slug))
            },
            onNavigateToPlayer = { type, id, title, poster, backdrop, slug ->
                navController.navigate(Screen.Player.createRoute(type, id, title, poster, backdrop, slug))
            },
            refocusKey = seriesRefocusKey,
            onRefocusKeyChange = onSeriesRefocusKeyChange,
            listState = seriesListState,
            railListStates = seriesRailStates,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(
        route = Screen.Search.route,
        arguments = listOf(
            navArgument("initialQuery") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        )
    ) { backStackEntry ->
        // Tab root: consume system Back (scaffold-less route owns its handler).
        BackHandler(enabled = true) { }
        val rawQuery = backStackEntry.arguments?.getString("initialQuery")
        val initialQuery = rawQuery?.let {
            try {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            } catch (e: Exception) {
                it
            }
        }?.takeIf { it.isNotBlank() }
        SearchScreen(
            onNavigateToDetail = { type, slug ->
                navController.navigate(Screen.Detail.createRoute(type, slug))
            },
            initialQuery = initialQuery,
            refocusKey = searchRefocusKey,
            onRefocusKeyChange = onSearchRefocusKeyChange,
            gridState = searchGridState,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(Screen.Genres.route) {
        // Tab root: consume system Back (scaffold-less route owns its handler).
        BackHandler(enabled = true) { }
        GenresScreen(
            onNavigateToGenre = { slug, name ->
                navController.navigate(Screen.GenreDetail.createRoute(slug, name))
            },
            refocusKey = genresRefocusKey,
            onRefocusKeyChange = onGenresRefocusKeyChange,
            gridState = genresGridState,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(
        route = Screen.GenreDetail.route,
        arguments = listOf(
            navArgument("slug") { type = NavType.StringType },
            navArgument("name") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            }
        )
    ) { backStackEntry ->
        val slug = backStackEntry.arguments?.getString("slug") ?: ""
        val rawName = backStackEntry.arguments?.getString("name") ?: ""
        val name = try {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            rawName
        }
        GenreDetailScreen(
            genreSlug = slug,
            genreName = name.ifBlank { slug },
            onNavigateToDetail = { type, detailSlug ->
                navController.navigate(Screen.Detail.createRoute(type, detailSlug))
            },
            onBack = { navController.popBackStack() },
            refocusKey = genreDetailRefocusKey,
            onRefocusKeyChange = onGenreDetailRefocusKeyChange,
            listState = genreDetailListState,
            railListStates = genreDetailRailStates,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(
        route = Screen.CollectionDetail.route,
        arguments = listOf(
            navArgument("slug") { type = NavType.StringType },
            navArgument("name") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            }
        )
    ) { backStackEntry ->
        val slug = backStackEntry.arguments?.getString("slug") ?: ""
        val rawName = backStackEntry.arguments?.getString("name") ?: ""
        val name = try {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            rawName
        }
        CollectionDetailScreen(
            collectionSlug = slug,
            collectionName = name.ifBlank { slug },
            onNavigateToDetail = { type, detailSlug ->
                navController.navigate(Screen.Detail.createRoute(type, detailSlug))
            },
            onBack = { navController.popBackStack() },
            refocusKey = collectionDetailRefocusKey,
            onRefocusKeyChange = onCollectionDetailRefocusKeyChange,
            listState = collectionDetailListState,
            railListStates = collectionDetailRailStates,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink
        )
    }

    composable(Screen.Settings.route) {
        // Tab root: consume system Back (scaffold-less route owns its handler).
        BackHandler(enabled = true) { }
        SettingsScreen(
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink,
            refocusKey = settingsRefocusKey,
            onRefocusKeyChange = onSettingsRefocusKeyChange,
            scrollState = settingsScrollState,
            onOpenDevTools = { navController.navigate(Screen.DevTools.route) }
        )
    }

    composable(Screen.DevTools.route) {
        // Child route: Back restores the Settings entry (same-card return
        // parks on DevTools when the advanced section is still unlocked).
        BackHandler {
            onSettingsRefocusKeyChange("devtools")
            navController.popBackStack()
        }
        DevToolsScreen(
            currentRoute = currentRoute,
            entryLinkRequester = contentEntryLink,
            barLinkRequester = barTabLink,
            refocusKey = devToolsRefocusKey,
            onRefocusKeyChange = onDevToolsRefocusKeyChange,
            scrollState = devToolsScrollState,
            // Dummy-QA fixtures open the real error states (Retry + Back)
            // with fake ids — no catalog dependence, nothing to clean up.
            onOpenDummyDetail = { type, slug ->
                navController.navigate(Screen.Detail.createRoute(type, slug))
            },
            onOpenDummyPlayer = { type, contentId ->
                navController.navigate(
                    Screen.Player.createRoute(
                        contentType = type,
                        contentId = contentId,
                        title = "DUMMY fixture (invalid id)"
                    )
                )
            },
            // Same-card return: BACK parks on the DevTools entry card when
            // the advanced section is still unlocked, else on the update
            // entry (see SettingsScreen return effect; relock-on-leave
            // usually hides the card, so the fallback is the norm).
            onBack = {
                onSettingsRefocusKeyChange("devtools")
                navController.popBackStack()
            }
        )
    }

    composable(
        route = Screen.Detail.route,
        arguments = listOf(
            navArgument("contentType") { type = NavType.StringType },
            navArgument("slug") { type = NavType.StringType }
        ),
        deepLinks = listOf(navDeepLink { uriPattern = "idlix://detail/{contentType}/{slug}" })
    ) { backStackEntry ->
        // Child route: Back pops to the originating rail card. DetailScreen
        // keeps its preview-key handler as the first consumer; this is the
        // single dispatcher-level handler for the route.
        BackHandler { navController.popBackStack() }
        val contentType = backStackEntry.arguments?.getString("contentType") ?: "movie"
        val slug = backStackEntry.arguments?.getString("slug") ?: ""

        DetailScreen(
            contentType = contentType,
            slug = slug,
            onNavigateToPlayer = { type, id, title, poster, backdrop, slugParam, season, episode ->
                navController.navigate(Screen.Player.createRoute(type, id, title, poster, backdrop, slugParam, season, episode))
            },
            onBack = { navController.popBackStack() }
        )
    }

    composable(
        route = Screen.Player.route,
        arguments = listOf(
            navArgument("contentType") { type = NavType.StringType },
            navArgument("contentId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType },
            navArgument("poster") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            },
            navArgument("backdrop") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            },
            navArgument("slug") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            },
            navArgument("season") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            },
            navArgument("episode") {
                type = NavType.StringType
                nullable = true
                defaultValue = ""
            }
        ),
        // idlix://player/<type>/<id>/<title>: query extras ride the intent;
        // modal-first Back stays inside PlayerScreen (its own handler).
        deepLinks = listOf(navDeepLink { uriPattern = "idlix://player/{contentType}/{contentId}/{title}" })
    ) { backStackEntry ->
        val contentType = backStackEntry.arguments?.getString("contentType") ?: "movie"
        val contentId = backStackEntry.arguments?.getString("contentId") ?: ""
        val rawTitle = backStackEntry.arguments?.getString("title") ?: ""
        val title = try {
            URLDecoder.decode(rawTitle, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            rawTitle
        }
        val rawPoster = backStackEntry.arguments?.getString("poster") ?: ""
        val poster = try {
            URLDecoder.decode(rawPoster, StandardCharsets.UTF_8.toString()).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            rawPoster.takeIf { it.isNotBlank() }
        }
        val rawBackdrop = backStackEntry.arguments?.getString("backdrop") ?: ""
        val backdrop = try {
            URLDecoder.decode(rawBackdrop, StandardCharsets.UTF_8.toString()).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            rawBackdrop.takeIf { it.isNotBlank() }
        }
        val rawSlug = backStackEntry.arguments?.getString("slug") ?: ""
        val slug = try {
            URLDecoder.decode(rawSlug, StandardCharsets.UTF_8.toString()).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            rawSlug.takeIf { it.isNotBlank() }
        }
        val season = backStackEntry.arguments?.getString("season")?.toIntOrNull()
        val episode = backStackEntry.arguments?.getString("episode")?.toIntOrNull()

        PlayerScreen(
            contentType = contentType,
            contentId = contentId,
            title = title,
            posterUrl = poster,
            backdropUrl = backdrop,
            slug = slug,
            seasonNumber = season,
            episodeNumber = episode,
            onBack = { navController.popBackStack() }
        )
    }
}
