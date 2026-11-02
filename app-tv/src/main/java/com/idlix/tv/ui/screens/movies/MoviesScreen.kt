package com.idlix.tv.ui.screens.movies

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.idlix.tv.R
import com.idlix.data.model.MovieItem
import com.idlix.tv.ui.components.TvHeroBillboard
import com.idlix.tv.ui.components.TvRailGraph
import com.idlix.tv.ui.components.TvScreenScaffold
import com.idlix.tv.ui.components.TvSwimlaneRow

@Composable
fun MoviesScreen(
    onNavigateToDetail: (contentType: String, slug: String) -> Unit,
    onNavigateToPlayer: (contentType: String, contentId: String, title: String, poster: String?, backdrop: String?, slug: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MoviesViewModel = viewModel(),
    // Same-card refocus key, hoisted in AppNavigation (see HomeScreen).
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    railListStates: (String) -> LazyListState = { LazyListState() },
    // Bar DOWN target on the hero Play button (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for the hero (see topEdgeBarEscape).
    barLinkRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val heroFocusRequester = remember { FocusRequester() }
    // Browsing signal for the hero carousel (see TvHeroBillboard
    // rotationPaused): true while focus sits anywhere in this screen — hero
    // CTAs or any rail — so the backdrop never swaps mid-browse. Passive
    // observer only; attaches no focus target and touches no focus chain.
    var screenHasFocus by remember { mutableStateOf(false) }
    // Immersive hero follow (see TvHeroBillboard focusedMedia): the focused
    // rail card, shown in the billboard while focused. Passive state only —
    // written by rail onItemFocus callbacks, touches no focus chain.
    var focusedMedia by remember { mutableStateOf<MovieItem?>(null) }
    // Rail links + column registries (see TvRailGraph): one FocusRequester
    // per rail container + one index->card map per rail, created on demand
    // and stable thereafter. Plain maps (not state): written during
    // composition, read only on key events.
    val railLinks = remember { mutableMapOf<String, FocusRequester>() }
    // Scroll-to-top on genuine content→bar escape (see topEdgeBarEscape):
    // UP from a top-edge card lands on the tabs, so pull the hero back into
    // view underneath. Suspend lambda — the modifier launches it async, so
    // the focus hop never waits on (or loops from) the scroll. Tab
    // LEFT/RIGHT and entry focus never touch this path (UP key only).
    val scrollToTop: suspend () -> Unit = {
        try {
            listState.scrollToItem(0)
        } catch (_: Exception) {}
    }
    // Dynamic order hero + non-empty rails only. Empty rails are skipped
    // entirely (no outer item, no link), so UP/DOWN never lands on a
    // detached container. Rail order MUST mirror the LazyColumn items
    // below. Axis movement is pure focus search between composed containers.
    val railOrder = buildList {
        add("hero")
        if (uiState.popularMovies.isNotEmpty()) add("popular")
        if (uiState.latestMovies.isNotEmpty()) add("latest")
        if (uiState.actionMovies.isNotEmpty()) add("action")
        if (uiState.comedyMovies.isNotEmpty()) add("comedy")
        if (uiState.horrorMovies.isNotEmpty()) add("horror")
        if (uiState.sciFiMovies.isNotEmpty()) add("scifi")
    }
    // Single wiring graph (see TvRailGraph): dynamic hero + non-empty rails
    // only. Empty rails are skipped entirely. Rail order MUST mirror the
    // LazyColumn items below. Axis movement is pure focus search between
    // composed containers.
    val railGraph = TvRailGraph(
        order = railOrder,
        entryId = "hero",
        entryLink = heroFocusRequester,
        links = railLinks
    )
    fun prevRailId(id: String): String? = railGraph.prevRailId(id)
    fun nextRailId(id: String): String? = railGraph.nextRailId(id)
    fun linkFor(id: String?): FocusRequester? = railGraph.linkFor(id)
    val navigateToDetail: (String, String, String) -> Unit = { railId, type, slug ->
        onRefocusKeyChange("$railId/$type/$slug")
        onNavigateToDetail(type, slug)
    }
    val errorMessage: String? = if (uiState.error != null || uiState.errorRes != null) {
        uiState.error ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
    } else {
        null
    }
    val railLists = mapOf(
        "popular" to uiState.popularMovies,
        "latest" to uiState.latestMovies,
        "action" to uiState.actionMovies,
        "comedy" to uiState.comedyMovies,
        "horror" to uiState.horrorMovies,
        "scifi" to uiState.sciFiMovies
    )
    // Per-rail card lookup for return-from-detail (see TvScreenScaffold).
    fun findCardIndex(railId: String, type: String, slug: String): Int =
        railLists[railId]?.indexOfFirst { it.contentType == type && it.slug == slug } ?: -1

    TvScreenScaffold(
        isLoading = uiState.isLoading,
        errorMessage = errorMessage,
        onRetry = { viewModel.loadMoviesData() },
        listState = listState,
        refocusKey = refocusKey,
        onRefocusKeyChange = onRefocusKeyChange,
        entryKeyValue = "hero",
        railOrder = railOrder,
        railListStates = railListStates,
        onEntryFocus = {
            // Hero-first, rail fallback (see HomeScreen): a partial outage
            // can empty the hero while rails render — never park detached.
            if (uiState.popularMovies.isNotEmpty()) {
                heroFocusRequester.requestFocus()
            } else {
                linkFor(railOrder.getOrNull(1))?.requestFocus()
                    ?: heroFocusRequester.requestFocus()
            }
        },
        findCardIndex = ::findCardIndex,
        modifier = modifier,
        trackFocus = { screenHasFocus = it },
        // Settled = ready: the billboard mirrors the popular rail (empty
        // only when every rail is empty, which raises the viewmodel error),
        // and the entry above falls back to the first rail. Total-empty
        // still raises the viewmodel error state (own Retry).
        contentReady = true,
        guardStaleKey = false
    ) { clearFocusKey ->
                // Hero Movie Billboard
                item {
                    TvHeroBillboard(
                        movies = uiState.popularMovies.take(6),
                        playButtonModifier = Modifier
                            .focusRequester(heroFocusRequester)
                            .then(if (entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier),
                        onPlayClick = {
                            onRefocusKeyChange("hero")
                            onNavigateToPlayer(it.contentType, it.id, it.title, it.fullPosterUrl, it.fullBackdropUrl, it.slug)
                        },
                        onDetailsClick = {
                            onRefocusKeyChange("hero")
                            onNavigateToDetail(it.contentType, it.slug)
                        },
                        barEscapeLink = barLinkRequester,
                        onBarEscape = scrollToTop,
                        downLink = linkFor(nextRailId("hero")),
                        rotationPaused = screenHasFocus,
                        focusedMedia = focusedMedia
                    )
                }

                // Swimlane: Film Terpopuler
                if (uiState.popularMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.popular_movies_indonesia),
                            items = uiState.popularMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("popular", it.contentType, it.slug) },
                            rowId = "popular",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("popular"),
                            upLink = linkFor(prevRailId("popular")),
                            downLink = linkFor(nextRailId("popular")),
                            railLink = railGraph.railLink("popular"),
                        )
                    }
                }

                // Swimlane: Film Terbaru
                if (uiState.latestMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.latest_movies),
                            items = uiState.latestMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("latest", it.contentType, it.slug) },
                            rowId = "latest",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("latest"),
                            upLink = linkFor(prevRailId("latest")),
                            downLink = linkFor(nextRailId("latest")),
                            railLink = railGraph.railLink("latest"),
                        )
                    }
                }

                // Swimlane: Film Aksi
                if (uiState.actionMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.movies_action),
                            items = uiState.actionMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("action", it.contentType, it.slug) },
                            rowId = "action",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("action"),
                            upLink = linkFor(prevRailId("action")),
                            downLink = linkFor(nextRailId("action")),
                            railLink = railGraph.railLink("action"),
                        )
                    }
                }

                // Swimlane: Film Komedi
                if (uiState.comedyMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.movies_comedy),
                            items = uiState.comedyMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("comedy", it.contentType, it.slug) },
                            rowId = "comedy",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("comedy"),
                            upLink = linkFor(prevRailId("comedy")),
                            downLink = linkFor(nextRailId("comedy")),
                            railLink = railGraph.railLink("comedy"),
                        )
                    }
                }

                // Swimlane: Film Horor
                if (uiState.horrorMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.movies_horror),
                            items = uiState.horrorMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("horror", it.contentType, it.slug) },
                            rowId = "horror",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("horror"),
                            upLink = linkFor(prevRailId("horror")),
                            downLink = linkFor(nextRailId("horror")),
                            railLink = railGraph.railLink("horror"),
                        )
                    }
                }

                // Swimlane: Film Fiksi Ilmiah (Sci-Fi)
                if (uiState.sciFiMovies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.movies_scifi),
                            items = uiState.sciFiMovies,
                            onItemFocus = { focusedMedia = it },
                            onItemClick = { navigateToDetail("scifi", it.contentType, it.slug) },
                            rowId = "scifi",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("scifi"),
                            upLink = linkFor(prevRailId("scifi")),
                            downLink = linkFor(nextRailId("scifi")),
                            railLink = railGraph.railLink("scifi"),
                        )
                    }
                }
    }
}
