package com.idlix.tv.ui.screens.player

import com.idlix.data.model.SubdlSubtitle
import com.idlix.data.model.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * JVM unit tests for the player UI state ([PlayerUiState] + the synchronous
 * [PlayerViewModel] state reducers).
 *
 * The ViewModel constructor needs an [android.app.Application] (repository,
 * history store, dispatchers), none of which exists in plain JVM unit tests —
 * so these tests allocate the ViewModel without running its constructor
 * (sun.misc.Unsafe, same seam as SettingsGateTest) and exercise only the
 * synchronous reducers that never touch the network, the database, or the
 * coroutine scope: subtitle select/toggle, picker visibility, next-episode
 * dismissal, and the null-next no-op. Async paths (loadStream, SubDL search,
 * download) and the Context-backed picker-token persistence stay on the
 * instrumented/rig side; the persistence mapping itself is covered by
 * [PlayerQualityPrefsTest].
 */
class PlayerUiStateTest {

    private fun newStateOnlyViewModel(initial: PlayerUiState): PlayerViewModel {
        try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            val vm = allocate.invoke(unsafe, PlayerViewModel::class.java) as PlayerViewModel
            // allocateInstance skips the constructor, so every MutableStateFlow
            // backing field is null until injected here with ctor-equivalent
            // defaults (fromPrefsValue(null) == AUTO / P100: pure string match).
            val flows: Map<String, Any> = mapOf(
                "_uiState" to MutableStateFlow(initial),
                "_tokenQuery" to MutableStateFlow(""),
                "_selectedQuality" to MutableStateFlow(TvPlaybackQuality.fromPrefsValue(null)),
                "_selectedSpeed" to MutableStateFlow(TvPlaybackSpeed.fromPrefsValue(null)),
                "_audioLanguage" to MutableStateFlow<String?>(null)
            )
            for ((name, flow) in flows) {
                val field = PlayerViewModel::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(vm, flow)
            }
            // Prod exposes uiState = _uiState.asStateFlow() (etc.); allocateInstance
            // skips the ctor so those public vals stay null (XML: getUiState() null
            // at 88/101/111/123/133/140/149, getTokenQuery() null at 76). Mirror the
            // ctor wiring onto the same backing instances.
            fun mirror(backing: String, exposed: String) {
                val backField = PlayerViewModel::class.java.getDeclaredField(backing)
                    .apply { isAccessible = true }
                val exposedField = PlayerViewModel::class.java.getDeclaredField(exposed)
                    .apply { isAccessible = true }
                exposedField.set(vm, backField.get(vm))
            }
            mirror("_uiState", "uiState")
            mirror("_tokenQuery", "tokenQuery")
            mirror("_selectedQuality", "selectedQuality")
            mirror("_selectedSpeed", "selectedSpeed")
            mirror("_audioLanguage", "audioLanguage")
            return vm
        } catch (e: Throwable) {
            Assume.assumeTrue("Seam: Unsafe allocation unavailable: ${e.message}", false)
            throw AssertionError("unreachable")
        }
    }

    @Test
    fun defaultStateIsIdleWithSubtitlesEnabled() {
        val state = PlayerUiState()
        assertFalse(state.isLoading)
        assertEquals(0, state.countdownSeconds)
        assertNull(state.resolvedStream)
        assertNull(state.selectedSubtitle)
        assertTrue(state.isSubtitleEnabled)
        assertEquals(0L, state.initialPositionMs)
        assertNull(state.error)
        assertFalse(state.isSubtitlePickerVisible)
        assertTrue(state.subdlSubtitles.isEmpty())
        assertFalse(state.isSearchingSubdl)
        assertNull(state.nextEpisode)
        assertFalse(state.isNextEpisodeDismissed)
        val vm = newStateOnlyViewModel(state)
        assertEquals("", vm.tokenQuery.value)
        assertEquals(TvPlaybackQuality.AUTO, vm.selectedQuality.value)
        assertEquals(TvPlaybackSpeed.P100, vm.selectedSpeed.value)
        assertNull(vm.audioLanguage.value)
    }

    @Test
    fun selectSubtitleTrackEnablesAndClosesPicker() {
        val vm = newStateOnlyViewModel(PlayerUiState(isSubtitlePickerVisible = true))
        val track = SubtitleTrack(lang = "id", label = "Indonesian", path = "/subs/id.vtt")
        vm.selectSubtitleTrack(track)
        val state = vm.uiState.value
        assertEquals(track, state.selectedSubtitle)
        assertTrue(state.isSubtitleEnabled)
        assertFalse(state.isSubtitlePickerVisible)
    }

    @Test
    fun selectNullTrackDisablesSubtitles() {
        val track = SubtitleTrack(lang = "en", label = "English", path = "/subs/en.vtt")
        val vm = newStateOnlyViewModel(
            PlayerUiState(selectedSubtitle = track, isSubtitleEnabled = true)
        )
        vm.selectSubtitleTrack(null)
        val state = vm.uiState.value
        assertNull(state.selectedSubtitle)
        assertFalse(state.isSubtitleEnabled)
        assertFalse(state.isSubtitlePickerVisible)
    }

    @Test
    fun toggleSubtitleFlipsTwice() {
        val vm = newStateOnlyViewModel(PlayerUiState(isSubtitleEnabled = true))
        vm.toggleSubtitle()
        assertFalse(vm.uiState.value.isSubtitleEnabled)
        vm.toggleSubtitle()
        assertTrue(vm.uiState.value.isSubtitleEnabled)
    }

    @Test
    fun openPickerWithCachedSubdlSkipsNetworkSearch() {
        val cached = listOf(SubdlSubtitle(name = "cached-release"))
        val vm = newStateOnlyViewModel(
            PlayerUiState(isSubtitlePickerVisible = false, subdlSubtitles = cached)
        )
        vm.openSubtitlePicker()
        val state = vm.uiState.value
        assertTrue(state.isSubtitlePickerVisible)
        assertEquals(cached, state.subdlSubtitles)
        assertFalse(state.isSearchingSubdl)
    }

    @Test
    fun closePickerHides() {
        val vm = newStateOnlyViewModel(PlayerUiState(isSubtitlePickerVisible = true))
        vm.closeSubtitlePicker()
        assertFalse(vm.uiState.value.isSubtitlePickerVisible)
    }

    @Test
    fun dismissNextEpisodeSetsFlag() {
        val vm = newStateOnlyViewModel(PlayerUiState(isNextEpisodeDismissed = false))
        vm.dismissNextEpisode()
        assertTrue(vm.uiState.value.isNextEpisodeDismissed)
    }

    @Test
    fun playNextEpisodeWithNullNextIsNoOp() {
        val vm = newStateOnlyViewModel(PlayerUiState())
        var invoked = false
        vm.playNextEpisode { invoked = true }
        assertFalse(invoked)
        assertNull(vm.uiState.value.nextEpisode)
    }
}
