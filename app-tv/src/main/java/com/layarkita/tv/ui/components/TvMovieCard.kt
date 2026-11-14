package com.layarkita.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import com.layarkita.tv.R
import com.layarkita.tv.ui.theme.NetflixDarkCard
import com.layarkita.tv.ui.theme.NetflixGold
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite

private val CardShape = RoundedCornerShape(10.dp)
private val BottomScrimBrush = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color(0xB3141414), Color(0xF2141414))
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMovieCard(
    title: String,
    posterUrl: String,
    rating: String,
    quality: String? = null,
    modifier: Modifier = Modifier,
    width: Int = 135,
    height: Int = 200,
    fillWidth: Boolean = false,
    rank: Int? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val highResPosterUrl = remember(posterUrl) { posterUrl.toFullQualityPosterUrl() }
    val imageRequest = remember(highResPosterUrl) {
        ImageRequest.Builder(context)
            .data(highResPosterUrl)
            .size(540, 810)
            .precision(Precision.EXACT)
            .allowRgb565(false)
            .crossfade(false)
            .build()
    }

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isCardActive = isFocused || isHovered

    // Mixed-input park: a mouse click (or keyboard Enter) fires onClick
    // without moving D-pad focus, leaving arrows stranded on the previous
    // node. This requester rides on the same Card focus node as the rail's
    // own requester (multiple requesters may share one node), so parking
    // here keeps every focusProperties link intact. Hover never touches it.
    val clickParkRequester = remember { FocusRequester() }

    LaunchedEffect(isCardActive) {
        if (isCardActive) {
            onFocus?.invoke()
        }
    }

    // Zero-vote entries ("0.0"/"0") carry no signal — hide the star row
    // entirely, mirroring TvHeroBillboard's takeIf { it > 0 }. Non-numeric
    // strings keep legacy behavior (shown) so custom badges never vanish.
    val showRating = remember(rating) {
        rating.isNotBlank() && rating != "N/A" &&
            (rating.toDoubleOrNull()?.let { it > 0 } ?: true)
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
            // Run the action first: if it navigates away this node is
            // disposed and the park below is a harmless no-op; if the node
            // survives, D-pad focus lands on it for the next arrow press.
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
        shape = CardDefaults.shape(shape = CardShape),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = CardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, NetflixWhite),
                shape = CardShape
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
            AsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Top-left: in-card rank number if rank != null, else star rating badge
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xD9000000), Color(0x66000000), Color.Transparent)
                            ),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$rank",
                        color = NetflixWhite,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else if (showRating) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xCC141414), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.rating_desc),
                        tint = NetflixGold,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = rating,
                        color = NetflixWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Top-right quality / season badge (if any)
            if (!quality.isNullOrBlank()) {
                val isSeasonBadge = quality.startsWith("S", ignoreCase = true) &&
                    quality.drop(1).all { it.isDigit() }
                val badgeModifier = if (isSeasonBadge) {
                    Modifier
                        .background(Color(0xD9141414), RoundedCornerShape(3.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                } else {
                    Modifier.background(NetflixRed.copy(alpha = 0.92f), RoundedCornerShape(3.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .then(badgeModifier)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = quality.uppercase(),
                        color = NetflixWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom title: only show on unranked cards (search results or standard rails)
            // On ranked cards (Trending Now), the poster art itself is clean and un-obscured matching [Image #1]
            if (rank == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(BottomScrimBrush)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = title,
                        color = NetflixWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        lineHeight = 16.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
