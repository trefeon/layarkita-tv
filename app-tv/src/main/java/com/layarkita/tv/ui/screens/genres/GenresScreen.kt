package com.layarkita.tv.ui.screens.genres

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.layarkita.tv.R
import com.layarkita.tv.ui.components.TvErrorState
import com.layarkita.tv.ui.components.TvLoadingSpinner
import com.layarkita.tv.ui.components.TvSizing
import com.layarkita.tv.ui.components.topEdgeBarEscape
import com.layarkita.tv.ui.components.tvArrowsViaMoveFocus
import com.layarkita.tv.ui.components.tvGridColumnCount
import com.layarkita.tv.ui.components.tvGridFocusFrame
import com.layarkita.tv.ui.components.tvMatrixNeighbors
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenresScreen(
    onNavigateToGenre: (slug: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenresViewModel = viewModel(),
    // Genre-detail return key: the originating genre slug. Hoisted in
    // AppNavigation (same reason as the Movies/Series tab keys — the grid
    // disposes on genre-detail push, so an in-screen key would die there).
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    // Bar DOWN target on the first tile (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for first-row tiles (see topEdgeBarEscape).
    barLinkRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val firstTileFocusRequester = remember { FocusRequester() }
    val screenWd = LocalConfiguration.current.screenWidthDp
    val genreCols = remember(screenWd) {
        tvGridColumnCount(
            screenWidthDp = screenWd,
            minCellWidthDp = 180,
            horizontalPaddingDp = 96,
            contentInsetDp = 24,
            spacingDp = 16
        )
    }
    val navigateToGenre: (String, String) -> Unit = { slug, name ->
        onRefocusKeyChange(slug)
        onNavigateToGenre(slug, name)
    }
    // Screen-local consume flag: the NavHost graph does not rebuild on
    // same-branch recomposition, so the consume never propagates back to
    // THIS composition (see MoviesScreen).
    var refocusConsumed by remember { mutableStateOf(false) }
    val clearFocusKey: () -> Unit = {
        refocusConsumed = true
        onRefocusKeyChange(null)
    }

    // No pending key: park on the first tile. `firstLoad` gates this to the
    // first settled run of THIS screen instance so a late data re-emission
    // never steals a restored tile's focus. Park + retry: the nav-transition
    // window drops the first request, so pull the grid to 0 and re-assert
    // (8x40ms) — entry never parks black.
    var firstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(uiState.isLoading, uiState.genres) {
        if (uiState.isLoading) return@LaunchedEffect
        val first = firstLoad
        firstLoad = false
        if (uiState.genres.isNotEmpty() && refocusKey == null && first) {
            try {
                gridState.scrollToItem(0)
            } catch (_: Exception) {}
            repeat(8) {
                try {
                    firstTileFocusRequester.requestFocus()
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(40)
            }
        }
    }

    // Return from genre detail: scroll the originating tile back into view;
    // its match effect requests focus and clears the key.
    LaunchedEffect(refocusKey, uiState.isLoading) {
        val key = refocusKey ?: return@LaunchedEffect
        if (uiState.isLoading || uiState.genres.isEmpty()) return@LaunchedEffect
        val index = uiState.genres.indexOfFirst { it.slug == key }
        if (index == -1) {
            onRefocusKeyChange(null)
            try {
                firstTileFocusRequester.requestFocus()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }
        try {
            gridState.scrollToItem(index)
        } catch (_: Exception) {
            onRefocusKeyChange(null)
            try {
                firstTileFocusRequester.requestFocus()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }
        // Watchdog: if the tile never consumes the key, park on first tile.
        kotlinx.coroutines.delay(1500)
        if (!refocusConsumed) {
            onRefocusKeyChange(null)
            try {
                firstTileFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(horizontal = 48.dp, vertical = 20.dp)
            .padding(top = 12.dp)
    ) {
        if (uiState.isLoading) {
            TvLoadingSpinner(
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.error != null || uiState.errorRes != null) {
            val errorMessage = uiState.error
                ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
            TvErrorState(
                message = errorMessage,
                onRetry = { viewModel.loadGenres() },
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.genres),
                    color = NetflixWhite,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                val tileRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(genreCols),
                    // Scale-allowance insets: 12dp each side/top covers the
                    // 1.08x ring overflow so edge tiles settle fully visible.
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer()
                ) {
                    itemsIndexed(uiState.genres, key = { _, g -> g.slug.ifBlank { g.id } }) { index, genre ->
                        val isFirst = index == 0
                        val nav = tvMatrixNeighbors(index = index, size = uiState.genres.size, cols = genreCols)
                        val tileFocusRequester = tileRequesters.getOrPut(index) { FocusRequester() }
                        if (refocusKey != null && refocusKey == genre.slug) {
                            LaunchedEffect(refocusKey) {
                                try {
                                    tileFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                                clearFocusKey()
                            }
                        }
                        Box(
                            Modifier
                                .tvGridFocusFrame(horizontalPadding = 4.dp, verticalPadding = 8.dp)
                        ) {
                            Button(
                                onClick = { navigateToGenre(genre.slug, genre.name) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NetflixDarkCard,
                                    contentColor = NetflixLightGray,
                                    focusedContainerColor = NetflixRed,
                                    focusedContentColor = NetflixWhite
                                ),
                                scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(2.5.dp, NetflixWhite)
                                    )
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .focusRequester(tileFocusRequester)
                                    .then(if (isFirst) Modifier.focusRequester(firstTileFocusRequester) else Modifier)
                                    .then(if (isFirst && entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier)
                                    .topEdgeBarEscape(
                                        barLinkRequester,
                                        force = nav.isFirstRow,
                                        onEscape = {
                                            try {
                                                gridState.scrollToItem(0)
                                            } catch (_: Exception) {}
                                        }
                                    )
                                    // All four arrows via moveFocus: tile focus
                                    // arrives requester-parked (bar DOWN / entry
                                    // park), which vetoes implicit default-search
                                    // (STUCK-1). topEdgeBarEscape above still sees
                                    // UP first on first-row tiles.
                                    .tvArrowsViaMoveFocus()
                                    .focusProperties {
                                        // Sibling-tile requester redirects poison
                                        // the node (STUCK-1 class) — geometric
                                        // default search handles the grid natively.
                                        up = if (nav.isFirstRow) (barLinkRequester ?: FocusRequester.Cancel) else FocusRequester.Default
                                        down = if (nav.downIndex == null) FocusRequester.Cancel else FocusRequester.Default
                                        left = if (nav.leftIndex == null) FocusRequester.Cancel else FocusRequester.Default
                                        right = if (nav.rightIndex == null) FocusRequester.Cancel else FocusRequester.Default
                                    }
                            ) {
                                Text(
                                    text = genre.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
