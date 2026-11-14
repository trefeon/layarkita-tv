package com.layarkita.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.layarkita.data.model.MovieItem

/**
 * Standard poster swimlane. Thin delegate over [TvRailShell]: only the poster
 * card visual lives here; title/container/frame/declarative links/Cancel
 * edges are the shell's. Same refocus contract (`rowId`/`focusKey`/
 * `onFocusKeyConsumed`, hoisted `listState`).
 */
@Composable
fun TvSwimlaneRow(
    title: String,
    items: List<MovieItem>,
    modifier: Modifier = Modifier,
    onItemFocus: ((MovieItem) -> Unit)? = null,
    onItemClick: (MovieItem) -> Unit,
    showRanks: Boolean = false,
    // Same-card refocus: the owning screen passes its pending key plus this
    // rail's id. The matching card requests focus on return from detail; the
    // screen clears the key via [onFocusKeyConsumed]. Null key = no-op, so
    // cold launch and unrelated recompositions are untouched.
    rowId: String = "",
    focusKey: String? = null,
    onFocusKeyConsumed: () -> Unit = {},
    // Scroll state, hoisted by the owning screen when rail scroll must survive
    // a route round-trip (detail return scrolls back to the originating card).
    // Defaults to a local state, preserving current behavior elsewhere.
    listState: LazyListState = rememberLazyListState(),
    // Explicit UP link: the owning screen passes the previous rail's
    // container link (hero entry for the first rail). Declarative only —
    // plain focus search across the composed TV lazy layouts.
    upLink: FocusRequester? = null,
    // Explicit DOWN link (mirror of upLink): the next rail's container link
    // (null for the last rail → Cancel). Deterministic, so DOWN never jumps
    // sideways.
    downLink: FocusRequester? = null,
    // This rail's container link for the neighbors' UP/DOWN: attached to the
    // LazyRow container — never to a card, whose node disposes when the
    // row scrolls right.
    railLink: FocusRequester? = null
) {
    TvRailShell(
        title = title,
        items = items,
        modifier = modifier,
        rowId = rowId,
        focusKey = focusKey,
        onFocusKeyConsumed = onFocusKeyConsumed,
        focusKeyFor = { movie -> "$rowId/${movie.contentType}/${movie.slug}" },
        itemKey = { _, movie -> movie.id },
        listState = listState,
        upLink = upLink,
        downLink = downLink,
        railLink = railLink,
        showFilterHint = showRanks,
        cardContent = { movie, index, cardModifier, _ ->
            TvMovieCard(
                title = movie.title,
                posterUrl = movie.fullPosterUrl,
                rating = movie.formattedRating,
                quality = movie.topRightBadge,
                rank = if (showRanks) index + 1 else null,
                modifier = cardModifier,
                onFocus = { onItemFocus?.invoke(movie) },
                onClick = { onItemClick(movie) }
            )
        }
    )
}
