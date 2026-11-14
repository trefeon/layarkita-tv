package com.layarkita.tv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.layarkita.data.model.MovieItem

/**
 * Netflix-style Top-10 rail: oversized rank numerals (red accent) paired with
 * compact poster cards. Thin delegate over [TvRailShell]: rank numerals sit in
 * a bounded 52dp box without negative offsets, eliminating bring-into-view jitter.
 * Aligns on the standard 48dp leading inset keyline.
 */
@Composable
fun TvTop10Row(
    title: String,
    items: List<MovieItem>,
    modifier: Modifier = Modifier,
    onItemFocus: ((MovieItem) -> Unit)? = null,
    onItemClick: (MovieItem) -> Unit,
    // Same-card refocus (mirrors TvSwimlaneRow): pending key + this rail's id;
    // the matching card self-requests focus and the screen clears the key.
    rowId: String = "",
    focusKey: String? = null,
    onFocusKeyConsumed: () -> Unit = {},
    // Hoisted scroll state (mirrors TvSwimlaneRow).
    listState: LazyListState = rememberLazyListState(),
    // Explicit UP/DOWN chain + rail link (see TvSwimlaneRow): declarative
    // container links only.
    upLink: FocusRequester? = null,
    downLink: FocusRequester? = null,
    railLink: FocusRequester? = null
) {
    val top10 = items.take(10)
    TvRailShell(
        title = title,
        items = top10,
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
        leadingInset = 48.dp,
        cardContent = { movie, index, cardModifier, _ ->
            TvMovieCard(
                title = movie.title,
                posterUrl = movie.fullPosterUrl,
                rating = movie.formattedRating,
                quality = movie.topRightBadge,
                rank = index + 1,
                width = 135,
                height = 200,
                modifier = cardModifier,
                onFocus = { onItemFocus?.invoke(movie) },
                onClick = { onItemClick(movie) }
            )
        }
    )
}
