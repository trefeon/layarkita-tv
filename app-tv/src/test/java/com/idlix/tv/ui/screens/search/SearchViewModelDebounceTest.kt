package com.idlix.tv.ui.screens.search

import com.idlix.data.api.IdlixDirectApi
import com.idlix.data.cache.DirectSourceCache
import com.idlix.data.model.SearchResponse
import com.idlix.data.model.SearchResultItem
import com.idlix.data.repository.IdlixRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debounce contract for remote typing: `d` -> `de` -> `dex` in quick
 * succession fires exactly ONE repository search for the final query, the
 * field state is synchronous per keystroke, blank cancels everything, and
 * IME Search ([SearchViewModel.searchNow]) skips the settle window.
 *
 * No coroutines-test / Main dispatcher here (not in the version catalog),
 * so the ViewModel takes an injected plain JVM scope and a short debounce;
 * production defaults ([SEARCH_DEBOUNCE_MS], viewModelScope) are untouched.
 */
class SearchViewModelDebounceTest {

    private class FakeSearchApi : IdlixDirectApi() {
        val queries = mutableListOf<String>()

        @Volatile
        var networkDelayMs: Long = 0L
        var failWith: RuntimeException? = null

        override suspend fun search(query: String): SearchResponse {
            // Record BEFORE the delay so tests can observe "search started".
            synchronized(queries) { queries += query }
            val pause = networkDelayMs
            if (pause > 0) delay(pause)
            failWith?.let { throw it }
            return SearchResponse(
                results = listOf(SearchResultItem(id = "id-$query", title = query, slug = query)),
                total = 1
            )
        }

        fun snapshot(): List<String> = synchronized(queries) { queries.toList() }
    }

    private fun viewModel(
        api: FakeSearchApi,
        debounceMs: Long = 150L
    ): Triple<SearchViewModel, CoroutineScope, FakeSearchApi> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = SearchViewModel(
            repository = IdlixRepository(api = api, cache = DirectSourceCache()),
            debounceMs = debounceMs,
            injectedScope = scope
        )
        return Triple(vm, scope, api)
    }

    private suspend fun awaitSettledFor(vm: SearchViewModel, query: String) {
        withTimeout(10_000) {
            while (vm.uiState.value.searchedQuery != query || vm.uiState.value.isLoading) {
                delay(25)
            }
        }
    }

    @Test
    fun rapidTypingFiresExactlyOneSearchWithFinalQuery() = runBlocking {
        val (vm, scope, api) = viewModel(FakeSearchApi())
        try {
            vm.onQueryChange("d")
            // Immediate UI state: the field shows every keystroke at once.
            assertEquals("d", vm.uiState.value.query)
            vm.onQueryChange("de")
            assertEquals("de", vm.uiState.value.query)
            vm.onQueryChange("dex")
            assertEquals("dex", vm.uiState.value.query)

            awaitSettledFor(vm, "dex")
            // Prove no second search leaks out after settling.
            delay(400)

            assertEquals(listOf("dex"), api.snapshot())
            assertEquals("dex", vm.uiState.value.searchedQuery)
            assertEquals(1, vm.uiState.value.results.size)
            assertEquals("dex", vm.uiState.value.results.first().title)
            assertFalse(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankQueryCancelsPendingSearchAndNeverHitsNetwork() = runBlocking {
        val (vm, scope, api) = viewModel(FakeSearchApi())
        try {
            vm.onQueryChange("dex")
            assertEquals("dex", vm.uiState.value.query)
            vm.onQueryChange("")
            assertEquals("", vm.uiState.value.query)

            // Past the debounce window: nothing may have fired.
            delay(500)

            assertTrue(api.snapshot().isEmpty())
            assertTrue(vm.uiState.value.results.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
            assertEquals("", vm.uiState.value.searchedQuery)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankQueryAfterSettledResultsClearsWithoutNewSearch() = runBlocking {
        val (vm, scope, api) = viewModel(FakeSearchApi())
        try {
            vm.onQueryChange("dex")
            awaitSettledFor(vm, "dex")
            assertEquals(1, api.snapshot().size)

            vm.onQueryChange("")
            assertTrue(vm.uiState.value.results.isEmpty())
            assertEquals("", vm.uiState.value.searchedQuery)
            assertFalse(vm.uiState.value.isLoading)

            delay(400)
            assertEquals(1, api.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queryIsSynchronousBeforeAnySearchFires() = runBlocking {
        val (vm, scope, api) = viewModel(FakeSearchApi())
        try {
            vm.onQueryChange("d")
            // Right after the call returns: field updated, network untouched.
            assertEquals("d", vm.uiState.value.query)
            assertTrue(api.snapshot().isEmpty())
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun imeSearchRunsImmediatelyWithoutWaitingForDebounce() = runBlocking {
        // A 60s settle window: if searchNow waited for the debounce, this
        // test would time out instead of passing in milliseconds.
        val (vm, scope, api) = viewModel(FakeSearchApi(), debounceMs = 60_000L)
        try {
            vm.onQueryChange("dex")
            assertTrue(api.snapshot().isEmpty())

            vm.searchNow()
            awaitSettledFor(vm, "dex")

            assertEquals(listOf("dex"), api.snapshot())
            assertEquals("dex", vm.uiState.value.results.first().title)
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tagSearchSetsQueryAndSearchesImmediately() = runBlocking {
        // A 60s settle window: a debounced path would never fire in-test.
        val (vm, scope, api) = viewModel(FakeSearchApi(), debounceMs = 60_000L)
        try {
            vm.searchQuery("Batman")
            assertEquals("Batman", vm.uiState.value.query)
            awaitSettledFor(vm, "Batman")

            assertEquals(listOf("Batman"), api.snapshot())
            assertEquals("Batman", vm.uiState.value.results.first().title)
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun supersededInFlightSearchNeverOverwritesNewerResults() = runBlocking {
        val api = FakeSearchApi().also { it.networkDelayMs = 400L }
        val (vm, scope, _) = viewModel(api, debounceMs = 50L)
        try {
            vm.onQueryChange("a")
            // Wait until the first search is actually in flight, then type on.
            withTimeout(10_000) {
                while (api.snapshot().isEmpty()) delay(20)
            }
            vm.onQueryChange("ab")

            awaitSettledFor(vm, "ab")
            delay(500)

            // Both requests reached the repo (cancellation can't recall the
            // network call), but only the newer result was published — and
            // the cancelled first search never surfaced as an error.
            assertEquals(listOf("a", "ab"), api.snapshot())
            assertEquals("ab", vm.uiState.value.searchedQuery)
            assertEquals("ab", vm.uiState.value.results.first().title)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally {
            scope.cancel()
        }
    }
}
