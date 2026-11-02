package com.idlix.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary tests for [DirectSourceCache]: exact-expiry semantics, TTL guards,
 * overwrite refresh, and key/type isolation.
 *
 * Complements `IdlixDirectSourceTest` (fresh/stale/type-mismatch/clear) with
 * the edges that pin the TTL contract: lists 15m, details 30m, genres 2h,
 * search 10m — play-info/claim/redeem are never stored here.
 */
class DirectSourceCacheTest {

    @Test
    fun entryExpiresExactlyAtTtl() {
        var now = 1_000_000L
        val cache = DirectSourceCache(clock = { now })
        cache.put("k", "v", 60_000L)
        now += 59_999L
        assertEquals("v", cache.get("k", String::class.java))
        now += 1L // now == put + ttl -> `expiresAtMs <= clock()` evicts
        assertNull(cache.get("k", String::class.java))
        assertEquals(0, cache.size())
    }

    @Test
    fun zeroAndNegativeTtlAreIgnored() {
        val cache = DirectSourceCache(clock = { 0L })
        cache.put("zero", "v", 0L)
        cache.put("negative", "v", -1_000L)
        assertNull(cache.get("zero", String::class.java))
        assertNull(cache.get("negative", String::class.java))
        assertEquals(0, cache.size())
    }

    @Test
    fun overwriteRefreshesValueAndTtl() {
        var now = 0L
        val cache = DirectSourceCache(clock = { now })
        cache.put("k", "v1", 60_000L)
        now += 50_000L
        cache.put("k", "v2", 60_000L)
        now += 50_000L // t=100s: v1 would be dead, v2 still fresh
        assertEquals("v2", cache.get("k", String::class.java))
        now += 10_001L // t=110s+1ms: past v2's refreshed expiry
        assertNull(cache.get("k", String::class.java))
    }

    @Test
    fun typeMismatchDoesNotEvictEntry() {
        val cache = DirectSourceCache(clock = { 0L })
        cache.put("k", "v", 60_000L)
        assertNull(cache.get("k", Int::class.java))
        // Wrong-type read leaves the entry alone for the right type.
        assertEquals("v", cache.get("k", String::class.java))
    }

    @Test
    fun keysExpireIndependently() {
        var now = 0L
        val cache = DirectSourceCache(clock = { now })
        cache.put("short", "s", 10_000L)
        cache.put("long", "l", 60_000L)
        now += 10_001L
        assertNull(cache.get("short", String::class.java))
        assertEquals("l", cache.get("long", String::class.java))
        assertEquals(1, cache.size())
    }

    @Test
    fun expiredUnreadEntriesCountedUntilTouched() {
        var now = 0L
        val cache = DirectSourceCache(clock = { now })
        cache.put("k", "v", 1_000L)
        now += 5_000L
        // No proactive eviction: stale entries linger until read.
        assertEquals(1, cache.size())
        assertNull(cache.get("k", String::class.java))
        assertEquals(0, cache.size())
    }
}
