package com.layarkita.tv.ui.screens

import com.layarkita.data.model.MovieItem
import com.layarkita.tv.ui.screens.history.neighborKeyAfterDelete
import com.layarkita.tv.ui.screens.home.homeHeroMovies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned route-audit contracts (SectionAuditSlice): the pure focus/return
 * decisions the screens make around partial data. Compose effects cannot run
 * on plain JVM JUnit, so these pin the exact helper semantics the composables
 * call — each test breaks loudly if the wired behavior changes.
 */
class RouteAuditTest {

    private fun movie(id: String) = MovieItem(id = id, title = "T$id", slug = "s$id")

    // ---- homeHeroMovies (HomeScreen billboard + entry gate agree) ----

    @Test
    fun homeHero_featuredWinsUncapped() {
        val featured = (1..10).map { movie("f$it") }
        assertEquals(featured, homeHeroMovies(featured, listOf(movie("t1")), listOf(movie("p1"))))
    }

    @Test
    fun homeHero_emptyFeaturedFallsBackToTrendingCappedAt8() {
        val trending = (1..10).map { movie("t$it") }
        assertEquals(trending.take(8), homeHeroMovies(emptyList(), trending, listOf(movie("p1"))))
    }

    @Test
    fun homeHero_emptyFeaturedAndTrendingFallsBackToPopularCappedAt8() {
        val popular = (1..10).map { movie("p$it") }
        assertEquals(popular.take(8), homeHeroMovies(emptyList(), emptyList(), popular))
    }

    @Test
    fun homeHero_totalEmptyYieldsEmptyBillboard() {
        assertTrue(homeHeroMovies(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun homeHero_singleItemListsPassThrough() {
        val one = listOf(movie("f1"))
        assertEquals(one, homeHeroMovies(one, emptyList(), emptyList()))
    }

    // ---- neighborKeyAfterDelete (ContinueWatching delete refocus) ----

    @Test
    fun neighbor_firstPrefersNext() {
        assertEquals("b", neighborKeyAfterDelete(listOf("a", "b", "c"), "a"))
    }

    @Test
    fun neighbor_middlePrefersNext() {
        assertEquals("c", neighborKeyAfterDelete(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun neighbor_lastFallsBackToPrevious() {
        assertEquals("b", neighborKeyAfterDelete(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun neighbor_singletonEmptiesToExplore() {
        assertNull(neighborKeyAfterDelete(listOf("a"), "a"))
    }

    @Test
    fun neighbor_unknownIdClears() {
        assertNull(neighborKeyAfterDelete(listOf("a", "b"), "ghost"))
    }

    @Test
    fun neighbor_emptyListClears() {
        assertNull(neighborKeyAfterDelete(emptyList(), "a"))
    }

    @Test
    fun neighbor_pairEitherDeletionLeavesSurvivor() {
        assertEquals("b", neighborKeyAfterDelete(listOf("a", "b"), "a"))
        assertEquals("a", neighborKeyAfterDelete(listOf("a", "b"), "b"))
    }
}
