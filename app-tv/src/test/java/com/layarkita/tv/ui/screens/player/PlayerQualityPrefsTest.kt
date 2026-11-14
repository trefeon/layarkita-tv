package com.layarkita.tv.ui.screens.player

import android.content.SharedPreferences
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for player preference mapping ([PlayerQualityPrefs.kt]).
 *
 * The enums are pure Kotlin and fully exercised here.
 * Persistence ([load/save]*) runs against an
 * in-memory [FakePrefs], so the full round-trip works on plain JVM with no
 * framework. Out of scope (needs a live player): [applyPlaybackQuality] and
 * [applyPlaybackSpeed], which mutate [androidx.media3.common.Player] state.
 */
@OptIn(UnstableApi::class)
class PlayerQualityPrefsTest {

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            map[key!!] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            map[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            map.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }

    private class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map.toMap()

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            map[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            map[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            map[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(map)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private fun prefsWith(): FakePrefs = FakePrefs()

    @Test
    fun qualityPrefsValuesRoundTrip() {
        for (quality in TvPlaybackQuality.entries) {
            assertEquals(quality, TvPlaybackQuality.fromPrefsValue(quality.prefsValue))
        }
    }

    @Test
    fun qualityUnknownOrNullFallsBackToAuto() {
        assertEquals(TvPlaybackQuality.AUTO, TvPlaybackQuality.fromPrefsValue(null))
        assertEquals(TvPlaybackQuality.AUTO, TvPlaybackQuality.fromPrefsValue(""))
        assertEquals(TvPlaybackQuality.AUTO, TvPlaybackQuality.fromPrefsValue("4k_uhd"))
    }

    @Test
    fun qualityCapsMatchTvBias() {
        // TV keeps headroom on steady Wi-Fi: 1080p uncapped, lower tiers higher
        // than mobile's metered-cell caps.
        assertEquals(1920, TvPlaybackQuality.P1080.maxWidth)
        assertEquals(1080, TvPlaybackQuality.P1080.maxHeight)
        assertNull(TvPlaybackQuality.P1080.maxBitrate)
        assertEquals(1280, TvPlaybackQuality.P720.maxWidth)
        assertEquals(3_000_000, TvPlaybackQuality.P720.maxBitrate)
        assertEquals(854, TvPlaybackQuality.P480.maxWidth)
        assertEquals(1_200_000, TvPlaybackQuality.P480.maxBitrate)
        assertNull(TvPlaybackQuality.AUTO.maxBitrate)
    }

    @Test
    fun rowsForHeightsEmptyReturnsFullList() {
        assertEquals(TvPlaybackQuality.entries.toList(), TvPlaybackQuality.rowsForHeights(emptySet()))
    }

    @Test
    fun rowsForHeightsFiltersByLadder() {
        assertEquals(
            listOf(TvPlaybackQuality.AUTO, TvPlaybackQuality.P1080),
            TvPlaybackQuality.rowsForHeights(setOf(1080))
        )
        assertEquals(
            listOf(TvPlaybackQuality.AUTO, TvPlaybackQuality.P720),
            TvPlaybackQuality.rowsForHeights(setOf(720))
        )
        assertEquals(
            listOf(TvPlaybackQuality.AUTO, TvPlaybackQuality.P480),
            TvPlaybackQuality.rowsForHeights(setOf(480))
        )
        assertEquals(
            TvPlaybackQuality.entries.toList(),
            TvPlaybackQuality.rowsForHeights(setOf(1080, 720, 480))
        )
    }

    @Test
    fun rowsForHeightsDegenerateFallsBackToFullList() {
        // Heights that match no bucket (e.g. unknown/zero) show everything
        // rather than an Auto-only modal.
        assertEquals(TvPlaybackQuality.entries.toList(), TvPlaybackQuality.rowsForHeights(setOf(0)))
    }

    @Test
    fun speedPrefsValuesRoundTrip() {
        for (speed in TvPlaybackSpeed.entries) {
            assertEquals(speed, TvPlaybackSpeed.fromPrefsValue(speed.prefsValue))
        }
    }

    @Test
    fun speedUnknownOrNullFallsBackTo1x() {
        assertEquals(TvPlaybackSpeed.P100, TvPlaybackSpeed.fromPrefsValue(null))
        assertEquals(TvPlaybackSpeed.P100, TvPlaybackSpeed.fromPrefsValue(""))
        assertEquals(TvPlaybackSpeed.P100, TvPlaybackSpeed.fromPrefsValue("3x"))
    }

    @Test
    fun speedFactorsAscendFromHalfToDouble() {
        val factors = TvPlaybackSpeed.entries.map { it.factor }
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), factors)
    }

    @Test
    fun qualityPersistsRoundTrip() {
        val prefs = prefsWith()
        assertEquals(TvPlaybackQuality.AUTO, loadPlaybackQuality(prefs))
        savePlaybackQuality(prefs, TvPlaybackQuality.P720)
        assertEquals(TvPlaybackQuality.P720, loadPlaybackQuality(prefs))
        savePlaybackQuality(prefs, TvPlaybackQuality.P480)
        assertEquals(TvPlaybackQuality.P480, loadPlaybackQuality(prefs))
    }

    @Test
    fun speedPersistsRoundTrip() {
        val prefs = prefsWith()
        assertEquals(TvPlaybackSpeed.P100, loadPlaybackSpeed(prefs))
        savePlaybackSpeed(prefs, TvPlaybackSpeed.P150)
        assertEquals(TvPlaybackSpeed.P150, loadPlaybackSpeed(prefs))
    }

    @Test
    fun audioLanguageDefaultsNullAndRemovalClears() {
        val prefs = prefsWith()
        assertNull(loadAudioLanguage(prefs))
        saveAudioLanguage(prefs, "id")
        assertEquals("id", loadAudioLanguage(prefs))
        saveAudioLanguage(prefs, null)
        assertNull(loadAudioLanguage(prefs))
    }

    @Test
    fun stabilizerDefaultsOnAndPersists() {
        val prefs = prefsWith()
        assertTrue(loadAudioStabilizer(prefs))
        saveAudioStabilizer(prefs, false)
        assertFalse(loadAudioStabilizer(prefs))
        saveAudioStabilizer(prefs, true)
        assertTrue(loadAudioStabilizer(prefs))
    }
}
