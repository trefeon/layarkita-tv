package com.layarkita.data.local

import android.content.SharedPreferences
import com.layarkita.data.model.WatchHistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the real [WatchHistoryStore] logic: record/start resume,
 * progress, the 95% restart rule, unfinished filtering, remove/restore,
 * clear-all, and the 60-item cap.
 *
 * No Robolectric/MockK in the catalog, and no prod edits allowed for
 * non-Blockers, so the store is exercised directly: [SharedPreferences] is a
 * plain interface (faked in memory below) and the instance is allocated
 * without invoking the Android `Context` constructor. Every behavior under
 * test runs real store code; only the prefs backend is fake.
 */
class WatchHistoryStoreJvmTest {

    private class FakeEditor(
        private val map: MutableMap<String, Any?>,
        private val listeners: MutableSet<SharedPreferences.OnSharedPreferenceChangeListener>
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals += key }

        override fun clear(): SharedPreferences.Editor =
            apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
            clearAll = false
            removals.clear()
            pending.clear()
        }
    }

    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun contains(key: String) = map.containsKey(key)
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)

        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun edit(): SharedPreferences.Editor = FakeEditor(map, listeners)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners -= listener
        }
    }

    private fun newStore(prefs: FakePrefs = FakePrefs()): WatchHistoryStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass
            .getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null)
        val store = unsafeClass
            .getDeclaredMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, WatchHistoryStore::class.java) as WatchHistoryStore
        fun setField(name: String, value: Any?) {
            WatchHistoryStore::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(store, value)
        }
        setField("prefs", prefs)
        // Mirrors the prod Json config so list round-trips behave identically.
        setField("json", Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
        setField("memoryHistory", null)
        val flow = MutableStateFlow(emptyList<WatchHistoryItem>())
        setField("_historyFlow", flow)
        // Prod exposes historyFlow = _historyFlow.asStateFlow(); allocateInstance
        // skips the ctor so that public val stays null (XML: getHistoryFlow() null
        // at 131/200/235/264). Mirror the ctor wiring onto the same backing flow.
        setField("historyFlow", flow)
        return store
    }

    // ---------------------------------------------------------- record/start

    @Test
    fun recordStartRegistersMetadataAtHead() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori", posterUrl = "/p.jpg")
        assertEquals(1, store.historyFlow.value.size)
        store.recordStartPlayback("m2", "movie", "Other")
        val all = store.getAllHistory()
        assertEquals(2, all.size)
        assertEquals("m2", all[0].contentId)
        assertEquals("Fuori", all[1].title)
        assertEquals("/p.jpg", all[1].posterUrl)
        assertEquals(2, store.historyFlow.value.size)
        // Flow emits the latest list too (size 2 after the second record).
        assertEquals(2, store.getAllHistory().size)
    }

    @Test
    fun recordStartPreservesExistingPosition() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.updateProgress("m1", 5_000L, 60_000L)
        store.recordStartPlayback("m1", "movie", "Fuori")
        assertEquals(5_000L, store.getAllHistory().first().positionMs)
        assertEquals(5_000L, store.getSavedPosition("m1"))
    }

    @Test
    fun prefsOnlyProgressIsAdoptedOnStart() {
        val store = newStore()
        // Progress for an id with no list entry (e.g. list pruned by the cap).
        store.updateProgress("m9", 12_000L, 60_000L)
        assertTrue(store.getAllHistory().isEmpty())
        store.recordStartPlayback("m9", "movie", "Late")
        assertEquals(12_000L, store.getAllHistory().first().positionMs)
        assertEquals(12_000L, store.getSavedPosition("m9"))
    }

    // ---------------------------------------------------------- resume rule

    @Test
    fun savedPositionRestartsNearEnd() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.updateProgress("m1", 59_000L, 60_000L)
        assertEquals(0L, store.getSavedPosition("m1"))
    }

    @Test
    fun savedPositionBoundaryIsStrictlyGreater() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.updateProgress("m1", 57_000L, 60_000L) // exactly 95%: resume
        assertEquals(57_000L, store.getSavedPosition("m1"))
        store.updateProgress("m1", 57_001L, 60_000L) // just past: restart
        assertEquals(0L, store.getSavedPosition("m1"))
    }

    @Test
    fun unfinishedFilterSkipsUnstartedAndFinished() {
        val store = newStore()
        store.recordStartPlayback("short", "movie", "Short")
        store.updateProgress("short", 5_000L, 60_000L)
        store.recordStartPlayback("mid", "movie", "Mid")
        store.updateProgress("mid", 20_000L, 60_000L)
        store.recordStartPlayback("done", "movie", "Done")
        store.updateProgress("done", 59_000L, 60_000L)
        val unfinished = store.getUnfinishedItems().map { it.contentId }
        assertEquals(listOf("mid"), unfinished)
    }

    // ---------------------------------------------------------- remove/restore/clear

    @Test
    fun removeItemClearsListAndKeys() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.updateProgress("m1", 20_000L, 60_000L)
        store.removeItem("m1")
        assertTrue(store.getAllHistory().isEmpty())
        assertEquals(0L, store.getSavedPosition("m1"))
        assertTrue(store.historyFlow.value.isEmpty())
    }

    @Test
    fun clearPositionRemovesEntry() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.clearPosition("m1")
        assertTrue(store.getAllHistory().isEmpty())
        assertEquals(0L, store.getSavedPosition("m1"))
    }

    @Test
    fun restoreItemReinsertsAtHeadWithKeys() {
        val store = newStore()
        store.recordStartPlayback("a", "movie", "A")
        store.updateProgress("a", 20_000L, 60_000L)
        store.recordStartPlayback("b", "movie", "B")
        val snapshot = store.getAllHistory().first { it.contentId == "a" }
        store.removeItem("a")
        store.restoreItem(snapshot)
        val all = store.getAllHistory()
        assertEquals("a", all.first().contentId)
        assertEquals(20_000L, store.getSavedPosition("a"))
    }

    @Test
    fun clearAllWipesListPrefsAndFlow() {
        val prefs = FakePrefs()
        val store = newStore(prefs)
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.updateProgress("m1", 20_000L, 60_000L)
        store.clearAll()
        assertTrue(store.getAllHistory().isEmpty())
        assertTrue(store.getUnfinishedItems().isEmpty())
        assertTrue(store.historyFlow.value.isEmpty())
        assertEquals(0L, store.getSavedPosition("m1"))
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun historyIsCappedAtSixtyNewestFirst() {
        val store = newStore()
        repeat(61) { i -> store.recordStartPlayback("m$i", "movie", "T$i") }
        val all = store.getAllHistory()
        assertEquals(60, all.size)
        assertEquals("m60", all.first().contentId)
        assertTrue(all.none { it.contentId == "m0" })
    }

    @Test
    fun savePositionAliasWritesProgress() {
        val store = newStore()
        store.recordStartPlayback("m1", "movie", "Fuori")
        store.savePosition("m1", 11_000L, 60_000L)
        assertEquals(11_000L, store.getSavedPosition("m1"))
    }

    @Test
    fun corruptPrefsQuarantinesToEmptyWithoutThrow() {
        val prefs = FakePrefs()
        prefs.map["history_items_json"] = "{not json"
        val store = newStore(prefs)
        assertTrue(store.getAllHistory().isEmpty())
        assertTrue(store.historyFlow.value.isEmpty())
    }
}
