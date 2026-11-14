package com.layarkita.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-model tests for [WatchHistoryItem]: unfinished windows, progress
 * clamps, remaining-time, and thumbnail resolution. No Android needed.
 */
class WatchHistoryItemTest {

    @Test
    fun unfinishedRequiresTenSecondsWatched() {
        assertFalse(WatchHistoryItem(contentId = "a", title = "A", positionMs = 9_999L).isUnfinished)
        assertTrue(WatchHistoryItem(contentId = "a", title = "A", positionMs = 10_000L).isUnfinished)
    }

    @Test
    fun unfinishedEndsAtNinetyFivePercent() {
        assertTrue(
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 94_999L, durationMs = 100_000L).isUnfinished
        )
        assertFalse(
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 95_000L, durationMs = 100_000L).isUnfinished
        )
        assertFalse(
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 99_000L, durationMs = 100_000L).isUnfinished
        )
    }

    @Test
    fun unfinishedWithUnknownDurationStaysTrue() {
        assertTrue(WatchHistoryItem(contentId = "a", title = "A", positionMs = 60_000L, durationMs = 0L).isUnfinished)
    }

    @Test
    fun progressFractionClamps() {
        assertEquals(0f, WatchHistoryItem(contentId = "a", title = "A").progressFraction)
        assertEquals(
            0.5f,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 50L, durationMs = 100L).progressFraction
        )
        assertEquals(
            1f,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 150L, durationMs = 100L).progressFraction
        )
        assertEquals(
            0f,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 10L, durationMs = 0L).progressFraction
        )
    }

    @Test
    fun progressPercentIsFloored() {
        assertEquals(
            50,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 50L, durationMs = 100L).progressPercent
        )
    }

    @Test
    fun remainingMinutesFloorAtOneWhileUnfinished() {
        assertEquals(
            1L,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 0L, durationMs = 90_000L).remainingMinutes
        )
        assertEquals(
            0L,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 100L, durationMs = 100L).remainingMinutes
        )
        assertEquals(
            0L,
            WatchHistoryItem(contentId = "a", title = "A", positionMs = 200L, durationMs = 100L).remainingMinutes
        )
    }

    @Test
    fun resolvedThumbnailPrefersBackdropThenPoster() {
        assertEquals(
            "https://img/back.jpg",
            WatchHistoryItem(
                contentId = "a", title = "A",
                posterUrl = "https://img/post.jpg", backdropUrl = "https://img/back.jpg"
            ).resolvedThumbnail
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/back.jpg",
            WatchHistoryItem(contentId = "a", title = "A", backdropUrl = "/back.jpg").resolvedThumbnail
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/post.jpg",
            WatchHistoryItem(contentId = "a", title = "A", posterUrl = "/post.jpg").resolvedThumbnail
        )
        assertEquals("", WatchHistoryItem(contentId = "a", title = "A").resolvedThumbnail)
    }
}
