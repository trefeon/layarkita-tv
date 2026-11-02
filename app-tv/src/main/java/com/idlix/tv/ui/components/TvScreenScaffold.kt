package com.idlix.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import com.idlix.tv.ui.theme.NetflixBlack
import kotlinx.coroutines.delay

val LocalTvOuterListState = compositionLocalOf<LazyListState?> { null }

/**
 * Single shared screen scaffold for every rail screen (Home, Movies, Series,
 * GenreDetail, CollectionDetail). Owns the duplicated screen chrome every
 * entry/detail screen had copy-pasted:
 *
 * - Full-screen [NetflixBlack] shell with exactly one back handler per route
 *   ([onBack] pops child routes; null consumes Back on tab roots) plus
 *   optional focus tracking ([trackFocus] — the hero-carousel rotation
 *   pause; passive observer only).
 * - Loading / error / content switch: centered [TvLoadingSpinner] while
 *   [isLoading], centered [TvErrorState] for [errorMessage], else the
 *   [LazyColumn] with the 12dp settle guard below the floating bar (+
 *   optional [bottomInset] + [restoreFocus]).
 * - Entry + return-from-detail focus: parks on the entry target (hero Play /
 *   Back) on cold launch via [onEntryFocus], scrolls the outer + rail lists
 *   back to the originating card for a pending [refocusKey], and falls back
 *   to the entry target when the key is stale. The matching rail card
 *   self-requests focus and clears the key via the `clearFocusKey` passed to
 *   [content] (hand it to every rail as `onFocusKeyConsumed`).
 *
 * The NavHost graph does not rebuild on same-branch recomposition, so a
 * consume never propagates back to the screen composition: the scaffold-local
 * `refocusConsumed` flag is the reliable consume signal. `firstLoad` gates
 * entry parking to the first settled run of the screen instance, so a late
 * data re-emission after a rail-card consume never steals restored focus.
 *
 * Per-screen differences are params, not forks: [entryKeyValue] ("hero" vs
 * "header"), [railOrder] (dynamic hero/header + non-empty rails, MUST mirror
 * the [content] column), [findCardIndex] (per-screen card lookup — note some
 * rails match slug-only), [entryDelayMs] (Back-owned screens settle 120ms),
 * [guardStaleKey] (Movies skips the stale-key watchdog check), and
 * [instanceKey] (genre/collection slug resets entry state per instance).
 */

/**
 * Entry park: pull the hoisted outer list back to 0 so the entry target is
 * composed, then request it — with 8x40ms retry on the cold-entry path where
 * the nav-transition window drops the first request (window not yet focused).
 * Stale-fallback parks use a single request (window already focused).
 */
private suspend fun parkOnEntry(
    listState: LazyListState,
    onEntryFocus: () -> Unit,
    retry: Boolean
) {
    try {
        listState.scrollToItem(0)
    } catch (_: Exception) {
    }
    if (retry) {
        repeat(8) {
            try {
                listState.scrollToItem(0)
            } catch (_: Exception) {
            }
            try {
                onEntryFocus()
            } catch (_: Exception) {
            }
            delay(40)
        }
    } else {
        try {
            onEntryFocus()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun TvScreenScaffold(
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    listState: LazyListState,
    refocusKey: String?,
    onRefocusKeyChange: (String?) -> Unit,
    entryKeyValue: String,
    railOrder: List<String>,
    railListStates: (String) -> LazyListState = { LazyListState() },
    onEntryFocus: () -> Unit,
    findCardIndex: (railId: String, type: String, slug: String) -> Int,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    restoreFocus: Boolean = false,
    trackFocus: ((Boolean) -> Unit)? = null,
    contentReady: Boolean = true,
    instanceKey: Any? = null,
    entryDelayMs: Long = 0L,
    guardStaleKey: Boolean = true,
    content: LazyListScope.(() -> Unit) -> Unit
) {
    var firstLoad by remember(instanceKey) { mutableStateOf(true) }
    var refocusConsumed by remember(instanceKey) { mutableStateOf(false) }
    // Live view of the key for the watchdog (belt and suspenders alongside
    // refocusConsumed, in case a future navigation version propagates).
    val latestKey = rememberUpdatedState(refocusKey)
    val clearFocusKey: () -> Unit = {
        refocusConsumed = true
        onRefocusKeyChange(null)
    }

    // Exactly one back handler per route: child routes pop, tab roots (null
    // onBack) consume so system Back never exits the app by accident.
    if (onBack != null) {
        BackHandler(onBack = onBack)
    } else {
        BackHandler(enabled = true) { }
    }

    // No pending key: park on the entry target (cold launch, tab entry,
    // player return). Gated to the first settled run (see above).
    // scrollToItem(0) first: the outer list state is hoisted above the
    // detail-swap dispose, so on BACK-with-null-key or tab re-entry the list
    // can be deep-scrolled with the entry target disposed — requesting its
    // detached requester would silently no-op and strand focus. Pulling to 0
    // restores the cold-launch invariant (entry target composed). The 8x40ms
    // retry then rides the nav-transition window, which drops the first
    // request (window not yet focused).
    LaunchedEffect(isLoading, contentReady) {
        if (isLoading || !contentReady) return@LaunchedEffect
        val first = firstLoad
        firstLoad = false
        if (refocusKey == null && first) {
            if (entryDelayMs > 0) delay(entryDelayMs)
            parkOnEntry(listState, onEntryFocus, retry = true)
        }
    }

    // Return from detail: scroll the outer list to the originating rail and
    // the rail to the originating card so it recomposes; its match effect
    // then requests focus and clears the key. A stale/gone key (or a scroll
    // that never yields a match) falls back to the entry target.
    // Rail order MUST mirror the content column below.
    LaunchedEffect(refocusKey, isLoading) {
        val key = refocusKey ?: return@LaunchedEffect
        if (isLoading || !contentReady) return@LaunchedEffect
        if (key == entryKeyValue) {
            onRefocusKeyChange(null)
            try {
                onEntryFocus()
            } catch (_: Exception) {
            }
            return@LaunchedEffect
        }
        val parts = key.split("/", limit = 3)
        if (parts.size != 3) {
            onRefocusKeyChange(null)
            parkOnEntry(listState, onEntryFocus, retry = false)
            return@LaunchedEffect
        }
        val (railId, type, slug) = parts
        val railIndex = railOrder.indexOf(railId)
        val cardIndex = if (railIndex == -1) {
            -1
        } else {
            findCardIndex(railId, type, slug)
        }
        if (railIndex == -1 || cardIndex == -1) {
            onRefocusKeyChange(null)
            parkOnEntry(listState, onEntryFocus, retry = false)
            return@LaunchedEffect
        }
        try {
            listState.scrollToItem(railIndex)
            railListStates(railId).scrollToItem(cardIndex)
        } catch (_: Exception) {
            onRefocusKeyChange(null)
            parkOnEntry(listState, onEntryFocus, retry = false)
            return@LaunchedEffect
        }
        // Watchdog: if the card never consumes the key, park on entry.
        delay(1500)
        if (!refocusConsumed && (!guardStaleKey || latestKey.value == key)) {
            onRefocusKeyChange(null)
            try {
                onEntryFocus()
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .then(
                if (trackFocus != null) {
                    Modifier.onFocusChanged { trackFocus(it.hasFocus) }
                } else {
                    Modifier
                }
            )
    ) {
        if (isLoading) {
            TvLoadingSpinner(
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (errorMessage != null) {
            TvErrorState(
                message = errorMessage,
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            CompositionLocalProvider(LocalTvOuterListState provides listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (restoreFocus) Modifier.focusRestorer() else Modifier),
                    contentPadding = PaddingValues(top = topInset, bottom = bottomInset)
                ) {
                    content(clearFocusKey)
                }
            }
        }
    }
}
