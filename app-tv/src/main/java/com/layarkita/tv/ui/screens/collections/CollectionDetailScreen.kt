package com.layarkita.tv.ui.screens.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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

/**
 * Collection detail: one rail per phase section (section titles map to TV
 * rows 1:1), cards navigate via the existing content-detail flow. Mirrors
 * [com.layarkita.tv.ui.screens.genres.GenreDetailScreen] (Back-owned entry
 * focus, same-card refocus, section rails) — sections are dynamic (phase
 * rails) instead of the fixed movies/series pair.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collectionSlug: String,
    collectionName: String,
    onNavigateToDetail: (contentType: String, slug: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionDetailViewModel = viewModel(),
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
    // Declarative rail links (see TvRailGraph): one container link per
    // section rail. Map is stable (remembered) so links survive
    // recomposition; rails are dynamic so we key by railId.
    val railLinks = remember { mutableMapOf<String, FocusRequester>() }
    // Scroll-to-top on genuine content→bar escape (see topEdgeBarEscape).
    // Suspend lambda — the modifier launches it async.
    val scrollToTop: suspend () -> Unit = {
        try {
            listState.scrollToItem(0)
        } catch (_: Exception) {}
    }
    val navigateToDetail: (String, String, String) -> Unit = { railId, type, slug ->
        onRefocusKeyChange("$railId/$type/$slug")
        onNavigateToDetail(type, slug)
    }

    // Detail rails render MovieItem; section entries map once per load.
    val sectionRails = remember(uiState.sections) {
        uiState.sections.map { section ->
            val railId = section.slug.ifBlank { section.id }
            railId to section.items.map { it.toMovieItem() }
        }
    }
    // Focus order skips empty sections entirely (no outer item, no link),
    // so UP/DOWN never lands on a detached container. sectionRails keeps
    // the data; visibleRails drives display + focus. Rail order MUST mirror
    // the LazyColumn items below (header, then visible sections).
    val visibleRails = sectionRails.filter { it.second.isNotEmpty() }
    val railOrder = listOf("header") + visibleRails.map { it.first }
    // Single wiring graph (see TvRailGraph): header + visible sections only.
    // Empty sections are skipped entirely. Rail order MUST mirror the
    // LazyColumn items below (header, then visible sections in order).
    val railGraph = TvRailGraph(
        order = railOrder,
        entryId = "header",
        entryLink = backFocusRequester,
        links = railLinks
    )
    fun prevRailId(id: String): String? = railGraph.prevRailId(id)
    fun nextRailId(id: String): String? = railGraph.nextRailId(id)
    fun linkFor(id: String?): FocusRequester? = railGraph.linkFor(id)

    LaunchedEffect(collectionSlug) {
        viewModel.loadCollection(collectionSlug)
    }

    val errorMessage: String? = if (uiState.error != null || uiState.errorRes != null) {
        uiState.error ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
    } else {
        null
    }
    // Per-rail card lookup for return-from-detail (see TvScreenScaffold).
    fun findCardIndex(railId: String, type: String, slug: String): Int =
        visibleRails.firstOrNull { it.first == railId }
            ?.second?.indexOfFirst { it.contentType == type && it.slug == slug } ?: -1
    TvScreenScaffold(
        isLoading = uiState.isLoading,
        errorMessage = errorMessage,
        onRetry = { viewModel.loadCollection(collectionSlug) },
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
        bottomInset = 32.dp,
        restoreFocus = true,
        // Settled = ready: the entry Back button is always composed, and
        // total-empty raises the viewmodel error state (own Retry) — no
        // data gate, so an empty-but-settled load still parks on Back.
        contentReady = true,
        instanceKey = collectionSlug,
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
                            text = collectionName.ifBlank { collectionSlug },
                            color = NetflixWhite,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                itemsIndexed(visibleRails, key = { _, r -> r.first.ifBlank { r.second.firstOrNull()?.id ?: "" } }) { _, (railId, movies) ->
                    TvSwimlaneRow(
                        title = uiState.sections.firstOrNull {
                            it.slug.ifBlank { it.id } == railId
                        }?.title?.ifBlank { railId } ?: railId,
                        items = movies,
                        onItemClick = { navigateToDetail(railId, it.contentType, it.slug) },
                        rowId = railId,
                        focusKey = refocusKey,
                        onFocusKeyConsumed = clearFocusKey,
                        listState = railListStates(railId),
                        upLink = linkFor(prevRailId(railId)),
                        downLink = linkFor(nextRailId(railId)),
                        railLink = railGraph.railLink(railId),
                    )
                }
    }
}
