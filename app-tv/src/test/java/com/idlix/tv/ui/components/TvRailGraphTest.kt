package com.idlix.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit contract for [TvRailGraph] (owned slice: TvRailShell + every rail
 * screen's order/graph wiring).
 *
 * Covers the pure order math — railIdx/prevRailId/nextRailId/linkFor(null) —
 * with zero FocusRequester instantiation (Compose focus objects are not
 * exercised in JVM unit tests; link creation order is covered by review).
 * The dynamic-order tests pin the invariant every screen documents:
 * [TvRailGraph.order] MUST mirror the LazyColumn items (entry + non-empty
 * rails only, same sequence), because the scaffold scrolls to
 * railOrder.indexOf(railId).
 */
class TvRailGraphTest {

    private fun graph(
        order: List<String>,
        entryId: String = "hero"
    ): TvRailGraph = TvRailGraph(
        order = order,
        entryId = entryId,
        entryLink = null,
        links = mutableMapOf()
    )

    // ---- index / neighbors ----

    @Test
    fun railIdx_returnsPositionOrMinusOne() {
        val g = graph(listOf("hero", "continue", "trending"))
        assertEquals(0, g.railIdx("hero"))
        assertEquals(1, g.railIdx("continue"))
        assertEquals(2, g.railIdx("trending"))
        assertEquals(-1, g.railIdx("nope"))
    }

    @Test
    fun prevRailId_walksBackwards_nullAtHead() {
        val g = graph(listOf("hero", "continue", "trending"))
        assertNull(g.prevRailId("hero"))
        assertEquals("hero", g.prevRailId("continue"))
        assertEquals("continue", g.prevRailId("trending"))
    }

    @Test
    fun nextRailId_walksForwards_nullAtTail() {
        val g = graph(listOf("hero", "continue", "trending"))
        assertEquals("continue", g.nextRailId("hero"))
        assertEquals("trending", g.nextRailId("continue"))
        assertNull(g.nextRailId("trending"))
    }

    @Test
    fun neighbors_nullAndUnknownIds_areNull() {
        val g = graph(listOf("hero", "continue"))
        assertNull(g.prevRailId(null))
        assertNull(g.nextRailId(null))
        assertNull(g.prevRailId("ghost"))
        assertNull(g.nextRailId("ghost"))
    }

    @Test
    fun neighbors_singleEntryGraph_areNull() {
        val g = graph(listOf("hero"))
        assertNull(g.prevRailId("hero"))
        assertNull(g.nextRailId("hero"))
    }

    // ---- link resolution without instantiating focus objects ----

    @Test
    fun linkFor_nullId_returnsNull() {
        val g = graph(listOf("hero", "continue"))
        assertNull(g.linkFor(null))
    }

    @Test
    fun linkFor_entryId_returnsEntryLink() {
        val g = graph(listOf("hero", "continue"), entryId = "hero")
        // entryLink is null here by construction; the branch under test is
        // "id == entryId -> return entryLink" (no map creation).
        assertNull(g.linkFor("hero"))
    }

    // ---- dynamic order invariant (mirrors every screen's buildList) ----

    @Test
    fun homeOrder_headIsEntry_skipsEmptyRails() {
        val continueWatching = emptyList<String>()
        val trending = listOf("t1")
        val top10 = emptyList<String>()
        val order = buildList {
            add("hero")
            if (continueWatching.isNotEmpty()) add("continue")
            if (trending.isNotEmpty()) add("trending")
            if (top10.isNotEmpty()) add("top10")
        }
        assertEquals(listOf("hero", "trending"), order)
        val g = graph(order)
        // Scaffold scroll target for the trending card == its column index.
        assertEquals(1, g.railIdx("trending"))
        assertEquals(-1, g.railIdx("continue"))
        assertEquals(-1, g.railIdx("top10"))
    }

    @Test
    fun allEmpty_orderIsEntryOnly_neighborsNull() {
        val g = graph(listOf("hero"))
        assertEquals(0, g.railIdx("hero"))
        assertNull(g.prevRailId("hero"))
        assertNull(g.nextRailId("hero"))
    }

    @Test
    fun collectionOrder_headerEntry_thenVisibleSections() {
        // Mirrors CollectionDetailScreen: header + visible sections in order.
        val sections = listOf("movies", "series")
        val order = listOf("header") + sections.filter { it.isNotEmpty() }
        val g = graph(order, entryId = "header")
        assertEquals(listOf("header", "movies", "series"), order)
        assertEquals(0, g.railIdx("header"))
        assertEquals("header", g.prevRailId("movies"))
        assertEquals("series", g.nextRailId("movies"))
        assertNull(g.nextRailId("series"))
    }

    @Test
    fun findCardIndexRule_unknownRail_isMinusOne() {
        // Every screen's findCardIndex returns -1 for an unknown railId, and
        // the scaffold treats railIndex == -1 || cardIndex == -1 as
        // stale-key -> clear + park on entry. Pin the lookup primitive the
        // rule is built on: indexOfFirst over an empty/missing list is -1.
        val railLists = mapOf("popular" to listOf("a", "b"))
        assertEquals(-1, railLists["ghost"]?.indexOfFirst { it == "a" } ?: -1)
        assertEquals(-1, railLists["popular"]?.indexOfFirst { it == "zzz" } ?: -1)
        assertEquals(1, railLists["popular"]?.indexOfFirst { it == "b" } ?: -1)
    }

    // ---- tvGridColumnCount & tvMatrixNeighbors tests ----

    @Test
    fun tvGridColumnCount_computesExpectedColumns() {
        val cols1080p = tvGridColumnCount(
            screenWidthDp = 960,
            minCellWidthDp = 135,
            horizontalPaddingDp = 96,
            contentInsetDp = 24,
            spacingDp = 16
        )
        // gridW = 960 - 96 - 24 = 840. (840 + 16) / (135 + 16) = 856 / 151 = 5
        assertEquals(5, cols1080p)

        val colsSmall = tvGridColumnCount(
            screenWidthDp = 200,
            minCellWidthDp = 300
        )
        assertEquals(1, colsSmall)
    }

    @Test
    fun tvMatrixNeighbors_firstRow() {
        val n = tvMatrixNeighbors(index = 1, size = 10, cols = 4)
        assertTrue(n.isFirstRow)
        assertFalse(n.isLastRow)
        assertFalse(n.isFirstCol)
        assertFalse(n.isLastCol)
        assertNull(n.upIndex)
        assertEquals(5, n.downIndex)
        assertEquals(0, n.leftIndex)
        assertEquals(2, n.rightIndex)
    }

    @Test
    fun tvMatrixNeighbors_lastRow() {
        // size = 10, cols = 4 -> rows: 0..3, 4..7, 8..9. Last row start is 8.
        val n = tvMatrixNeighbors(index = 8, size = 10, cols = 4)
        assertFalse(n.isFirstRow)
        assertTrue(n.isLastRow)
        assertTrue(n.isFirstCol)
        assertFalse(n.isLastCol)
        assertEquals(4, n.upIndex)
        assertNull(n.downIndex)
        assertNull(n.leftIndex)
        assertEquals(9, n.rightIndex)
    }

    @Test
    fun tvMatrixNeighbors_firstCol() {
        val n = tvMatrixNeighbors(index = 4, size = 12, cols = 4)
        assertFalse(n.isFirstRow)
        assertFalse(n.isLastRow)
        assertTrue(n.isFirstCol)
        assertFalse(n.isLastCol)
        assertNull(n.leftIndex)
        assertEquals(5, n.rightIndex)
        assertEquals(0, n.upIndex)
        assertEquals(8, n.downIndex)
    }

    @Test
    fun tvMatrixNeighbors_lastCol() {
        val n = tvMatrixNeighbors(index = 7, size = 12, cols = 4)
        assertFalse(n.isFirstRow)
        assertFalse(n.isLastRow)
        assertFalse(n.isFirstCol)
        assertTrue(n.isLastCol)
        assertEquals(6, n.leftIndex)
        assertNull(n.rightIndex)
        assertEquals(3, n.upIndex)
        assertEquals(11, n.downIndex)
    }

    @Test
    fun tvMatrixNeighbors_interiorCell() {
        val n = tvMatrixNeighbors(index = 5, size = 12, cols = 4)
        assertFalse(n.isFirstRow)
        assertFalse(n.isLastRow)
        assertFalse(n.isFirstCol)
        assertFalse(n.isLastCol)
        assertEquals(4, n.leftIndex)
        assertEquals(6, n.rightIndex)
        assertEquals(1, n.upIndex)
        assertEquals(9, n.downIndex)
    }

    @Test
    fun tvMatrixNeighbors_partialLastRow_clampsDownIndexToLastItem() {
        // size = 7, cols = 4 -> row 0: [0, 1, 2, 3], row 1: [4, 5, 6]
        // Index 3 (col 3) has row+1 index 7, which exceeds size-1 (6), so it clamps to 6.
        val n3 = tvMatrixNeighbors(index = 3, size = 7, cols = 4)
        assertFalse(n3.isLastRow)
        assertEquals(6, n3.downIndex)

        // Index 2 (col 2) in size 6, cols 4: row+1 is 6, clamped to size-1 (5).
        val n2 = tvMatrixNeighbors(index = 2, size = 6, cols = 4)
        assertFalse(n2.isLastRow)
        assertEquals(5, n2.downIndex)
    }

    @Test
    fun toFullQualityPosterUrl_upgradesW342ToW780() {
        val tmdbUrl = "https://image.tmdb.org/t/p/w342/test_poster.jpg"
        assertEquals("https://image.tmdb.org/t/p/w780/test_poster.jpg", tmdbUrl.toFullQualityPosterUrl())
    }

    @Test
    fun toFullQualityPosterUrl_preservesNonW342AndNonTmdbUrls() {
        assertEquals("https://example.com/poster.jpg", "https://example.com/poster.jpg".toFullQualityPosterUrl())
        assertEquals("", "".toFullQualityPosterUrl())
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg", "https://image.tmdb.org/t/p/original/poster.jpg".toFullQualityPosterUrl())
    }

    @Test
    fun toFullQualityBackdropUrl_upgradesW1280ToOriginal() {
        val tmdbUrl = "https://image.tmdb.org/t/p/w1280/test_backdrop.jpg"
        assertEquals("https://image.tmdb.org/t/p/original/test_backdrop.jpg", tmdbUrl.toFullQualityBackdropUrl())
    }

    @Test
    fun toFullQualityBackdropUrl_upgradesW500AndW342ToW780() {
        val w500 = "https://image.tmdb.org/t/p/w500/test_still.jpg"
        assertEquals("https://image.tmdb.org/t/p/w780/test_still.jpg", w500.toFullQualityBackdropUrl())

        val w342 = "https://image.tmdb.org/t/p/w342/test_still.jpg"
        assertEquals("https://image.tmdb.org/t/p/w780/test_still.jpg", w342.toFullQualityBackdropUrl())
    }

    @Test
    fun toFullQualityBackdropUrl_preservesNonTmdbAndBlankUrls() {
        assertEquals("https://example.com/art.jpg", "https://example.com/art.jpg".toFullQualityBackdropUrl())
        assertEquals("", "".toFullQualityBackdropUrl())
    }
}
