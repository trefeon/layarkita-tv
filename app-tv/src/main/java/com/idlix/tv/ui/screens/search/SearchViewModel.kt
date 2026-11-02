package com.idlix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idlix.data.model.SearchResultItem
import com.idlix.data.repository.IdlixRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<SearchResultItem> = emptyList(),
    val error: String? = null,
    val errorRes: Int? = null,
    /**
     * Trimmed query the visible [results]/[error] were settled for.
     * The screen compares this against the live field text to know whether
     * the grid is fresh (safe D-pad DOWN target) or stale (stay in field).
     */
    val searchedQuery: String = ""
)

/**
 * Remote-typing settle window. D-pad / on-screen keyboards deliver one
 * character per press with a hunt-and-peck rhythm, so a short window fires
 * after the FIRST character instead of the full query. 700ms waits until
 * typing settles, then searches once.
 */
const val SEARCH_DEBOUNCE_MS = 700L

class SearchViewModel(
    private val repository: IdlixRepository = IdlixRepository(),
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
    injectedScope: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // Production runs on viewModelScope; tests inject a plain JVM scope so
    // the debounce is exercisable without Dispatchers.Main. viewModelScope
    // is only touched when no scope was injected.
    private val searchScope: CoroutineScope = injectedScope ?: viewModelScope

    private var searchJob: Job? = null

    /**
     * Deep-link entry (global SEARCH / launcher voice): same debounced path
     * as typed input, so a pre-filled query still searches.
     */
    fun setQuery(query: String) {
        onQueryChange(query)
    }

    /**
     * Immediate UI state: the field text updates on EVERY keystroke and
     * focus never leaves the field. The network search waits for
     * [debounceMs] of silence, then runs once for the final query. Each new
     * keystroke cancels the pending search via `Job.cancel()`.
     */
    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                error = null,
                errorRes = null,
                searchedQuery = ""
            )
            return
        }

        val target = newQuery.trim()
        searchJob = searchScope.launch {
            delay(debounceMs)
            runSearch(target)
        }
    }

    /**
     * IME Search fast path: skip the settle window and search immediately.
     * The screen sets pendingFocusOnResults so focus jumps to the grid when
     * this search lands (or parks back on the field when it comes up empty).
     */
    fun searchNow() {
        searchQuery(_uiState.value.query)
    }

    /**
     * Complete-query entry (quick-search tag): replaces the field content
     * and searches at once on the immediate lane — no settle wait, and no
     * field-focus bounce (which would pop the keyboard over the results).
     */
    fun searchQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                error = null,
                errorRes = null,
                searchedQuery = ""
            )
            return
        }

        val target = query.trim()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, errorRes = null)
        searchJob = searchScope.launch {
            runSearch(target)
        }
    }

    private suspend fun runSearch(target: String) {
        try {
            val results = repository.search(target)
            // A newer keystroke may have cancelled us while the network call
            // was in flight: never publish stale results or clear newer state.
            currentCoroutineContext().ensureActive()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                results = results,
                error = null,
                errorRes = null,
                searchedQuery = target
            )
        } catch (e: CancellationException) {
            // Superseded keystroke: not an error, just stay quiet so the
            // newer search owns the state. MUST rethrow so cancellation works.
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.localizedMessage,
                errorRes = com.idlix.tv.R.string.search_error,
                searchedQuery = target
            )
        }
    }
}
