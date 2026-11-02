package com.idlix.tv.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idlix.data.local.WatchHistoryStore
import com.idlix.data.model.CollectionItem
import com.idlix.data.model.MovieItem
import com.idlix.data.model.SeriesItem
import com.idlix.data.model.WatchHistoryItem
import com.idlix.data.model.toSeriesItem
import com.idlix.data.repository.IdlixRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroMovie: MovieItem? = null,
    val featured: List<MovieItem> = emptyList(),
    val continueWatching: List<WatchHistoryItem> = emptyList(),
    val trending: List<MovieItem> = emptyList(),
    val popularMovies: List<MovieItem> = emptyList(),
    val popularSeries: List<SeriesItem> = emptyList(),
    val actionMovies: List<MovieItem> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
    val error: String? = null,
    val errorRes: Int? = null
)

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = IdlixRepository()
    private val historyStore = WatchHistoryStore.getInstance(application.applicationContext)

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = false))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Collect unfinished watch history items reactively
        viewModelScope.launch {
            historyStore.historyFlow.collect { allItems ->
                val unfinished = allItems.filter { it.isUnfinished }
                _uiState.value = _uiState.value.copy(continueWatching = unfinished)
            }
        }
        loadHomeData()
    }

    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, errorRes = null)
            try {
                var homepage = runCatching { repository.getHomepageData() }.getOrNull()
                var featured = homepage?.featured.orEmpty()
                var trending = homepage?.trendingNow?.takeIf { it.isNotEmpty() } ?: repository.getTrendingMovies()
                var popular = homepage?.trendingCountry?.takeIf { it.isNotEmpty() }
                    ?: homepage?.latestMovies?.takeIf { it.isNotEmpty() }
                    ?: repository.getPopularMovies()
                var series = repository.getPopularSeries().ifEmpty {
                    homepage?.latestSeries?.map { it.toSeriesItem() }.orEmpty()
                }

                // If all returned empty, wait 1s and retry once (network socket warmup on startup)
                if (featured.isEmpty() && trending.isEmpty() && popular.isEmpty() && series.isEmpty()) {
                    kotlinx.coroutines.delay(1000)
                    homepage = runCatching { repository.getHomepageData() }.getOrNull()
                    featured = homepage?.featured.orEmpty()
                    trending = homepage?.trendingNow?.takeIf { it.isNotEmpty() } ?: repository.getTrendingMovies()
                    popular = homepage?.trendingCountry?.takeIf { it.isNotEmpty() }
                        ?: homepage?.latestMovies?.takeIf { it.isNotEmpty() }
                        ?: repository.getPopularMovies()
                    series = repository.getPopularSeries().ifEmpty {
                        homepage?.latestSeries?.map { it.toSeriesItem() }.orEmpty()
                    }
                }
                if (featured.isEmpty() && trending.isEmpty() && popular.isEmpty() && series.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        errorRes = com.idlix.tv.R.string.error_home_load
                    )
                    return@launch
                }

                val action = repository.getMoviesByGenre("action")

                // Collections are a browse-assist rail, never a gate: a failed
                // fetch degrades to no rail, it must not blank Home.
                val collections = runCatching { repository.getCollections().collections }
                    .getOrNull() ?: emptyList()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    heroMovie = featured.firstOrNull() ?: trending.firstOrNull() ?: popular.firstOrNull(),
                    featured = featured,
                    trending = trending,
                    popularMovies = popular,
                    popularSeries = series,
                    actionMovies = action,
                    collections = collections,
                    error = null,
                    errorRes = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage,
                    errorRes = com.idlix.tv.R.string.error_home_load
                )
            }
        }
    }

    fun refreshContinueWatching() {
        val unfinished = historyStore.getUnfinishedItems()
        _uiState.value = _uiState.value.copy(continueWatching = unfinished)
    }

    fun removeFromHistory(contentId: String) {
        historyStore.removeItem(contentId)
        refreshContinueWatching()
    }

    fun setHeroMovie(movie: MovieItem) {
        _uiState.value = _uiState.value.copy(heroMovie = movie)
    }
}
