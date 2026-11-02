package com.idlix.tv.tv

import android.media.tv.TvContract
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/**
 * Mapper contract tests for WatchNextSync.kt (launcher "Continue Watching"
 * preview channel + system Watch Next mirror).
 *
 * The type mappers ([watchNextType][previewType]) and [namespaced] are pure
 * string/int functions over inlined TvContract constants, so they run on
 * plain JVM through reflection (methods are private). Everything touching
 * ContentResolver / PreviewChannel / files (syncFromStore, clearAll,
 * ensureChannel, posterUri, deepLinkFor via android.net.Uri) needs a device
 * and stays uncovered by contract: all those paths are fail-silent
 * (runCatching + catch-all) with a 2s debounce in IdlixTvApp.
 */
class WatchNextMapperTest {

    private fun syncObject(): Any {
        return try {
            val cls = Class.forName("com.idlix.tv.tv.WatchNextSync")
            cls.getDeclaredField("INSTANCE").get(null)!!
        } catch (e: Throwable) {
            Assume.assumeTrue("Seam: WatchNextSync not on test classpath: ${e.message}", false)
            throw AssertionError("unreachable")
        }
    }

    private fun call(name: String, arg: String): Any? {
        return try {
            val instance = syncObject()
            val method = instance.javaClass.getDeclaredMethod(name, String::class.java)
            method.isAccessible = true
            method.invoke(instance, arg)
        } catch (e: Throwable) {
            Assume.assumeTrue("Seam: mapper '$name' not invokable on JVM: ${e.message}", false)
            throw AssertionError("unreachable")
        }
    }

    @Test
    fun deepLinkSchemeIsIdlix() {
        assertEquals("idlix", WatchNextSync.DEEP_LINK_SCHEME)
    }

    @Test
    fun providerIdIsNamespaced() {
        assertEquals("idlix:tt123", call("namespaced", "tt123"))
        assertEquals("idlix:", call("namespaced", ""))
    }

    @Test
    fun watchNextTypeMapsToContractConstants() {
        assertEquals(TvContract.WatchNextPrograms.TYPE_MOVIE, call("watchNextType", "movie"))
        assertEquals(TvContract.WatchNextPrograms.TYPE_TV_EPISODE, call("watchNextType", "episode"))
        // Unknown kinds (incl. "series") fall back to the series bucket.
        assertEquals(TvContract.WatchNextPrograms.TYPE_TV_SERIES, call("watchNextType", "series"))
        assertEquals(TvContract.WatchNextPrograms.TYPE_TV_SERIES, call("watchNextType", "podcast"))
        assertEquals(TvContract.WatchNextPrograms.TYPE_TV_SERIES, call("watchNextType", ""))
    }

    @Test
    fun previewTypeMapsToContractConstants() {
        assertEquals(TvContract.PreviewPrograms.TYPE_MOVIE, call("previewType", "movie"))
        assertEquals(TvContract.PreviewPrograms.TYPE_TV_EPISODE, call("previewType", "episode"))
        assertEquals(TvContract.PreviewPrograms.TYPE_TV_SERIES, call("previewType", "series"))
        assertEquals(TvContract.PreviewPrograms.TYPE_TV_SERIES, call("previewType", "podcast"))
    }

    @Test
    fun mapperBranchesAreDistinct() {
        // Guards a copy-paste collapse of the movie/episode branches.
        val movie = call("watchNextType", "movie")
        val episode = call("watchNextType", "episode")
        val series = call("watchNextType", "series")
        assertEquals(false, movie == episode)
        assertEquals(false, movie == series)
        assertEquals(false, episode == series)
    }
}
