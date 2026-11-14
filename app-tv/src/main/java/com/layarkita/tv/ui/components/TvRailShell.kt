package com.layarkita.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixWhite
import kotlinx.coroutines.launch

/**
 * Single shared rail shell for every TV swimlane (posters, Top-10, continue,
 * collections). Owns the duplicated chrome every rail had copy-pasted:
 * title + [LazyRow] container ([railLink] + exactly one [focusRestorer] +
 * 48/8 insets) + card focus frame ([railFocusFrame] +
 * [TvSizing.RailFocusVertical]) + declarative D-pad links
 * ([focusProperties] up/down/left/right) with Cancel terminals (col-0 LEFT,
 * last-cell RIGHT, null-[downLink] DOWN).
 *
 * No imperative hops: axis movement is pure focus search across the
 * composed TV lazy layouts. UP/DOWN resolve through the neighbor rail's
 * container link (never into a lazy item — detached nodes stay out of the
 * graph), LEFT/RIGHT walk the row. Empty rails are skipped entirely by the
 * owning screen's dynamic [TvRailGraph.order], so UP/DOWN never lands on a
 * detached container.
 *
 * Visual variants stay OUT of the shell: callers render their own card via
 * [cardContent] (plain poster, Top-10 numeral + compact poster, wide continue
 * card, collection tile) using the provided [cardModifier], which already
 * carries the card's [focusRequester]. Top-10's only shell difference is the
 * wider [leadingInset] (64dp) for the oversized numeral.
 *
 * Refocus contract (unchanged): the owning screen passes its pending key plus
 * this rail's [rowId]; the card whose [focusKeyFor] matches self-requests
 * focus and the screen clears the key via [onFocusKeyConsumed]. Null key =
 * no-op, so cold launch and unrelated recompositions are untouched.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun <T> TvRailShell(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    rowId: String = "",
    focusKey: String? = null,
    onFocusKeyConsumed: () -> Unit = {},
    focusKeyFor: (T) -> String,
    itemKey: (index: Int, item: T) -> Any,
    listState: LazyListState = rememberLazyListState(),
    upLink: FocusRequester? = null,
    downLink: FocusRequester? = null,
    railLink: FocusRequester? = null,
    leadingInset: Dp = 48.dp,
    trailingInset: Dp = 48.dp,
    showFilterHint: Boolean = false,
    cardContent: @Composable (item: T, index: Int, cardModifier: Modifier, isLast: Boolean) -> Unit
) {
    if (items.isEmpty()) return

    val outerListState = LocalTvOuterListState.current
    val railScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var lastFocusedIndex by rememberSaveable(rowId) { mutableIntStateOf(0) }
    val cardRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val restorerFallbackRequester = remember { FocusRequester() }
    val restorerTargetIndex = lastFocusedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    val topBarClearancePx = with(LocalDensity.current) { TvTopBarHeight.toPx() }
    var columnHeightPx by remember { mutableFloatStateOf(0f) }
    val railColumnResponder = remember(topBarClearancePx) {
        object : BringIntoViewResponder {
            @ExperimentalFoundationApi
            override fun calculateRectForParent(localRect: Rect): Rect {
                val bottom = if (columnHeightPx > 0f) columnHeightPx else localRect.bottom
                return Rect(
                    left = localRect.left,
                    top = -topBarClearancePx,
                    right = localRect.right,
                    bottom = bottom
                )
            }

            @ExperimentalFoundationApi
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { columnHeightPx = it.height.toFloat() }
            .bringIntoViewResponder(railColumnResponder)
            // Mixed-input safety net: unconsumed arrows from this rail's
            // cards re-park on the last-known card instead of dying. Healthy
            // paths consume before bubbling, so D-pad-only behavior is
            // unchanged (terminals re-park on the already-focused card).
            .tvDpadFocusRecovery(restorerFallbackRequester)
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = leadingInset, end = trailingInset, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = NetflixWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (showFilterHint) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "All",
                        color = NetflixWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Movies",
                        color = NetflixLightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "TV Series",
                        color = NetflixLightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(
                start = leadingInset,
                end = trailingInset,
                top = 6.dp,
                bottom = 6.dp
            ),
            modifier = Modifier
                .then(if (railLink != null) Modifier.focusRequester(railLink) else Modifier)
                .focusRestorer { restorerFallbackRequester }
        ) {
            itemsIndexed(items, key = { index, item -> itemKey(index, item) }) { index, item ->
                val cardFocusRequester = cardRequesters.getOrPut(index) { FocusRequester() }
                if (focusKey != null && focusKey == focusKeyFor(item)) {
                    LaunchedEffect(focusKey) {
                        try {
                            cardFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                        onFocusKeyConsumed()
                    }
                }
                val isLast = index == items.lastIndex
                Box(
                    Modifier.railFocusFrame()
                ) {
                    val cardModifier = Modifier
                        .focusRequester(cardFocusRequester)
                        .then(if (index == restorerTargetIndex) Modifier.focusRequester(restorerFallbackRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) lastFocusedIndex = index }
                        .focusProperties {
                            if (upLink != null) up = upLink
                            down = downLink ?: FocusRequester.Cancel
                            // Sibling-card requester redirects POISON the node
                            // (all outgoing transfers die — STUCK-1 class);
                            // geometric default search handles LEFT/RIGHT
                            // natively, so only the edge cancels stay.
                            left = if (index == 0) FocusRequester.Cancel else FocusRequester.Default
                            right = if (isLast) FocusRequester.Cancel else FocusRequester.Default
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (upLink != null) {
                                        var success = false
                                        try {
                                            // STUCK-1: release the held focus
                                            // session first — a bare hop out
                                            // of a requester-parked card can
                                            // be vetoed and silently no-op.
                                            try {
                                                focusManager.clearFocus(force = true)
                                            } catch (_: Exception) {}
                                            upLink.requestFocus()
                                            success = true
                                        } catch (_: Exception) {
                                            success = false
                                        }
                                        if (!success && outerListState != null) {
                                            railScope.launch {
                                                try {
                                                    outerListState.scrollToItem(
                                                        (outerListState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                                    )
                                                    withFrameNanos {}
                                                    upLink.requestFocus()
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.DirectionLeft -> {
                                    // Direct sibling-card requester hops fail
                                    // (STUCK-1 class). Scroll the neighbor fully
                                    // into view FIRST, then hop — the proven
                                    // UP-retry pattern.
                                    if (index == 0) {
                                        true
                                    } else {
                                        lastFocusedIndex = index - 1
                                        railScope.launch {
                                            try {
                                                listState.scrollToItem(index - 1)
                                                withFrameNanos {}
                                                cardRequesters[index - 1]?.requestFocus()
                                            } catch (_: Exception) {}
                                        }
                                        true
                                    }
                                }
                                Key.DirectionRight -> {
                                    if (isLast) {
                                        true
                                    } else {
                                        lastFocusedIndex = index + 1
                                        railScope.launch {
                                            try {
                                                listState.scrollToItem(index + 1)
                                                withFrameNanos {}
                                                cardRequesters[index + 1]?.requestFocus()
                                            } catch (_: Exception) {}
                                        }
                                        true
                                    }
                                }
                                Key.DirectionDown -> {
                                    if (downLink == null) {
                                        true
                                    } else {
                                        var success = false
                                        try {
                                            // STUCK-1: release the held focus
                                            // session first (mirror of UP).
                                            try {
                                                focusManager.clearFocus(force = true)
                                            } catch (_: Exception) {}
                                            downLink.requestFocus()
                                            success = true
                                        } catch (_: Exception) {
                                            success = false
                                        }
                                        if (!success && outerListState != null) {
                                            railScope.launch {
                                                try {
                                                    outerListState.scrollToItem(
                                                        outerListState.firstVisibleItemIndex + 1
                                                    )
                                                    withFrameNanos {}
                                                    downLink.requestFocus()
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                    cardContent(item, index, cardModifier, isLast)
                }
            }
        }
    }
}

/**
 * Canonical link graph shared by every rail screen. Each screen builds ONE
 * graph from its dynamic [order] (hero/header + non-empty rails only —
 * empty rails are skipped entirely so UP/DOWN never lands on a detached
 * container) plus its hoisted link map, then resolves every rail's
 * declarative wiring through it.
 *
 * [links] entries are created on demand and stable thereafter (same pattern
 * as the hoisted rail states in AppNavigation): [linkFor] never returns null
 * for a known rail id. Axis movement is pure focus search between composed
 * containers — no registries, no imperative hops, no next-into-lazy-items.
 */
class TvRailGraph(
    val order: List<String>,
    val entryId: String,
    val entryLink: FocusRequester?,
    val links: MutableMap<String, FocusRequester>
) {
    fun railIdx(id: String): Int = order.indexOf(id)

    fun prevRailId(id: String?): String? {
        if (id == null) return null
        val i = order.indexOf(id)
        if (i <= 0) return null
        return order[i - 1]
    }

    fun nextRailId(id: String?): String? {
        if (id == null) return null
        val i = order.indexOf(id)
        if (i == -1) return null
        return order.getOrNull(i + 1)
    }

    fun linkFor(id: String?): FocusRequester? {
        if (id == null) return null
        if (id == entryId) return entryLink
        return links.getOrPut(id) { FocusRequester() }
    }

    fun railLink(id: String): FocusRequester = links.getOrPut(id) { FocusRequester() }
}
