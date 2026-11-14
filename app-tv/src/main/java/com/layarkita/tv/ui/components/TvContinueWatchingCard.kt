package com.layarkita.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.layarkita.data.model.WatchHistoryItem
import com.layarkita.tv.R
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite

private val ContinueCardShape = RoundedCornerShape(10.dp)
private val ContinueScrimBrush = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0xB3141414), NetflixBlack)
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvContinueWatchingCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 220,
    height: Int = 135,
    fillWidth: Boolean = false,
    onFocus: (() -> Unit)? = null
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isCardActive = isFocused || isHovered

    // Mixed-input park: same contract as TvMovieCard — click lands D-pad
    // focus on this node so arrows continue from here. Hover never parks.
    val clickParkRequester = remember { FocusRequester() }

    LaunchedEffect(isCardActive) {
        if (isCardActive) {
            onFocus?.invoke()
        }
    }

    val context = LocalContext.current
    val rawUrl = item.backdropUrl ?: item.posterUrl
    val imageUrl = remember(rawUrl) { rawUrl?.toFullQualityBackdropUrl() }
    val imageRequest = remember(imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(780, 440)
            .precision(Precision.EXACT)
            .allowRgb565(false)
            .crossfade(false)
            .build()
    }

    val sizeModifier = if (fillWidth) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(width.toFloat() / height.toFloat().coerceAtLeast(1f))
    } else {
        Modifier
            .width(width.dp)
            .height(height.dp)
    }

    Card(
        onClick = {
            // Action first, park second — same contract as TvMovieCard.
            onClick()
            try {
                clickParkRequester.requestFocus()
            } catch (_: Exception) {
            }
        },
        interactionSource = interactionSource,
        modifier = modifier
            .then(sizeModifier)
            .focusRequester(clickParkRequester),
        scale = CardDefaults.scale(focusedScale = TvSizing.CardFocusScale),
        glow = CardDefaults.glow(focusedGlow = Glow.None),
        shape = CardDefaults.shape(shape = ContinueCardShape),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = ContinueCardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, NetflixWhite),
                shape = ContinueCardShape
            )
        )
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
            // Backdrop Image
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Gradient scrim for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ContinueScrimBrush)
            )

            // Center play button overlay: always composed to eliminate layout churn,
            // with graphicsLayer alpha and scale toggled on focus.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = if (isCardActive) 1f else 0.72f
                        val s = if (isCardActive) 1.08f else 0.92f
                        scaleX = s
                        scaleY = s
                    }
                    .background(if (isCardActive) NetflixRed else Color(0xAA000000), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint = NetflixWhite,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }

            // Bottom title & progress bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = item.title,
                    color = NetflixWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Subtitle: Episode code or remaining time
                val epTitle = item.episodeTitle
                val subtitle: String = if (!epTitle.isNullOrBlank()) {
                    epTitle
                } else if (item.remainingMinutes > 0) {
                    stringResource(R.string.remaining_minutes, item.remainingMinutes.toInt())
                } else ""
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = NetflixLightGray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress Bar (Netflix Red)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = item.progressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(NetflixRed, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}
