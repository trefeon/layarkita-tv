package com.layarkita.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Border
import androidx.tv.material3.Text
import com.layarkita.tv.R
import com.layarkita.tv.ui.components.TvLoadingSpinner
import com.layarkita.tv.ui.components.TvMovieCard
import com.layarkita.tv.ui.components.TvTextField
import com.layarkita.tv.ui.components.tvGridColumnCount
import com.layarkita.tv.ui.components.tvGridFocusFrame
import com.layarkita.tv.ui.components.tvMatrixNeighbors
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToDetail: (contentType: String, slug: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(),
    // Deep-link query (global SEARCH / launcher voice): consumed once into
    // the view model; null keeps the current field content.
    initialQuery: String? = null,
    // Detail-return key: "type/slug" of the originating result. Hoisted in
    // AppNavigation (same reason as the Genres grid key — the results grid
    // disposes on detail push, so an in-screen key would die there).
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    // Bar DOWN target on the search field (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for the field (see topEdgeBarEscape): at rest the
    // field sits under the transparent bar and plain UP dies.
    barLinkRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Grid freshness: the visible results belong to the live field text (not
    // a stale or still-debouncing query). Only fresh results are a D-pad DOWN
    // target; mid-typing DOWN stays in the field so focus never jumps rows.
    val resultsFresh = !uiState.isLoading &&
        uiState.query.isNotBlank() &&
        uiState.results.isNotEmpty() &&
        uiState.searchedQuery == uiState.query.trim()

    val searchFieldFocusRequester = remember { FocusRequester() }
    val clearButtonFocusRequester = remember { FocusRequester() }
    val firstTagFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val errorRetryFocusRequester = remember { FocusRequester() }

    var isInputFocused by remember { mutableStateOf(false) }
    var isClearFocused by remember { mutableStateOf(false) }
    var pendingFocusOnResults by remember { mutableStateOf(false) }

    val navigateToDetail: (String, String) -> Unit = { type, slug ->
        onRefocusKeyChange("$type/$slug")
        onNavigateToDetail(type, slug)
    }
    // Screen-local consume flag: the NavHost graph does not rebuild on
    // same-branch recomposition, so the consume never propagates back to
    // THIS composition (see MoviesScreen).
    var refocusConsumed by remember { mutableStateOf(false) }
    val clearFocusKey: () -> Unit = {
        refocusConsumed = true
        onRefocusKeyChange(null)
    }
    val latestKey = rememberUpdatedState(refocusKey)

    // Deep-link entry: a global-search voice query lands here from
    // AppNavigation (onNewIntent QUERY / EXTRA_MEDIA_TITLE routing).
    LaunchedEffect(initialQuery) {
        val query = initialQuery
        if (!query.isNullOrBlank() && query != uiState.query) {
            viewModel.setQuery(query)
        }
    }

    val context = LocalContext.current
    val quickSearches = remember(context) {
        context.resources.getStringArray(R.array.quick_searches).toList()
    }
    // Column-aware grid edges (absolute control): Adaptive(135dp) span varies
    // with panel width, so derive it from the real screen width with the same
    // floor math as the grid (viewport = screen − 48dp sides − 12dp insets).
    // First-column LEFT stays, last-column RIGHT stays, last-row DOWN stays,
    // first-row UP returns to the field; intra-grid moves stay on geometry.
    val screenWd = LocalConfiguration.current.screenWidthDp
    val searchCols = remember(screenWd) {
        tvGridColumnCount(
            screenWidthDp = screenWd,
            minCellWidthDp = 140,
            horizontalPaddingDp = 96,
            contentInsetDp = 24,
            spacingDp = 16
        )
    }

    // Request initial focus on the search input bar when screen is first displayed.
    // Skipped when a detail-return key is pending: the return effect below
    // owns focus then, and a field request would win the race and strand the
    // grid (same disposal-swap reason the key is hoisted). Park + retry
    // (8x40ms): the nav-transition window drops the first request.
    val escapeScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (latestKey.value != null) return@LaunchedEffect
        repeat(8) {
            try {
                searchFieldFocusRequester.requestFocus()
            } catch (_: Exception) {}
            delay(40)
        }
    }

    // Automatically transition focus to results once loaded if requested by user action
    // Grid-compose race: the first-cell requester attaches with the grid,
    // which can lag the state commit by a frame — retry briefly (same 8x40ms
    // idiom as the initial field focus above) instead of stranding focus.
    LaunchedEffect(uiState.isLoading, uiState.results, pendingFocusOnResults) {
        if (pendingFocusOnResults && !uiState.isLoading) {
            if (uiState.results.isNotEmpty()) {
                pendingFocusOnResults = false
                repeat(8) {
                    try {
                        firstResultFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                    delay(40)
                }
            } else {
                pendingFocusOnResults = false
                try {
                    searchFieldFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }
    // Land on Retry as soon as a search failure renders, so D-pad is never stranded.
    LaunchedEffect(uiState.error, uiState.errorRes) {
        if (uiState.error != null || uiState.errorRes != null) {
            try {
                errorRetryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // Return from detail: scroll the originating result back into view; its
    // match effect requests focus and clears the key. Null key keeps the
    // field-park above, so first entry still lands on the search field.
    LaunchedEffect(refocusKey, uiState.isLoading, uiState.results) {
        val key = refocusKey ?: return@LaunchedEffect
        if (uiState.isLoading || uiState.results.isEmpty()) return@LaunchedEffect
        val index = uiState.results.indexOfFirst { "${it.contentType}/${it.slug}" == key }
        if (index == -1) {
            onRefocusKeyChange(null)
            try {
                searchFieldFocusRequester.requestFocus()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }
        try {
            gridState.scrollToItem(index)
        } catch (_: Exception) {
            onRefocusKeyChange(null)
            try {
                searchFieldFocusRequester.requestFocus()
            } catch (_: Exception) {}
            return@LaunchedEffect
        }
        // Watchdog: if the cell never consumes the key, park on the field.
        kotlinx.coroutines.delay(1500)
        if (!refocusConsumed) {
            onRefocusKeyChange(null)
            try {
                searchFieldFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .padding(horizontal = 48.dp, vertical = 20.dp)
            .padding(top = 12.dp)
    ) {
        // Search input: TvTextField owns the visuals, BACK-unfocus, IME and
        // UP bar-escape; the field holds no per-key UP duplicate and the
        // input-session trap is handled inside the component. The clear key
        // stays a plain Button beside it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                hint = stringResource(R.string.search_placeholder),
                leadingIcon = { focused ->
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = if (focused) NetflixWhite else NetflixLightGray
                    )
                },
                textStyle = TextStyle(
                    color = NetflixWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                imeAction = ImeAction.Search,
                onImeAction = {
                    keyboardController?.hide()
                    // Immediate-search fast path: skip the settle window so
                    // the full query searches at once, then land on the grid
                    // when it arrives. Already-fresh results focus at once.
                    viewModel.searchNow()
                    if (resultsFresh) {
                        try {
                            firstResultFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    } else {
                        pendingFocusOnResults = true
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                fieldModifier = Modifier
                    .focusRequester(searchFieldFocusRequester)
                    .then(if (entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier)
                    .focusProperties {
                        down = if (uiState.query.isBlank()) {
                            firstTagFocusRequester
                        } else if (resultsFresh) {
                            firstResultFocusRequester
                        } else {
                            FocusRequester.Cancel
                        }
                        right = if (uiState.query.isNotEmpty()) clearButtonFocusRequester else FocusRequester.Default
                    }
                    .onFocusChanged { isInputFocused = it.isFocused },
                barEscapeLink = barLinkRequester,
                onBarEscape = { gridState.scrollToItem(0) },
                onPreviewKey = { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (keyEvent.key) {
                            Key.DirectionDown -> {
                                keyboardController?.hide()
                                if (uiState.query.isBlank()) {
                                    try {
                                        firstTagFocusRequester.requestFocus()
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                } else if (resultsFresh) {
                                    try {
                                        firstResultFocusRequester.requestFocus()
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                } else {
                                    // Search still settling (or stale rows on
                                    // screen): stay in the field, keep typing.
                                    true
                                }
                            }
                            Key.DirectionRight -> {
                                if (uiState.query.isNotEmpty()) {
                                    try {
                                        clearButtonFocusRequester.requestFocus()
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                }
            )

            if (uiState.query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.onQueryChange("")
                        try {
                            searchFieldFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NetflixBlack,
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
                        .focusRequester(clearButtonFocusRequester)
                        .focusProperties {
                            left = searchFieldFocusRequester
                            if (resultsFresh) {
                                down = firstResultFocusRequester
                            }
                        }
                        .onFocusChanged { isClearFocused = it.isFocused }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionLeft -> {
                                        try {
                                            searchFieldFocusRequester.requestFocus()
                                            true
                                        } catch (_: Exception) {
                                            false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        if (resultsFresh) {
                                            try {
                                                firstResultFocusRequester.requestFocus()
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                        } else {
                                            true
                                        }
                                    }
                                    // UP goes home to the top bar (same input-
                                    // session release as the field, for safety),
                                    // pulling the results grid back to 0.
                                    Key.DirectionUp -> {
                                        val link = barLinkRequester
                                        if (link != null) {
                                            try {
                                                focusManager.clearFocus(force = true)
                                            } catch (_: Exception) {}
                                            escapeScope.launch {
                                                try {
                                                    gridState.scrollToItem(0)
                                                } catch (_: Exception) {}
                                            }
                                            try {
                                                link.requestFocus()
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                ) {
                    Icon(imageVector = Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Search Tags for TV Remote (only shown when query is empty, matching mobile UX)
        if (uiState.query.isBlank()) {
            val tagRequesters = remember { mutableMapOf<Int, FocusRequester>() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties {
                        up = searchFieldFocusRequester
                    }
            ) {
                Text(
                    text = stringResource(R.string.popular_searches),
                    color = NetflixLightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // Scale-allowance insets: the 1.08x tag zoom + 2dp ring
                    // must settle inside the viewport at both row ends.
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier.focusRestorer()
                ) {
                    itemsIndexed(quickSearches) { tagIndex, tag ->
                        val isFirstTag = tagIndex == 0
                        val isLastTag = tagIndex == quickSearches.lastIndex
                        val tagFocusRequester = tagRequesters.getOrPut(tagIndex) { FocusRequester() }
                        // Focus frame: headroom idiom so edge tags never crop their ring.
                        Box(
                            Modifier
                                .tvGridFocusFrame(horizontalPadding = 4.dp, verticalPadding = 8.dp)
                        ) {
                            Button(
                                onClick = {
                                    // Complete query: hide the keyboard (no
                                    // refinement typing needed) and search at
                                    // once. No field-focus bounce — it would
                                    // pop Gboard over the results and the
                                    // dying tag row would strand focus. The
                                    // pending transfer lands on the grid.
                                    keyboardController?.hide()
                                    viewModel.searchQuery(tag)
                                    pendingFocusOnResults = true
                                },
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
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                                modifier = Modifier
                                    .focusRequester(tagFocusRequester)
                                    .then(if (isFirstTag) Modifier.focusRequester(firstTagFocusRequester) else Modifier)
                                    .focusProperties {
                                        up = searchFieldFocusRequester
                                        left = if (isFirstTag) FocusRequester.Cancel else (tagRequesters[tagIndex - 1] ?: FocusRequester.Default)
                                        right = if (isLastTag) FocusRequester.Cancel else (tagRequesters[tagIndex + 1] ?: FocusRequester.Default)
                                    }
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            when (keyEvent.key) {
                                                Key.DirectionUp -> {
                                                    try {
                                                        searchFieldFocusRequester.requestFocus()
                                                        true
                                                    } catch (_: Exception) {
                                                        false
                                                    }
                                                }
                                                Key.DirectionDown -> {
                                                    true // Consume to prevent focus drop
                                                }
                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                            ) {
                                Text(text = tag, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Results Section
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                TvLoadingSpinner(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.error != null || uiState.errorRes != null) {
                val errorMessage = uiState.error
                    ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = errorMessage,
                        color = NetflixWhite,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.searchNow() },
                        colors = ButtonDefaults.colors(
                            containerColor = NetflixRed,
                            contentColor = NetflixWhite,
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
                        modifier = Modifier.focusRequester(errorRetryFocusRequester)
                    ) {
                        Text(text = stringResource(R.string.try_again), fontSize = 14.sp)
                    }
                }
            } else if (uiState.results.isEmpty() && uiState.query.isNotBlank()) {
                Text(
                    text = stringResource(R.string.no_results_for, uiState.query),
                    color = NetflixLightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else if (uiState.results.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_hint),
                    color = NetflixLightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val cellRequesters = remember { mutableMapOf<Int, FocusRequester>() }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(searchCols),
                    // Scale-allowance insets: 12dp each side/top covers the
                    // 1.12x ring overflow so edge cards settle fully visible.
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
                        .focusProperties {
                            up = searchFieldFocusRequester
                        }
                ) {
                    itemsIndexed(uiState.results, key = { _, it -> it.id }) { index, item ->
                        val isFirst = index == 0
                        val nav = tvMatrixNeighbors(index = index, size = uiState.results.size, cols = searchCols)
                        val cellFocusRequester = cellRequesters.getOrPut(index) { FocusRequester() }
                        if (refocusKey != null && refocusKey == "${item.contentType}/${item.slug}") {
                            LaunchedEffect(refocusKey) {
                                try {
                                    cellFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                                clearFocusKey()
                            }
                        }
                        Box(
                            Modifier
                                .tvGridFocusFrame(horizontalPadding = 4.dp, verticalPadding = 8.dp)
                        ) {
                            TvMovieCard(
                                title = item.title,
                                posterUrl = item.fullPosterUrl,
                                rating = item.formattedRating,
                                quality = item.quality ?: if (item.contentType == "tv_series") {
                                    stringResource(R.string.content_badge_series)
                                } else {
                                    stringResource(R.string.content_badge_movie)
                                },
                                fillWidth = true,
                                onClick = { navigateToDetail(item.contentType, item.slug) },
                                modifier = Modifier
                                    .focusRequester(cellFocusRequester)
                                    .then(if (isFirst) Modifier.focusRequester(firstResultFocusRequester) else Modifier)
                                    .focusProperties {
                                        up = if (nav.isFirstRow) searchFieldFocusRequester else (nav.upIndex?.let { cellRequesters[it] } ?: FocusRequester.Default)
                                        down = if (nav.downIndex == null) FocusRequester.Cancel else (cellRequesters[nav.downIndex] ?: FocusRequester.Default)
                                        left = if (nav.leftIndex == null) FocusRequester.Cancel else (cellRequesters[nav.leftIndex] ?: FocusRequester.Default)
                                        right = if (nav.rightIndex == null) FocusRequester.Cancel else (cellRequesters[nav.rightIndex] ?: FocusRequester.Default)
                                    }
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp && nav.isFirstRow) {
                                            escapeScope.launch {
                                                try {
                                                    gridState.scrollToItem(0)
                                                } catch (_: Exception) {}
                                            }
                                            try {
                                                searchFieldFocusRequester.requestFocus()
                                                true
                                            } catch (_: Exception) {
                                                false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}
