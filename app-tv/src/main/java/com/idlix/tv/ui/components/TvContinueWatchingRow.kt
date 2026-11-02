package com.idlix.tv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.idlix.data.model.WatchHistoryItem

/**
 * Continue-watching rail (wide 220dp cards). Thin delegate over
 * [TvRailShell]: only the wide-card visual lives here; frame/declarative
 * links/Cancel edges are the shell's. Key shape stays
 * "$rowId/${contentType}/${contentId}" (contentId — continue items may carry
 * a null slug).
 */
@Composable
fun TvContinueWatchingRow(
    title: String = "Lanjutkan Menonton",
    items: List<WatchHistoryItem>,
    modifier: Modifier = Modifier,
    onItemFocus: ((WatchHistoryItem) -> Unit)? = null,
    onItemClick: (WatchHistoryItem) -> Unit,
    // Same-card refocus (mirrors TvSwimlaneRow): the owning screen passes its
    // pending key plus this rail's id. The matching card requests focus on
    // return from player; the screen clears the key via [onFocusKeyConsumed].
    // Null key = no-op.
    rowId: String = "",
    focusKey: String? = null,
    onFocusKeyConsumed: () -> Unit = {},
    // Hoisted scroll state (mirrors TvSwimlaneRow) so detail/player return
    // can restore the originating card.
    listState: LazyListState = rememberLazyListState(),
    // Explicit UP/DOWN chain + rail link (see TvSwimlaneRow): declarative
    // container links only.
    upLink: FocusRequester? = null,
    downLink: FocusRequester? = null,
    railLink: FocusRequester? = null
) {
    TvRailShell(
        title = title,
        items = items,
        modifier = modifier,
        rowId = rowId,
        focusKey = focusKey,
        onFocusKeyConsumed = onFocusKeyConsumed,
        focusKeyFor = { item -> "$rowId/${item.contentType}/${item.contentId}" },
        itemKey = { _, item -> item.contentId },
        listState = listState,
        upLink = upLink,
        downLink = downLink,
        railLink = railLink,
        cardContent = { item, _, cardModifier, _ ->
            TvContinueWatchingCard(
                item = item,
                onClick = { onItemClick(item) },
                modifier = cardModifier,
                onFocus = { onItemFocus?.invoke(item) }
            )
        }
    )
}
