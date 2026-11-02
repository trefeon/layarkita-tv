package com.idlix.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import com.idlix.data.local.WatchHistoryStore
import com.idlix.tv.ui.components.TvErrorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.idlix.tv.R
import com.idlix.data.model.CastMember
import com.idlix.data.model.EpisodeItem
import com.idlix.tv.ui.components.TvLoadingSpinner
import com.idlix.tv.ui.components.TvSizing
import com.idlix.tv.ui.components.toFullQualityBackdropUrl
import com.idlix.tv.ui.components.toFullQualityPosterUrl
import com.idlix.tv.ui.components.tvDetailHeaderHeight
import com.idlix.tv.ui.components.tvGridFocusFrame
import com.idlix.tv.ui.theme.NetflixBlack
import com.idlix.tv.ui.theme.NetflixDarkCard
import com.idlix.tv.ui.theme.NetflixGold
import com.idlix.tv.ui.theme.NetflixLightGray
import com.idlix.tv.ui.theme.NetflixRed
import com.idlix.tv.ui.theme.NetflixWhite

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
    contentType: String,
    slug: String,
    onNavigateToPlayer: (contentType: String, contentId: String, title: String, poster: String?, backdrop: String?, slug: String?, season: Int?, episode: Int?) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = viewModel()
) {
    LaunchedEffect(contentType, slug) {
        viewModel.loadDetail(contentType, slug)
    }

    val uiState by viewModel.uiState.collectAsState()
    val detailListState = rememberLazyListState()
    val detailScope = rememberCoroutineScope()
    val playButtonFocusRequester = remember { FocusRequester() }
    val trailerButtonFocusRequester = remember { FocusRequester() }
    val seasonRailLink = remember { FocusRequester() }
    val episodesRailLink = remember { FocusRequester() }

    var lastFocusedSeasonIndex by remember(slug) { mutableIntStateOf(0) }
    var lastFocusedEpisodeIndex by remember(slug, uiState.selectedSeason?.id) { mutableIntStateOf(0) }
    val seasonRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val seasonFallbackRequester = remember { FocusRequester() }
    val episodeRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val episodeFallbackRequester = remember { FocusRequester() }

    var playFocused by remember { mutableStateOf(false) }
    var trailerFocused by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(playFocused, trailerFocused) {
        val isAnyHeaderFocused = playFocused || trailerFocused
        if (isAnyHeaderFocused && !headerFocused) {
            headerFocused = true
            if (detailListState.firstVisibleItemIndex > 0 || detailListState.firstVisibleItemScrollOffset > 0) {
                try {
                    detailListState.animateScrollToItem(0)
                } catch (_: Exception) {}
            }
        } else if (!isAnyHeaderFocused) {
            headerFocused = false
        }
    }

    // Latest unfinished history entry for this title (matched by slug, or movie id):
    // drives the Resume label + seeks to the saved position via the player.
    val context = LocalContext.current
    val historyStore = remember(context) { WatchHistoryStore.getInstance(context) }
    val history by historyStore.historyFlow.collectAsState()
    val resumeItem = remember(history, slug, uiState.movie?.id) {
        val movieId = uiState.movie?.id
        history
            .filter { it.slug == slug || (movieId?.isNotBlank() == true && it.contentId == movieId) }
            .maxByOrNull { it.lastWatchedTime }
            ?.takeIf { it.isUnfinished }
    }

    // Place default focus on "Putar Sekarang" / "Putar Episode 1" as soon as media details load.
    // Park + retry (8x40ms): the nav-transition window drops the first
    // request, so re-assert — entry never parks black. Error states own
    // their Retry focus (see TvErrorState); loading holds none.
    LaunchedEffect(uiState.isLoading, uiState.movie, uiState.series) {
        if (!uiState.isLoading && (uiState.movie != null || uiState.series != null)) {
            repeat(8) {
                try {
                    playButtonFocusRequester.requestFocus()
                } catch (e: Exception) {}
                delay(40)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        if (uiState.isLoading) {
            TvLoadingSpinner(
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.error != null || uiState.errorRes != null) {
            val errorMessage = uiState.error
                ?: uiState.errorRes?.let { stringResource(it) }.orEmpty()
            TvErrorState(
                message = errorMessage,
                onRetry = { viewModel.loadDetail(contentType, slug) },
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val title = uiState.movie?.title ?: uiState.series?.title ?: ""
            val overview = uiState.movie?.overview ?: uiState.series?.overview ?: ""
            val backdropUrl = uiState.movie?.fullBackdropUrl ?: uiState.series?.fullBackdropUrl ?: ""
            val highResBackdropUrl = remember(backdropUrl) { backdropUrl.toFullQualityBackdropUrl() }
            val backdropRequest = remember(highResBackdropUrl) {
                ImageRequest.Builder(context)
                    .data(highResBackdropUrl)
                    .size(1920, 1080)
                    .precision(Precision.EXACT)
                    .allowRgb565(false)
                    .crossfade(false)
                    .build()
            }
            val rating = uiState.movie?.voteAverage ?: uiState.series?.voteAverage ?: ""
            val year = (uiState.movie?.releaseDate ?: uiState.series?.firstAirDate)?.take(4) ?: ""
            val genres = (uiState.movie?.genres ?: uiState.series?.genres ?: emptyList()).joinToString(", ") { it.name }
            val castList = uiState.movie?.cast ?: uiState.series?.cast ?: emptyList()

            LazyColumn(
                state = detailListState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
            ) {
                // Header & Info: viewport-relative (78% of real screen height,
                // clamped 300..420) so the Play CTA stays above the fold on the
                // 411dp rig; a fixed 420dp header buried it below the fold.
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tvDetailHeaderHeight())
                    ) {
                        AsyncImage(
                            model = backdropRequest,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Bottom gradient fade into the page body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            NetflixBlack.copy(alpha = 0.55f),
                                            NetflixBlack.copy(alpha = 0.92f),
                                            NetflixBlack
                                        )
                                    )
                                )
                        )

                        // Left scrim behind the text block
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            NetflixBlack.copy(alpha = 0.85f),
                                            NetflixBlack.copy(alpha = 0.4f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 48.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // Netflix kicker: red N mark + tracked label
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(NetflixRed, RoundedCornerShape(2.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "N",
                                        color = NetflixWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (contentType == "movie") {
                                        stringResource(R.string.kicker_film)
                                    } else {
                                        stringResource(R.string.kicker_series)
                                    },
                                    color = NetflixLightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = title,
                                color = NetflixWhite,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Black
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Match-score metadata line: match % • year • maturity • HD
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rating.toDoubleOrNull()?.let { vote ->
                                    val percent = ((vote * 10).toInt()).coerceIn(0, 100)
                                    Text(
                                        text = stringResource(R.string.match_score_format, percent),
                                        color = NetflixWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (year.isNotBlank()) {
                                    Text(text = year, color = NetflixLightGray, fontSize = 14.sp)
                                }

                                // Maturity badge (catalog carries no certification; stable 13+ gate)
                                Box(
                                    modifier = Modifier
                                        .border(BorderStroke(1.dp, NetflixLightGray), RoundedCornerShape(2.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "13+",
                                        color = NetflixWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                // HD badge
                                Box(
                                    modifier = Modifier
                                        .border(BorderStroke(1.dp, NetflixLightGray), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "HD",
                                        color = NetflixWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Duration (movies) / season + episode counts (series)
                                if (contentType == "movie") {
                                    val runtimeMinutes = uiState.movie?.duration
                                        ?: uiState.movie?.runtime
                                    if (runtimeMinutes != null && runtimeMinutes > 0) {
                                        Text(
                                            text = stringResource(R.string.movie_duration_format, runtimeMinutes),
                                            color = NetflixLightGray,
                                            fontSize = 14.sp
                                        )
                                    }
                                } else {
                                    val seasonCount = uiState.series?.seasons?.size?.takeIf { it > 0 }
                                        ?: uiState.series?.numberOfSeasons?.takeIf { it > 0 }
                                    val episodeCount = uiState.series?.defaultSeason?.episodes?.size?.takeIf { it > 0 }
                                        ?: uiState.episodes.size.takeIf { it > 0 }
                                        ?: uiState.series?.numberOfEpisodes?.takeIf { it > 0 }
                                    if (seasonCount != null && episodeCount != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.series_counts_format,
                                                seasonCount,
                                                episodeCount
                                            ),
                                            color = NetflixLightGray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }

                            if (genres.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = genres,
                                    color = NetflixLightGray,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 600.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = overview,
                                color = NetflixLightGray,
                                fontSize = 14.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 600.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Play / Resume Button with default focus and clear white outline ring.
                            // When unfinished history exists for this title, resume the saved
                            // episode (series) at its saved position — the player seeks
                            // automatically via WatchHistoryStore.getSavedPosition(contentId).
                            val resumeMinutes = resumeItem?.remainingMinutes?.takeIf { it > 0 }?.toInt()
                            val playLabel = when {
                                resumeItem != null && resumeMinutes != null -> {
                                    val resume = stringResource(R.string.resume_play)
                                    val remaining = stringResource(R.string.remaining_minutes, resumeMinutes)
                                    "$resume · $remaining"
                                }
                                resumeItem != null -> stringResource(R.string.resume_play)
                                contentType == "movie" -> stringResource(R.string.play_now)
                                else -> stringResource(R.string.play_episode_1)
                            }
                            // Trailer, when the detail payload carries one (movies and
                            // series alike): opens the YouTube URL in the system
                            // handler. Secondary to Play — dark card, white ring.
                            val trailerUrl = uiState.movie?.trailerUrl
                                ?: uiState.series?.trailerUrl
                            val hasSeasons = uiState.series?.seasons?.isNotEmpty() == true
                            val hasEpisodes = !uiState.isLoadingEpisodes && uiState.episodes.isNotEmpty()
                            val downTarget = if (contentType == "tv_series") {
                                when {
                                    hasSeasons -> seasonRailLink
                                    hasEpisodes -> episodesRailLink
                                    else -> FocusRequester.Cancel
                                }
                            } else FocusRequester.Cancel

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        if (contentType == "movie") {
                                            uiState.movie?.let {
                                                onNavigateToPlayer("movie", it.id, it.title, it.fullPosterUrl, it.fullBackdropUrl, it.slug, null, null)
                                            }
                                        } else {
                                            val resume = resumeItem
                                            if (resume != null) {
                                                onNavigateToPlayer(
                                                    resume.contentType,
                                                    resume.contentId,
                                                    resume.title,
                                                    resume.posterUrl,
                                                    resume.backdropUrl,
                                                    resume.slug,
                                                    resume.seasonNumber,
                                                    resume.episodeNumber
                                                )
                                            } else {
                                                val firstEp = uiState.episodes.firstOrNull()
                                                    ?: uiState.series?.defaultSeason?.episodes?.firstOrNull()
                                                if (firstEp != null) {
                                                    onNavigateToPlayer(
                                                        "episode",
                                                        firstEp.id,
                                                        "${uiState.series?.title} - ${firstEp.name}",
                                                        firstEp.fullStillUrl,
                                                        uiState.series?.fullBackdropUrl,
                                                        uiState.series?.slug,
                                                        uiState.selectedSeason?.seasonNumber ?: 1,
                                                        firstEp.episodeNumber
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.colors(
                                        containerColor = NetflixRed,
                                        contentColor = NetflixWhite,
                                        focusedContainerColor = NetflixRed,
                                        focusedContentColor = NetflixWhite
                                    ),
                                    scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                                    border = ButtonDefaults.border(
                                        focusedBorder = Border(
                                            border = BorderStroke(2.5.dp, NetflixWhite)
                                        )
                                    ),
                                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                    modifier = Modifier
                                        .focusRequester(playButtonFocusRequester)
                                        .onFocusChanged { playFocused = it.isFocused }
                                        .focusProperties {
                                            left = FocusRequester.Cancel
                                            right = if (!trailerUrl.isNullOrBlank()) trailerButtonFocusRequester else FocusRequester.Cancel
                                            up = FocusRequester.Cancel
                                            down = downTarget
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.play)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = playLabel,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                if (!trailerUrl.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(trailerUrl)
                                                    )
                                                )
                                            } catch (_: Exception) {
                                            }
                                        },
                                        colors = ButtonDefaults.colors(
                                            containerColor = NetflixDarkCard,
                                            contentColor = NetflixWhite,
                                            // Stay dark on focus (never invert to
                                            // white): the ring must read on the
                                            // dark header, same as rows.
                                            focusedContainerColor = NetflixDarkCard,
                                            focusedContentColor = NetflixWhite
                                        ),
                                        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                                        border = ButtonDefaults.border(
                                            focusedBorder = Border(
                                                border = BorderStroke(2.5.dp, NetflixWhite)
                                            )
                                        ),
                                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                        modifier = Modifier
                                            .focusRequester(trailerButtonFocusRequester)
                                            .onFocusChanged { trailerFocused = it.isFocused }
                                            .focusProperties {
                                                left = playButtonFocusRequester
                                                right = FocusRequester.Cancel
                                                up = FocusRequester.Cancel
                                                down = downTarget
                                            }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.trailer),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // If Series: Season selector & Episode list
                if (contentType == "tv_series" && uiState.series != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(
                                text = stringResource(R.string.select_season_episode),
                                color = NetflixWhite,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Seasons Row
                            val seasons = uiState.series?.seasons ?: emptyList()
                            if (seasons.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .focusRequester(seasonRailLink)
                                        .focusRestorer { seasonFallbackRequester }
                                ) {
                                    items(seasons.size) { sIndex ->
                                        val season = seasons[sIndex]
                                        val isFirstSeason = sIndex == 0
                                        val isLastSeason = sIndex == seasons.lastIndex
                                        val isSelected = season.id == uiState.selectedSeason?.id ||
                                                season.seasonNumber == uiState.selectedSeason?.seasonNumber
                                        val sRequester = seasonRequesters.getOrPut(sIndex) { FocusRequester() }
                                        val isSeasonFallback = sIndex == lastFocusedSeasonIndex.coerceIn(0, (seasons.size - 1).coerceAtLeast(0))

                                        Box(
                                            Modifier.tvGridFocusFrame(horizontalPadding = 2.dp, verticalPadding = 6.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.selectSeason(slug, season) },
                                                colors = ButtonDefaults.colors(
                                                    containerColor = if (isSelected) NetflixRed else NetflixDarkCard,
                                                    contentColor = NetflixWhite,
                                                    // Unselected stays dark on focus
                                                    // (never inverts to white) so the
                                                    // white ring stays visible.
                                                    focusedContainerColor = if (isSelected) NetflixRed else NetflixDarkCard,
                                                    focusedContentColor = NetflixWhite
                                                ),
                                                scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                                                border = ButtonDefaults.border(
                                                    focusedBorder = Border(
                                                        border = BorderStroke(2.5.dp, NetflixWhite)
                                                    )
                                                ),
                                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                                                modifier = Modifier
                                                    .focusRequester(sRequester)
                                                    .then(if (isSeasonFallback) Modifier.focusRequester(seasonFallbackRequester) else Modifier)
                                                    .onFocusChanged { if (it.isFocused) lastFocusedSeasonIndex = sIndex }
                                                    .focusProperties {
                                                        up = playButtonFocusRequester
                                                        down = if (!uiState.isLoadingEpisodes && uiState.episodes.isNotEmpty()) episodesRailLink else FocusRequester.Cancel
                                                        left = if (isFirstSeason) FocusRequester.Cancel else (seasonRequesters[sIndex - 1] ?: FocusRequester.Default)
                                                        right = if (isLastSeason) FocusRequester.Cancel else (seasonRequesters[sIndex + 1] ?: FocusRequester.Default)
                                                    }
                                                    .onPreviewKeyEvent { event ->
                                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                                            try {
                                                                playButtonFocusRequester.requestFocus()
                                                                true
                                                            } catch (_: Exception) {
                                                                detailScope.launch {
                                                                    try {
                                                                        detailListState.scrollToItem(0)
                                                                        withFrameNanos {}
                                                                        playButtonFocusRequester.requestFocus()
                                                                    } catch (_: Exception) {}
                                                                }
                                                                true
                                                            }
                                                        } else {
                                                            false
                                                        }
                                                    }
                                            ) {
                                                Text(text = season.name)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Episodes List
                            if (uiState.isLoadingEpisodes) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    TvLoadingSpinner()
                                }
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .focusRequester(episodesRailLink)
                                        .focusRestorer { episodeFallbackRequester }
                                ) {
                                    items(uiState.episodes.size) { epIndex ->
                                        val ep = uiState.episodes[epIndex]
                                        val isFirstEp = epIndex == 0
                                        val isLastEp = epIndex == uiState.episodes.lastIndex
                                        val epRequester = episodeRequesters.getOrPut(epIndex) { FocusRequester() }
                                        val isEpFallback = epIndex == lastFocusedEpisodeIndex.coerceIn(0, (uiState.episodes.size - 1).coerceAtLeast(0))
                                        val hasSeasons = uiState.series?.seasons?.isNotEmpty() == true

                                        Box(
                                            Modifier.tvGridFocusFrame(horizontalPadding = 2.dp, verticalPadding = 10.dp)
                                        ) {
                                            TvEpisodeCard(
                                                episode = ep,
                                                onClick = {
                                                    onNavigateToPlayer(
                                                        "episode",
                                                        ep.id,
                                                        "${uiState.series?.title} - ${ep.name}",
                                                        ep.fullStillUrl,
                                                        uiState.series?.fullBackdropUrl,
                                                        uiState.series?.slug,
                                                        uiState.selectedSeason?.seasonNumber ?: 1,
                                                        ep.episodeNumber
                                                    )
                                                },
                                                modifier = Modifier
                                                    .focusRequester(epRequester)
                                                    .then(if (isEpFallback) Modifier.focusRequester(episodeFallbackRequester) else Modifier)
                                                    .onFocusChanged { if (it.isFocused) lastFocusedEpisodeIndex = epIndex }
                                                    .focusProperties {
                                                        up = if (hasSeasons) seasonRailLink else playButtonFocusRequester
                                                        down = FocusRequester.Cancel
                                                        left = if (isFirstEp) FocusRequester.Cancel else (episodeRequesters[epIndex - 1] ?: FocusRequester.Default)
                                                        right = if (isLastEp) FocusRequester.Cancel else (episodeRequesters[epIndex + 1] ?: FocusRequester.Default)
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Cast list
                if (castList.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(start = 48.dp, top = 16.dp, bottom = 32.dp)) {
                            Text(
                                text = stringResource(R.string.cast),
                                color = NetflixWhite,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                // End inset mirrors the 48dp start inset below:
                                // the column pads only the start, so the last
                                // avatar needs scroll room to settle clear of
                                // the viewport edge.
                                contentPadding = PaddingValues(end = 48.dp),
                                modifier = Modifier.focusRestorer()
                            ) {
                                items(castList.take(12), key = { it.id }) { cast ->
                                    TvCastCard(cast = cast)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvEpisodeCard(
    episode: EpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val highResStillUrl = remember(episode.fullStillUrl) { episode.fullStillUrl.toFullQualityBackdropUrl() }
    val stillRequest = remember(highResStillUrl) {
        ImageRequest.Builder(context)
            .data(highResStillUrl)
            .size(640, 360)
            .precision(Precision.EXACT)
            .allowRgb565(false)
            .crossfade(false)
            .build()
    }
    // Black halo outside the white ring so the selection reads on bright
    // stills too (same B&W-only treatment as the rail cards).
    var epFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(180.dp)
            .onFocusChanged { epFocused = it.isFocused },
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.colors(
            containerColor = NetflixDarkCard
        ),
        scale = CardDefaults.scale(focusedScale = TvSizing.CardFocusScale),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ),
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, NetflixWhite)
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (epFocused) {
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            color = Color.Black,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = stroke)
                        )
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                ) {
                    AsyncImage(
                        model = stillRequest,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(Color(0xBB000000), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.episode_badge, episode.episodeNumber),
                            color = NetflixWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.episode_title_format,
                            episode.episodeNumber,
                            episode.name
                        ),
                        color = NetflixWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = episode.overview ?: "",
                        color = NetflixLightGray,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun TvCastCard(cast: CastMember) {
    val highResProfileUrl = remember(cast.fullProfileUrl) { cast.fullProfileUrl.toFullQualityPosterUrl() }
    // Deliberately NOT focusable: cast rows are informational only (no detail
    // destination), so a focusable no-op Card would trap the D-pad on a dead control.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(105.dp)
            .padding(vertical = 4.dp)
            .background(Color.Transparent, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        AsyncImage(
            model = highResProfileUrl,
            contentDescription = cast.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(NetflixDarkCard)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = cast.name,
            color = NetflixWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = cast.character ?: "",
            color = NetflixLightGray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
