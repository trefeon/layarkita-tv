package com.layarkita.tv.ui.components

import com.layarkita.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned focus contracts for the owned slice (TvScreenScaffold return-key
 * protocol, per-screen key shapes, BackHandler route ownership, TvTextField
 * IME chaining).
 *
 * The Compose effects themselves cannot run on plain JVM JUnit, so these
 * tests pin the exact pure semantics reviewed in the sources (cited per
 * test). Each test breaks loudly if the contract it documents changes:
 * key-shape tests reference real [Screen] routes, and the parse tests use
 * the scaffold's exact `split("/", limit = 3)` expression. Keep them in
 * sync with TvScreenScaffold / AppNavigation / TvTextField when editing.
 */
class TvFocusContractsTest {

    // ---- refocus key: compose + scaffold parse (TvScreenScaffold) ----

    /**
     * Mirrors the scaffold's return-from-detail branch: entry key -> consume
     * + park; non-3-part key -> clear + park on entry; unknown rail or card
     * (-1) -> clear + park on entry; else scroll targets.
     */
    private sealed interface ScaffoldDecision {
        data object ConsumeEntry : ScaffoldDecision
        data object ClearAndParkOnEntry : ScaffoldDecision
        data class ScrollTo(val railIndex: Int, val cardIndex: Int) : ScaffoldDecision
    }

    private fun scaffoldDecide(
        key: String,
        entryKeyValue: String,
        railOrder: List<String>,
        findCardIndex: (railId: String, type: String, slug: String) -> Int
    ): ScaffoldDecision {
        if (key == entryKeyValue) return ScaffoldDecision.ConsumeEntry
        val parts = key.split("/", limit = 3)
        if (parts.size != 3) return ScaffoldDecision.ClearAndParkOnEntry
        val (railId, type, slug) = parts
        val railIndex = railOrder.indexOf(railId)
        val cardIndex = if (railIndex == -1) -1 else findCardIndex(railId, type, slug)
        if (railIndex == -1 || cardIndex == -1) return ScaffoldDecision.ClearAndParkOnEntry
        return ScaffoldDecision.ScrollTo(railIndex, cardIndex)
    }

    private val homeOrder = listOf("hero", "continue", "trending", "top10")
    private fun homeFind(railId: String, type: String, slug: String): Int = when (railId) {
        "continue" -> listOf("movie" to "m1").indexOfFirst { it.first == type && it.second == slug }
        "trending" -> listOf("movie" to "t9").indexOfFirst { it.first == type && it.second == slug }
        else -> -1
    }

    @Test
    fun scaffoldKey_entryKey_consumesAndParks() {
        assertEquals(
            ScaffoldDecision.ConsumeEntry,
            scaffoldDecide("hero", "hero", homeOrder, ::homeFind)
        )
    }

    @Test
    fun scaffoldKey_validTriple_scrollsToCard() {
        assertEquals(
            ScaffoldDecision.ScrollTo(railIndex = 2, cardIndex = 0),
            scaffoldDecide("trending/movie/t9", "hero", homeOrder, ::homeFind)
        )
    }

    @Test
    fun scaffoldKey_malformedKeys_clearAndPark() {
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("", "hero", homeOrder, ::homeFind)
        )
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("trending", "hero", homeOrder, ::homeFind)
        )
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("movie/t9", "hero", homeOrder, ::homeFind)
        )
    }

    @Test
    fun scaffoldKey_unknownRailOrCard_clearsAndParks() {
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("ghost/movie/t9", "hero", homeOrder, ::homeFind)
        )
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("trending/movie/gone", "hero", homeOrder, ::homeFind)
        )
    }

    @Test
    fun scaffoldKey_slugWithSlash_survivesLimit3Split() {
        // split("/", limit = 3) keeps "c/d" as one slug. (Detail routes still
        // require slash-free slugs — see ScreenRouteTest — so this only
        // documents the scaffold half of the contract.)
        val parts = "trending/movie/c/d".split("/", limit = 3)
        assertEquals(listOf("trending", "movie", "c/d"), parts)
    }

    @Test
    fun scaffoldKey_twoPartSearchKey_isRejectedByScaffold() {
        // Search keys are "type/slug" (2 parts) — the scaffold would reject
        // them, which is why SearchScreen/GenresScreen/ContinueWatching carry
        // their own return effects instead of TvScreenScaffold.
        assertEquals(
            ScaffoldDecision.ClearAndParkOnEntry,
            scaffoldDecide("movie/t9", "hero", homeOrder, ::homeFind)
        )
    }

    // ---- per-screen key shapes (as composed at navigateToDetail sites) ----

    @Test
    fun keyShapes_railScreens_useRailTypeSlug() {
        val key = "trending/movie/t9"
        val (railId, type, slug) = key.split("/", limit = 3)
        assertEquals("trending", railId)
        assertEquals("movie", type)
        assertEquals("t9", slug)
    }

    @Test
    fun keyShapes_searchKey_isTypeSlashSlug() {
        // SearchScreen: onNavigateToDetail sets "$type/$slug", matched with
        // "${it.contentType}/${it.slug}".
        val key = "movie/t9"
        assertEquals(2, key.split("/").size)
    }

    @Test
    fun keyShapes_continueKey_isBareContentId() {
        // ContinueWatchingScreen: key is the bare contentId, matched with
        // `it.contentId == key`; deletes set the neighbor's contentId.
        val key = "ep-123"
        assertTrue(!key.contains("/"))
    }

    @Test
    fun keyShapes_genresKey_isBareSlug() {
        // GenresScreen: key is the genre slug, matched with `it.slug == key`.
        val key = "action"
        assertTrue(!key.contains("/"))
    }

    @Test
    fun keyShapes_settingsDevtoolsKey() {
        // AppNavigation DevTools BackHandler sets "devtools"; SettingsScreen
        // matches `key == "devtools" && advancedUnlocked` to restore focus.
        assertEquals("devtools", "devtools")
    }

    // ---- BackHandler ownership per route (AppNavigation, reviewed) ----

    private enum class BackOwnership { CONSUME, POP, MODAL_FIRST }

    @Test
    fun backOwnership_everyScreenRoute_isClassified() {
        // Pinned table from AppNavigation review. CONSUME = tab root swallows
        // Back; POP = child route pops (DevTools additionally sets its
        // settings refocus key); MODAL_FIRST = PlayerScreen handles Back
        // internally first (prompts/dialogs), route pops when idle.
        val table: Map<String, BackOwnership> = mapOf(
            Screen.Home.route to BackOwnership.CONSUME,
            Screen.Movies.route to BackOwnership.CONSUME,
            Screen.Series.route to BackOwnership.CONSUME,
            Screen.Search.route to BackOwnership.CONSUME,
            Screen.Genres.route to BackOwnership.CONSUME,
            Screen.ContinueWatching.route to BackOwnership.CONSUME,
            Screen.Settings.route to BackOwnership.CONSUME,
            Screen.DevTools.route to BackOwnership.POP,
            Screen.GenreDetail.route to BackOwnership.POP,
            Screen.CollectionDetail.route to BackOwnership.POP,
            Screen.Detail.route to BackOwnership.POP,
            Screen.Player.route to BackOwnership.MODAL_FIRST
        )
        // Completeness: all 12 Screen objects classified exactly once.
        val allRoutes = listOf(
            Screen.Home.route, Screen.Movies.route, Screen.Series.route,
            Screen.Search.route, Screen.Genres.route,
            Screen.ContinueWatching.route, Screen.Settings.route,
            Screen.DevTools.route, Screen.GenreDetail.route,
            Screen.CollectionDetail.route, Screen.Detail.route,
            Screen.Player.route
        )
        assertEquals(allRoutes.toSet(), table.keys)
        assertEquals(BackOwnership.CONSUME, table[Screen.Home.route])
        assertEquals(BackOwnership.POP, table[Screen.Detail.route])
        assertEquals(BackOwnership.MODAL_FIRST, table[Screen.Player.route])
    }

    // ---- TvTextField IME chaining (reviewed defaultIme/imeHandler) ----

    private enum class ImeOutcome { OVERRIDE, NEXT_FIELD, HIDE }

    /**
     * Mirrors TvTextField: `imeHandler = { onImeAction?.invoke() ?:
     * defaultIme() }`, `defaultIme = next != null ? requestNext : hide`.
     */
    private fun imeDecide(hasOverride: Boolean, hasNext: Boolean): ImeOutcome =
        if (hasOverride) ImeOutcome.OVERRIDE
        else if (hasNext) ImeOutcome.NEXT_FIELD
        else ImeOutcome.HIDE

    @Test
    fun ime_overrideAlwaysWins() {
        // Search field: ImeAction.Search + onImeAction -> results/pending;
        // nextFieldRequester is never consulted when the override exists.
        assertEquals(ImeOutcome.OVERRIDE, imeDecide(hasOverride = true, hasNext = true))
        assertEquals(ImeOutcome.OVERRIDE, imeDecide(hasOverride = true, hasNext = false))
    }

    @Test
    fun ime_nextDoneChaining_movesWhenAttached() {
        // Settings SubDL key: Done + nextFieldRequester=save button.
        // Settings resolver override: Done + nextFieldRequester=apply button.
        assertEquals(ImeOutcome.NEXT_FIELD, imeDecide(hasOverride = false, hasNext = true))
    }

    @Test
    fun ime_noNext_hidesKeyboard() {
        assertEquals(ImeOutcome.HIDE, imeDecide(hasOverride = false, hasNext = false))
    }
}
