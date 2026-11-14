package com.layarkita.tv.ui.screens.genres

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.layarkita.tv.R
import com.layarkita.tv.ui.components.TvRailGraph
import com.layarkita.tv.ui.components.TvScreenScaffold
import com.layarkita.tv.ui.components.TvSwimlaneRow
import com.layarkita.tv.ui.components.topEdgeBarEscape
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenreDetailScreen(
    genreSlug: String,
    genreName: String,
    onNavigateToDetail: (contentType: String, slug: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenreDetailViewModel = viewModel(),
    // Same-card refocus key, hoisted in AppNavigation (see MoviesScreen).
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    railListStates: (String) -> LazyListState = { LazyListState() },
    // Bar DOWN target on the Back button (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for Back (see topEdgeBarEscape).
    barLinkRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val backFocusRequester = remember { FocusRequester() }
    // Rail links + column registries (see TvRailGraph): one FocusRequester
    // per rail container + one index->card map per rail, created on demand
    // and stable thereafter. Plain maps (not state): written during
    // composition, read only on key events.
    val railLinks = remember { mutableMapOf<String, FocusRequester>() }
    // Scroll-to-top on genuine content→bar escape (see topEdgeBarEscape):
    // UP from a top-edge card lands on the tabs, so pull the header back
    // into view underneath. Suspend lambda — the modifier launches it async.
    val scrollToTop: suspend () -> Unit = {
        try {
            listState.scrollToItem(0)
        } catch (_: Exception) {}
    }
    // Dynamic order: header + non-empty rails only. Empty rails are skipped
    // entirely (no outer item, no link), so UP/DOWN never lands on a
    // detached container. Rail order MUST mirror the LazyColumn items
    // below.
    val railOrder = buildList {
        add("header")
        if (uiState.movies.isNotEmpty()) add("movies")
        if (uiState.series.isNotEmpty()) add("series")
    }
    // Single wiring graph (see TvRailGraph): dynamic header + non-empty
    // rails only. Empty rails are skipped entirely. Rail order MUST mirror
    // the LazyColumn items below. Axis movement is pure focus search
    // between composed containers.
    val railGraph = TvRailGraph(
        order = railOrder,
        entryId = "header",
        entryLink = backFocusRequester,
        links = railLinks
    )
    fun prevRailId(id: String): String? = railGraph.prevRailId(id)
    fun nextRailId(id: String): String? = railGraph.nextRailId(id)
    fun linkFor(id: String?): FocusRequester? = railGraph.linkFor(id)
    val navigateToDetail: (String, String, String) -> Unit = { railId, type, slug ->
        onRefocusKeyChange("$railId/$type/$slug")
        onNavigateToDetail(type, slug)
    }

    LaunchedEffect(genreSlug) {
        viewModel.loadGenre(genreSlug)
    }

    val errorMessage: String? = if (uiState.error != null || uiState.errorRes != null) {
        uiState.error ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
    } else {
        null
    }
    val railLists = mapOf(
        "movies" to uiState.movies,
        "series" to uiState.series
    )
    // Per-rail card lookup for return-from-detail (see TvScreenScaffold).
    fun findCardIndex(railId: String, type: String, slug: String): Int =
        railLists[railId]?.indexOfFirst { it.contentType == type && it.slug == slug } ?: -1

    TvScreenScaffold(
        isLoading = uiState.isLoading,
        errorMessage = errorMessage,
        onRetry = { viewModel.loadGenre(genreSlug) },
        listState = listState,
        refocusKey = refocusKey,
        onRefocusKeyChange = onRefocusKeyChange,
        entryKeyValue = "header",
        railOrder = railOrder,
        railListStates = railListStates,
        onEntryFocus = { backFocusRequester.requestFocus() },
        findCardIndex = ::findCardIndex,
        modifier = modifier,
        onBack = onBack,
        // Settled = ready: the entry Back button is always composed, and
        // total-empty raises the viewmodel error state (own Retry) — no
        // data gate, so an empty-but-settled load still parks on Back.
        contentReady = true,
        instanceKey = genreSlug,
        entryDelayMs = 120L
    ) { clearFocusKey ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.colors(
                                containerColor = NetflixDarkCard,
                                contentColor = NetflixLightGray,
                                focusedContainerColor = NetflixRed,
                                focusedContentColor = NetflixWhite
                            ),
                            scale = ButtonDefaults.scale(focusedScale = 1.08f),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NetflixWhite)
                                )
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                            modifier = Modifier
                                .focusRequester(backFocusRequester)
                                .then(if (entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                                        coroutineScope.launch {
                                            try {
                                                listState.scrollToItem(0)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    down = linkFor(nextRailId("header")) ?: FocusRequester.Cancel
                                }
                                .topEdgeBarEscape(barLinkRequester, force = true, onEscape = scrollToTop)
                        ) {
                            Text(text = stringResource(R.string.back), fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = genreName,
                            color = NetflixWhite,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (uiState.movies.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.movies),
                            items = uiState.movies,
                            onItemClick = { navigateToDetail("movies", it.contentType, it.slug) },
                            rowId = "movies",
                            focusKey = refocusKey,
                            onFocusKeyConsumed = clearFocusKey,
                            listState = railListStates("movies"),
                            upLink = linkFor(prevRailId("movies")),
                            downLink = linkFor(nextRailId("movies")),
                            railLink = railGraph.railLink("movies"),
                        )
                    }
                }
                if (uiState.series.isNotEmpty()) {
                    item {
                        TvSwimlaneRow(
                            title = stringResource(R.string.series),
                            items = uiState.series,
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
    }
}
