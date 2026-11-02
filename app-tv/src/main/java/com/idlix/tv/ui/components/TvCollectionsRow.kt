package com.idlix.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.idlix.data.model.CollectionItem
import com.idlix.tv.R
import com.idlix.tv.ui.theme.NetflixDarkCard
import com.idlix.tv.ui.theme.NetflixLightGray
import com.idlix.tv.ui.theme.NetflixRed
import com.idlix.tv.ui.theme.NetflixWhite

private val CollectionCardShape = RoundedCornerShape(10.dp)
private val CollectionScrimBrush = Brush.verticalGradient(
    listOf(Color(0x33141414), Color(0xCC141414), Color(0xF5141414))
)

/**
 * Collections rail: web collection card look matching z2.idlixku.com with
 * full-quality backdrop/poster art, dark gradient scrim, kind badge pill,
 * and high-contrast focus border.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCollectionsRow(
    title: String,
    items: List<CollectionItem>,
    modifier: Modifier = Modifier,
    onItemFocus: ((CollectionItem) -> Unit)? = null,
    onItemClick: (CollectionItem) -> Unit,
    rowId: String = "",
    focusKey: String? = null,
    onFocusKeyConsumed: () -> Unit = {},
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
        focusKeyFor = { collection -> "$rowId/collection/${collection.slug}" },
        itemKey = { _, collection -> collection.id.ifBlank { collection.slug } },
        listState = listState,
        upLink = upLink,
        downLink = downLink,
        railLink = railLink,
        cardContent = { collection, _, cardModifier, _ ->
            val context = LocalContext.current
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val isHovered by interactionSource.collectIsHoveredAsState()
            val isCardActive = isFocused || isHovered

            LaunchedEffect(isCardActive) {
                if (isCardActive) {
                    onItemFocus?.invoke(collection)
                }
            }

            val titlesText = if (collection.itemCount > 0) {
                stringResource(R.string.collection_titles_format, collection.itemCount)
            } else ""
            val subtitle = remember(titlesText, collection.kind) {
                buildString {
                    if (titlesText.isNotBlank()) {
                        append(titlesText)
                    }
                    if (collection.kind.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(collection.kind.replaceFirstChar { it.uppercase() })
                    }
                }
            }

            val hasArt = collection.fullBackdropUrl.isNotBlank() || collection.fullPosterUrl.isNotBlank()
            val artUrl = remember(collection.fullBackdropUrl, collection.fullPosterUrl) {
                if (hasArt) {
                    collection.fullBackdropUrl.ifBlank { collection.fullPosterUrl }.toFullQualityBackdropUrl()
                } else null
            }
            val imageRequest = remember(artUrl) {
                artUrl?.let {
                    ImageRequest.Builder(context)
                        .data(it)
                        .size(780, 440)
                        .precision(Precision.EXACT)
                        .allowRgb565(false)
                        .crossfade(false)
                        .build()
                }
            }

            Card(
                onClick = { onItemClick(collection) },
                interactionSource = interactionSource,
                scale = CardDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                glow = CardDefaults.glow(focusedGlow = Glow.None),
                shape = CardDefaults.shape(shape = CollectionCardShape),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = CollectionCardShape
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(2.5.dp, NetflixWhite),
                        shape = CollectionCardShape
                    )
                ),
                modifier = Modifier
                    .width(240.dp)
                    .height(135.dp)
                    .then(cardModifier)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NetflixDarkCard)
                        .drawWithContent {
                            drawContent()
                            if (isCardActive) {
                                drawRoundRect(
                                    color = Color.Black,
                                    style = Stroke(width = 2.dp.toPx()),
                                    cornerRadius = CornerRadius(10.dp.toPx())
                                )
                            }
                        }
                ) {
                    if (imageRequest != null) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = collection.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CollectionScrimBrush)
                        )
                    }

                    // Top-left badge pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(
                                color = NetflixRed.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = collection.kind.ifBlank { "COLLECTION" }.uppercase(),
                            color = NetflixWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // Bottom-left title and subtitle
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = collection.title,
                            color = NetflixWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = subtitle,
                                color = NetflixLightGray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    )
}
