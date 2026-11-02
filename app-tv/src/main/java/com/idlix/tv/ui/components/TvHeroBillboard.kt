package com.idlix.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.idlix.data.model.MovieItem
import com.idlix.tv.R
import com.idlix.tv.ui.screens.devtools.DevToolsCarousel
import com.idlix.tv.ui.theme.NetflixBlack
import com.idlix.tv.ui.theme.NetflixGold
import com.idlix.tv.ui.theme.NetflixLightGray
import com.idlix.tv.ui.theme.NetflixRed
import com.idlix.tv.ui.theme.NetflixWhite
import kotlinx.coroutines.delay

// Web-hero scrims: full-bleed art runs top-to-bottom, so both gradients run
// hotter than the old 55%-hero values — white title text and the red CTA
// must read on bright backdrops, not just dark ones.
private val HeroBottomBrush = Brush.verticalGradient(
    colors = listOf(
        Color.Transparent,
        NetflixBlack.copy(alpha = 0.35f),
        NetflixBlack.copy(alpha = 0.82f),
        NetflixBlack
    )
)

private val HeroLeftBrush = Brush.horizontalGradient(
    colors = listOf(
        NetflixBlack.copy(alpha = 0.90f),
        NetflixBlack.copy(alpha = 0.60f),
        Color.Transparent
    )
)

// Immersive-follow dissolve (Flixclusive ImmersiveHomeBackground fade,
// reimplemented): backdrop art crossfades on slide AND on focused-card
// change, so colors follow focus smoothly instead of cutting.
private const val HeroFadeMs = 600

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHeroBillboard(
    movies: List<MovieItem>,
    modifier: Modifier = Modifier,
    playButtonModifier: Modifier = Modifier,
    onPlayClick: (MovieItem) -> Unit,
    onDetailsClick: (MovieItem) -> Unit,
    // Content-to-bar escape for both CTAs (see topEdgeBarEscape): at partial
    // scroll the billboard sits under the transparent bar and plain UP dies.
    barEscapeLink: FocusRequester? = null,
    // Scroll-to-top hook forwarded to topEdgeBarEscape (see there): the hero
    // is index 0, so this is a no-op settle. Null = focus-only (default).
    onBarEscape: (suspend () -> Unit)? = null,
    // Downlink to first rail container link: deterministic vertical D-pad navigation.
    downLink: FocusRequester? = null,
    // Browsing pause: true while focus sits anywhere in the host screen
    // (rails included — see the root hasFocus observer in HomeScreen). The
    // 8s rotation freezes so the backdrop stays stable while browsing; the
    // Play node stays outside the artwork AnimatedContent block either way,
    // so focus is never disposed by a slide change.
    rotationPaused: Boolean = false,
    // Immersive follow (Flixclusive focusedMedia pattern, reimplemented on
    // local data): the host screen passes the currently focused rail card.
    // While non-null (and carrying art), the billboard shows THAT media —
    // backdrop, badge, title, meta, and CTA target — instead of the carousel
    // slide, dissolving via AnimatedContent. Null (or art-less) falls back
    // to the carousel. Passive display input only: no focus node, no chain.
    focusedMedia: MovieItem? = null
) {
    if (movies.isEmpty()) return

    // Featured carousel: self-advancing index, 8s per slide. `remember(count)`
    // keeps the position across content refreshes of the same size and resets
    // it only when the slide count itself changes (no yank-to-zero on every
    // list re-emission). Paused while a CTA is focused OR while the host
    // screen reports focus anywhere (browsing the rails) — the backdrop must
    // not swap under the user mid-browse.
    val count = movies.size
    var index by remember(count) { mutableIntStateOf(0) }
    var isPlayFocused by remember { mutableStateOf(false) }
    val isCtaFocused = isPlayFocused

    // Auto-scroll outer LazyColumn to top when hero CTA buttons take focus
    val outerListState = LocalTvOuterListState.current
    LaunchedEffect(
        isCtaFocused,
        outerListState?.firstVisibleItemIndex,
        outerListState?.firstVisibleItemScrollOffset
    ) {
        if (isCtaFocused && outerListState != null) {
            if (outerListState.firstVisibleItemIndex > 0 || outerListState.firstVisibleItemScrollOffset > 0) {
                try {
                    outerListState.scrollToItem(0)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(count, isCtaFocused, rotationPaused) {
        if (count <= 1 || isCtaFocused || rotationPaused) return@LaunchedEffect
        while (true) {
            delay(8000L)
            index = (index + 1) % count
        }
    }
    // DevTools inspector feed (dev-only): report carousel position/count.
    // Additive observer — no effect on playback, timing, or layout.
    LaunchedEffect(index, count) {
        DevToolsCarousel.report(index, count)
    }
    val movie = movies[index.coerceIn(0, count - 1)]

    // Debounce focusedMedia to eliminate hero screen shake during rapid D-pad browsing
    var settledFocusedMedia by remember { mutableStateOf<MovieItem?>(null) }
    LaunchedEffect(focusedMedia) {
        if (focusedMedia == null) {
            settledFocusedMedia = null
        } else {
            delay(160L)
            settledFocusedMedia = focusedMedia
        }
    }

    // Immersive follow: debounced focused card wins over carousel slide for every
    // visible slot (art, badge, title, meta, CTA target). Blank-art focus
    // falls back to the carousel — never a black billboard.
    val display = settledFocusedMedia?.takeIf { it.fullBackdropUrl.isNotBlank() } ?: movie

    val context = LocalContext.current
    val backdropUrl = remember(display.fullBackdropUrl, display.fullPosterUrl) {
        display.fullBackdropUrl.ifBlank { display.fullPosterUrl }.toFullQualityBackdropUrl()
    }

    // Match score derived from TMDB vote average (8.7 -> "87% Match").
    // Null or zero votes carry no signal — omit instead of "0%".
    val matchPercent = remember(display.voteAverage) {
        display.voteAverage?.toDoubleOrNull()?.takeIf { it > 0 }?.let { ((it * 10).toInt()).coerceIn(0, 100) }
    }
    // Star-line rating ("6.4"): null when the catalog carries no vote, so no
    // "N/A" (or "0.0") placeholder ever renders in the meta row.
    val starRating = remember(display.voteAverage) {
        display.voteAverage?.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format("%.1f", it) }
    }
    val year = remember(display.releaseDate) {
        display.releaseDate?.take(4)?.takeIf { it.length == 4 }
    }
    val runtimeMinutes = remember(display.runtime) {
        display.runtime?.takeIf { it > 0 }
    }
    // Up to two genre names, web-hero style ("Mystery, Thriller").
    val genreLine = remember(display.genres) {
        display.genres.map { it.name }.filter { it.isNotBlank() }.take(2)
            .joinToString(", ").takeIf { it.isNotBlank() }
    }
    val isSeries = display.contentType == "tv_series"

    // Focus requester for explicit vertical navigation between CTA and outer links
    val playBtnRequester = remember { FocusRequester() }

    // Cinematic hero: ~68% of the REAL screen height (LazyColumn items see
    // infinite parent height, so BoxWithConstraints clamps to max and crops).
    // Short enough that the first rail stays fully on screen below the CTAs.
    // Clamped so the CTA block never crops on short viewports (320dp floor)
    // nor stretches into a fullscreen poster on tall panels (560dp ceiling).
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val heroHeight = (screenH * 0.80f).coerceIn(330.dp, 434.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .tvFocusFrame()
    ) {
        // Full-bleed cinematic backdrop. Swapped per slide AND per focused
        // card inside AnimatedContent (Flixclusive ImmersiveHomeBackground
        // pattern, reimplemented): a 600ms dissolve, so art and colors
        // follow focus smoothly instead of cutting — while title/meta and
        // both buttons recompose in place OUTSIDE, so the Play Button node
        // (and its focus) is never disposed mid-rotation or mid-browse.
        AnimatedContent(
            targetState = backdropUrl,
            transitionSpec = {
                fadeIn(animationSpec = tween(HeroFadeMs)) togetherWith
                    fadeOut(animationSpec = tween(HeroFadeMs))
            },
            label = "hero-backdrop"
        ) { url ->
            val backdropRequest = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(1920, 1080)
                    .precision(Precision.EXACT)
                    .allowRgb565(false)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = backdropRequest,
                contentDescription = display.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom gradient fade into the rails
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroBottomBrush)
        )

        // Left gradient scrim behind the text block (saves fillrate vs full-screen)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(680.dp)
                .background(HeroLeftBrush)
        )

        // Bottom-left content block, web-hero order:
        // badge / tagline / logo or title / meta / synopsis / CTA / dots
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(600.dp)
                .padding(start = 48.dp, top = 68.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // 1. Content-type badge: red rounded pill
            Box(
                modifier = Modifier
                    .background(NetflixRed, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSeries) "TV SERIES" else "MOVIE",
                    color = NetflixWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 2. Tagline
            if (!display.tagline.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = display.tagline!!,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3. Title Logo (display.fullLogoUrl) OR Bold Title
            Spacer(modifier = Modifier.height(6.dp))
            var logoLoaded by remember(display.fullLogoUrl) { mutableStateOf(false) }
            var logoFailed by remember(display.fullLogoUrl) { mutableStateOf(false) }
            if (display.fullLogoUrl.isNotBlank() && !logoFailed) {
                Box(contentAlignment = Alignment.CenterStart) {
                    if (!logoLoaded) {
                        Text(
                            text = display.title,
                            color = NetflixWhite,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Black
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(display.fullLogoUrl)
                            .size(500, 180)
                            .allowRgb565(false)
                            .crossfade(false)
                            .build(),
                        contentDescription = display.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onSuccess = { logoLoaded = true },
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .heightIn(min = 42.dp, max = 68.dp)
                            .widthIn(max = 360.dp)
                    )
                }
            } else {
                Text(
                    text = display.title,
                    color = NetflixWhite,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 4. Metadata Row ([Image #1] style)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.heightIn(min = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (starRating != null) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = NetflixGold,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = starRating,
                        color = NetflixGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val metaTokens = buildList {
                    if (display.effectiveYear.isNotBlank()) {
                        add(display.effectiveYear)
                    }
                    if (isSeries) {
                        val seasons = display.numberOfSeasons ?: 0
                        if (seasons > 0) {
                            add("$seasons Season${if (seasons > 1) "s" else ""}")
                        }
                        val episodes = display.numberOfEpisodes ?: 0
                        if (episodes > 0) {
                            add("$episodes Episode${if (episodes > 1) "s" else ""}")
                        }
                    } else {
                        val runtime = display.runtime ?: 0
                        if (runtime > 0) {
                            add(stringResource(R.string.movie_duration_format, runtime))
                        }
                    }
                    if (display.genres.isNotEmpty()) {
                        val genresStr = display.genres
                            .map { it.name }
                            .filter { it.isNotBlank() }
                            .take(3)
                            .joinToString(", ")
                        if (genresStr.isNotBlank()) {
                            add(genresStr)
                        }
                    }
                }

                if (metaTokens.isNotEmpty()) {
                    Text(
                        text = metaTokens.joinToString("  •  "),
                        color = NetflixLightGray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Status / Quality Pill
                if (isSeries && !display.status.isNullOrBlank()) {
                    val statusText = display.status!!
                    val isOngoing = statusText.contains("Returning", ignoreCase = true) ||
                        statusText.contains("Ongoing", ignoreCase = true) ||
                        statusText.contains("Production", ignoreCase = true)
                    val statusLabel = if (isOngoing) "ONGOING" else statusText.uppercase()
                    val borderColor = if (isOngoing) Color(0xFF10B981) else NetflixLightGray
                    val bgColor = if (isOngoing) Color(0x2610B981) else Color(0x33FFFFFF)
                    val txtColor = if (isOngoing) Color(0xFF34D399) else NetflixWhite
                    Box(
                        modifier = Modifier
                            .background(bgColor, RoundedCornerShape(4.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = statusLabel,
                            color = txtColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (!display.quality.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, NetflixLightGray), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = display.quality!!.uppercase(),
                            color = NetflixWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 5. Overview / Synopsis Paragraph
            if (!display.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = display.overview!!,
                    color = Color(0xFFD4D4D8),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 540.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Single Watch Now CTA matching z2.idlixku.com ([Image #1])
            val watchNowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isWatchNowHovered by watchNowInteraction.collectIsHoveredAsState()
            Button(
                onClick = { onPlayClick(display) },
                interactionSource = watchNowInteraction,
                modifier = playButtonModifier
                    .focusRequester(playBtnRequester)
                    .onFocusChanged { isPlayFocused = it.isFocused }
                    .topEdgeBarEscape(barEscapeLink, force = true, onEscape = onBarEscape)
                    // Black halo OUTSIDE the white focus ring: on bright
                    // backdrops the white ring alone washes out (proven by
                    // crop_play.png). B&W only, zero layout churn.
                    .then(
                        if (isPlayFocused || isWatchNowHovered) {
                            Modifier.border(4.dp, Color.Black, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        if (barEscapeLink != null) up = barEscapeLink
                        if (downLink != null) down = downLink
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NetflixRed,
                    contentColor = NetflixWhite,
                    focusedContainerColor = NetflixWhite,
                    focusedContentColor = NetflixBlack
                ),
                scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                border = ButtonDefaults.border(
                    border = Border(
                        border = BorderStroke(
                            width = if (isWatchNowHovered) 2.dp else 1.dp,
                            color = if (isWatchNowHovered) NetflixWhite else Color.Transparent
                        )
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NetflixWhite)
                    )
                ),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Watch Now",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Watch Now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // Carousel position dots: display-only indicators, never focusable
            // (plain Boxes, no clickable/focusable modifier — focus traps
            // forbidden). Active slide = red wide pill, rest = dim dots.
            if (count > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until count) {
                        Box(
                            modifier = Modifier
                                .then(
                                    if (i == index) Modifier.size(width = 28.dp, height = 5.dp)
                                    else Modifier.size(6.dp)
                                )
                                .background(
                                    if (i == index) NetflixRed
                                    else Color.White.copy(alpha = 0.35f),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
            }
        }

        // Bottom-Right Network Watermark
        if (display.fullNetworkLogoUrl.isNotBlank()) {
            AsyncImage(
                model = display.fullNetworkLogoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 24.dp)
                    .height(26.dp)
                    .widthIn(max = 120.dp),
                alpha = 0.65f
            )
        }
    }
}
