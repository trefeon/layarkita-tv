package com.idlix.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.idlix.tv.ui.components.TvRailGraph
import com.idlix.tv.ui.components.TvScreenScaffold
import androidx.lifecycle.viewmodel.compose.viewModel
import com.idlix.tv.R
import com.idlix.data.model.MovieItem
import com.idlix.data.model.CollectionItem
import com.idlix.data.model.WatchHistoryItem
import com.idlix.tv.ui.components.TvCollectionsRow
import com.idlix.tv.ui.components.TvContinueWatchingRow
import com.idlix.tv.ui.components.TvHeroBillboard
import com.idlix.tv.ui.components.TvSwimlaneRow
import com.idlix.tv.ui.components.TvTop10Row

@Composable
fun HomeScreen(
    onNavigateToDetail: (contentType: String, slug: String) -> Unit,
    onNavigateToPlayer: (contentType: String, contentId: String, title: String, poster: String?, backdrop: String?, slug: String?, season: Int?, episode: Int?) -> Unit,
    onNavigateToCollection: (slug: String, name: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
    // Same-card refocus key, hoisted in AppNavigation above the dual-NavHost
    // switch (in-screen remember/rememberSaveable dies with the host dispose).
    // "railId/type/slug", "hero" for hero-initiated detail, null when idle.
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    // Hoisted scroll states (see AppNavigation): the return effect scrolls
    // these back so the originating card recomposes and takes focus.
    listState: LazyListState = rememberLazyListState(),
    railListStates: (String) -> LazyListState = { LazyListState() },
    // Bar DOWN target, attached to the hero Play button alongside the local
    // entry requester (see AppNavigation.contentEntryLink).
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
    // Rail links (see TvRailGraph): one FocusRequester per rail container,
    // created on demand and stable thereafter.
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
        if (uiState.continueWatching.isNotEmpty()) add("continue")
        if (uiState.trending.isNotEmpty()) add("trending")
        if (uiState.popularSeries.isNotEmpty()) add("series")
        if (uiState.actionMovies.isNotEmpty()) add("action")
        if (uiState.collections.isNotEmpty()) add("collections")
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
    // The matching rail card self-requests focus and clears the key via
    // onRefocusKeyChange(null); a stale/gone key falls back to hero Play
    // after a short settle window. Null key = cold launch / tab entry, which
    // parks on hero Play exactly as before.
    val navigateToDetail: (String, String, String) -> Unit = { railId, type, slug ->
        onRefocusKeyChange("$railId/$type/$slug")
        onNavigateToDetail(type, slug)
    }
    val navigateToCollection: (CollectionItem) -> Unit = { collection ->
        onRefocusKeyChange("collections/collection/${collection.slug}")
        onNavigateToCollection(collection.slug, collection.title)
    }
    // Refresh continue watching list on screen entrance / return
    LaunchedEffect(Unit) {
        viewModel.refreshContinueWatching()
    }

    val errorMessage: String? = if (uiState.error != null || uiState.errorRes != null) {
        uiState.error ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
    } else {
        null
    }
    // Per-rail card lookup for return-from-detail (see TvScreenScaffold):
    // note series/collections match slug-only.
    fun findCardIndex(railId: String, type: String, slug: String): Int = when (railId) {
        "continue" -> uiState.continueWatching.indexOfFirst { it.contentType == type && it.contentId == slug }
        "trending" -> uiState.trending.indexOfFirst { it.contentType == type && it.slug == slug }
        "series" -> uiState.popularSeries.indexOfFirst { it.slug == slug }
        "action" -> uiState.actionMovies.indexOfFirst { it.contentType == type && it.slug == slug }
        "collections" -> uiState.collections.indexOfFirst { it.slug == slug }
        else -> -1
    }

    TvScreenScaffold(
        isLoading = uiState.isLoading,
        errorMessage = errorMessage,
        onRetry = { viewModel.loadHomeData() },
        listState = listState,
        refocusKey = refocusKey,
        onRefocusKeyChange = onRefocusKeyChange,
        entryKeyValue = "hero",
        railOrder = railOrder,
        railListStates = railListStates,
        onEntryFocus = {
            // Hero-first, rail fallback: a partial mirror outage can leave
            // the curated hero empty while rails still render — parking the
            // detached Play requester would strand focus on a tab root whose
            // BACK is consumed. Fall back to the first content rail (its
            // container forwards into the first card via focusRestorer).
            if (homeHeroMovies(uiState.featured, uiState.trending, uiState.popularMovies).isNotEmpty()) {
                heroFocusRequester.requestFocus()
            } else {
                linkFor(railOrder.getOrNull(1))?.requestFocus()
                    ?: heroFocusRequester.requestFocus()
            }
        },
        findCardIndex = ::findCardIndex,
        modifier = modifier,
        trackFocus = { screenHasFocus = it },
        // Settled = ready: the billboard degrades featured → trending →
        // popular (never blank while data exists) and the entry above falls
        // back to the first rail, so parking is safe without a hero gate.
        // Total-empty still raises the viewmodel error state (own Retry).
        contentReady = true
    ) { clearFocusKey ->
                // 1. Dynamic Hero Billboard
                item {
                    TvHeroBillboard(
                        movies = homeHeroMovies(uiState.featured, uiState.trending, uiState.popularMovies),
                        playButtonModifier = Modifier
                            .focusRequester(heroFocusRequester)
                            .then(if (entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier),
                        onPlayClick = { item ->
                            onRefocusKeyChange("hero")
                            if (item.contentType == HeroCollectionType) {
                                onNavigateToCollection(item.slug, item.title)
                            } else if (item.contentType == "tv_series") {
                                onNavigateToDetail(item.contentType, item.slug)
                            } else {
                                // Resume-aware: a focused continue card maps
                                // back to its history entry, so Play resumes
                                // with stored season/episode like the rail.
                                val resume = uiState.continueWatching.firstOrNull { it.contentId == item.id }
                                if (resume != null) {
                                    onNavigateToPlayer(
                                        resume.contentType,
                                        resume.contentId,
                                        resume.title,
                                        resume.posterUrl,
                                        resume.backdropUrl,
                                        resume.slug,
                                        resume.seasonNumber,
                                        resume.episodeNumber
                                    )
                                } else {
                                    onNavigateToPlayer(
                                        item.contentType,
                                        item.id,
                                        item.title,
                                        item.fullPosterUrl,
                                        item.fullBackdropUrl,
                                        item.slug,
                                        null,
                                        null
                                    )
                                }
                            }
                        },
                        onDetailsClick = { item ->
                            onRefocusKeyChange("hero")
                            if (item.contentType == HeroCollectionType) {
                                onNavigateToCollection(item.slug, item.title)
                            } else if (item.contentType == "episode") {
                                // Episodes belong to a series (slug is the
                                // series slug): open the series detail.
                                onNavigateToDetail("tv_series", item.slug)
                            } else {
                                onNavigateToDetail(item.contentType, item.slug)
                            }
                        },
                        barEscapeLink = barLinkRequester,
                        onBarEscape = scrollToTop,
                        downLink = linkFor(nextRailId("hero")),
                        rotationPaused = screenHasFocus,
                        focusedMedia = focusedMedia
                    )
                }

                // 2. Bar Paling Pertama: Lanjutkan Menonton (hanya muncul jika ada histori yang belum kelar)
                if (uiState.continueWatching.isNotEmpty()) {
                    item {
                        TvContinueWatchingRow(
                            title = stringResource(R.string.continue_watching),
                            items = uiState.continueWatching,
                            onItemFocus = { focusedMedia = it.toHeroMovie() },
                            onItemClick = { item ->
                                onRefocusKeyChange("continue/${item.contentType}/${item.contentId}")
                                onNavigateToPlayer(
                                    item.contentType,
                                    item.contentId,
                                    item.title,
                                    item.posterUrl,
                                    item.backdropUrl,
                                    item.slug,
                                    item.seasonNumber,
                                    item.episodeNumber
                                )
                            },
                            rowId = "continue",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("continue"),
                            upLink = linkFor(prevRailId("continue")),
                            downLink = linkFor(nextRailId("continue")),
                            railLink = railGraph.railLink("continue"),
                        )
                    }
                }

                // 3. Swimlane: Sedang Tren Sekarang (with ranks #1..15 and All/Movies/TV Series filter hint)
                if (uiState.trending.isNotEmpty()) {
                    item {
                    TvSwimlaneRow(
                        title = stringResource(R.string.trending_now),
                        items = uiState.trending,
                        showRanks = true,
                        onItemFocus = { focusedMedia = it },
                        onItemClick = { navigateToDetail("trending", it.contentType, it.slug) },
                        rowId = "trending",
                        focusKey = refocusKey,
                        onFocusKeyConsumed = clearFocusKey,
                        listState = railListStates("trending"),
                        upLink = linkFor(prevRailId("trending")),
                        downLink = linkFor(nextRailId("trending")),
                        railLink = railGraph.railLink("trending"),
                    )
                    }
                }

                // 4. Swimlane: Serial TV Pilihan
                if (uiState.popularSeries.isNotEmpty()) {
                    item {
                    val seriesAsMovies = remember(uiState.popularSeries) {
                        uiState.popularSeries.map { s ->
                            MovieItem(
                                id = s.id,
                                title = s.title,
                                slug = s.slug,
                                posterPath = s.posterPath,
                                backdropPath = s.backdropPath,
                                voteAverage = s.voteAverage,
                                contentType = "tv_series"
                            )
                        }
                    }
                    TvSwimlaneRow(
                        title = stringResource(R.string.popular_series),
                        items = seriesAsMovies,
                        onItemFocus = { focusedMedia = it },
                        onItemClick = { navigateToDetail("series", it.contentType, it.slug) },
                        rowId = "series",
                        focusKey = refocusKey,
                        onFocusKeyConsumed = clearFocusKey,
                        listState = railListStates("series"),
                        upLink = linkFor(prevRailId("series")),
                        downLink = linkFor(nextRailId("series")),
                        railLink = railGraph.railLink("series"),
                    )
                    }
                }

                // 6. Swimlane: Film Aksi
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

                // 7. Swimlane: Collections (curated universes/franchises → detail phases).
                // Neighbor links resolve via prev/nextRailId, so empty rails
                // are skipped with no special-casing here.
                if (uiState.collections.isNotEmpty()) {
                    item {
                        TvCollectionsRow(
                            title = stringResource(R.string.collections),
                            items = uiState.collections,
                            onItemFocus = { focusedMedia = it.toHeroMovie() },
                            onItemClick = { navigateToCollection(it) },
                            rowId = "collections",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("collections"),
                            upLink = linkFor(prevRailId("collections")),
                            downLink = linkFor(nextRailId("collections")),
                            railLink = railGraph.railLink("collections"),
                        )
                    }
                }
    }
}

// Dedicated hero content type for focused collection cards: routes hero CTAs
// to the collection detail instead of the player (see onPlayClick above).
private const val HeroCollectionType = "collection"

// Collection rail focus → hero display (Flixclusive focusedMedia): paths are
// raw TMDB paths like the list model, so map straight across.
private fun CollectionItem.toHeroMovie(): MovieItem = MovieItem(
    id = id,
    title = title,
    slug = slug,
    posterPath = posterPath,
    backdropPath = backdropPath,
    contentType = HeroCollectionType
)

// Continue-watching focus → hero display: history stores FULL image urls
// (see PlayerViewModel.recordStartPlayback), so strip to TMDB paths for the
// list model. Unparseable urls map to null (hero falls back to carousel).
private fun WatchHistoryItem.toHeroMovie(): MovieItem = MovieItem(
    id = contentId,
    title = title,
    slug = slug.orEmpty(),
    posterPath = posterUrl?.toTmdbPath(),
    backdropPath = backdropUrl?.toTmdbPath(),
    contentType = contentType
)

// Full TMDB url (".../t/p/w1280/abc.jpg") → raw path ("/abc.jpg"); raw paths
// pass through; foreign/blank urls → null (no art, hero keeps carousel).
private fun String.toTmdbPath(): String? {
    if (startsWith("/")) return this
    val marker = "/t/p/"
    val i = indexOf(marker)
    if (i < 0) return null
    val rest = substring(i + marker.length).substringAfter("/", "")
    return "/$rest".takeIf { rest.isNotEmpty() }
}

/**
 * Hero billboard source (Home): curated featured first, trending fallback,
 * popular fallback. Mirrors the [HomeViewModel] heroMovie chain
 * (featured ?: trending ?: popular) so the billboard and the entry gate
 * agree: the billboard is empty only when all three are empty, in which case
 * every rail is also empty and the viewmodel raises the error state instead.
 */
internal fun homeHeroMovies(
    featured: List<MovieItem>,
    trending: List<MovieItem>,
    popular: List<MovieItem>
): List<MovieItem> =
    featured.ifEmpty { trending.take(8) }.ifEmpty { popular.take(8) }
