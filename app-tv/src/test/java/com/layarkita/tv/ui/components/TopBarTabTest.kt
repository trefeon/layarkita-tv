package com.layarkita.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ownership table for [TopBarTab.fromRoute] (owned slice: TvTopBar chrome).
 *
 * Pure string matching — no Compose, no Resources lookup at runtime
 * (labelRes ints are compile-time constants). Pins which route highlights
 * which tab, including the deliberate fallbacks reviewed in AppNavigation:
 * fullscreen child routes (detail/player/collection/settings/devtools) map
 * to HOME because those screens either hide the bar or pass an explicit
 * selection.
 */
class TopBarTabTest {

    @Test
    fun tabRoots_mapToTheirTab() {
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("home"))
        assertEquals(TopBarTab.MOVIES, TopBarTab.fromRoute("movies"))
        assertEquals(TopBarTab.SERIES, TopBarTab.fromRoute("series"))
        assertEquals(TopBarTab.GENRES, TopBarTab.fromRoute("genres"))
        assertEquals(TopBarTab.SEARCH, TopBarTab.fromRoute("search"))
        assertEquals(TopBarTab.LIBRARY, TopBarTab.fromRoute("continue_watching"))
    }

    @Test
    fun searchWithQuery_staysOnSearch() {
        assertEquals(TopBarTab.SEARCH, TopBarTab.fromRoute("search?initialQuery=avengers"))
    }

    @Test
    fun genreDetail_mapsToGenres() {
        assertEquals(TopBarTab.GENRES, TopBarTab.fromRoute("genre/action?name=Action"))
    }

    @Test
    fun fullscreenChildren_fallBackToHome() {
        // Detail, player, collection detail, settings and devtools never rely
        // on fromRoute for their chrome state — pinned so a future caller
        // using fromRoute directly on these routes notices the HOME highlight.
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("detail/movie/slug-1"))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("detail/tv_series/slug-2"))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("player/movie/42/Title?poster=&backdrop="))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("collection/marvel?name=Marvel"))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("settings"))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("devtools"))
    }

    @Test
    fun nullRoute_mapsToNull() {
        assertNull(TopBarTab.fromRoute(null))
    }

    @Test
    fun unknownRoute_fallsBackToHome() {
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute("something-new"))
        assertEquals(TopBarTab.HOME, TopBarTab.fromRoute(""))
    }

    @Test
    fun everyTab_hasDistinctRoute() {
        val routes = TopBarTab.values().map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
