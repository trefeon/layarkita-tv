package com.layarkita.tv.update

import com.layarkita.tv.ui.screens.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * State-machine + handoff contract tests for the update engine
 * (UpdateState.kt, SettingsViewModel.surfaceAvailableUpdate,
 * PendingUpdateStore / UpdateCheckWorker / UpdateInstaller extras).
 *
 * All offline, plain JVM: UpdateState is pure Kotlin; the ViewModel guard is
 * exercised on an Unsafe-allocated instance with only `_updateState`
 * injected (same seam as SettingsGateTest — surfaceAvailableUpdate touches
 * no Android APIs); extra keys are compile-time constants.
 */
class UpdateStateMachineTest {

    // ------------------------------------------------------------ state shape

    @Test
    fun availableCarriesCheckPayload() {
        val s = UpdateState.Available(
            version = "1.0.4",
            notes = "",
            assetUrl = "https://github.com/trefeon/layarkita-tv/releases/download/v1.0.4/layarkita-tv-v1.0.4.apk",
            sizeBytes = 42L
        )
        assertEquals("1.0.4", s.version)
        assertEquals(42L, s.sizeBytes)
        assertTrue(s.assetUrl.endsWith(".apk"))
    }

    @Test
    fun downloadingProgressIsUnitRange() {
        // Contract: UpdateDownloader emits coerceIn(0f, 1f); the state just
        // carries the float — boundaries must be representable.
        assertEquals(0f, UpdateState.Downloading(0f).progress)
        assertEquals(1f, UpdateState.Downloading(1f).progress)
    }

    @Test
    fun downloadedCarriesFileProviderUriString() {
        val s = UpdateState.Downloaded("content://com.layarkita.tv.updateprovider/updates/layarkita-tv-v1.0.4.apk")
        assertTrue(s.fileUri.startsWith("content://"))
    }

    @Test
    fun failedCarriesShortReason() {
        assertEquals("offline", UpdateState.Failed("offline").reason)
        assertEquals("1.0.3", UpdateState.UpToDate("1.0.3").currentVersion)
    }

    // ------------------------------------------------- surfaceAvailableUpdate

    private fun newSurfaceOnlyViewModel(initial: UpdateState): SettingsViewModel {
        try {
            val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            val vm = allocate.invoke(unsafe, SettingsViewModel::class.java) as SettingsViewModel
            val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
            val field = vm.javaClass.getDeclaredField("_updateState")
            field.isAccessible = true
            field.set(vm, state)
            return vm
        } catch (e: Throwable) {
            Assume.assumeTrue("Seam: Unsafe allocation unavailable: ${e.message}", false)
            throw AssertionError("unreachable")
        }
    }

    private fun current(vm: SettingsViewModel): UpdateState {
        val field = vm.javaClass.getDeclaredField("_updateState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UpdateState>
        return flow.value
    }

    private fun pending() = arrayOf("1.0.4", "", "https://example.invalid/layarkita-tv-v1.0.4.apk", 9L)

    @Test
    fun idleSurfacesPendingAvailable() {
        val vm = newSurfaceOnlyViewModel(UpdateState.Idle)
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        val cur = current(vm)
        assertTrue(cur is UpdateState.Available)
        assertEquals("1.0.4", (cur as UpdateState.Available).version)
    }

    @Test
    fun failedSurfacesPendingAvailable() {
        val vm = newSurfaceOnlyViewModel(UpdateState.Failed("offline"))
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        assertTrue(current(vm) is UpdateState.Available)
    }

    @Test
    fun upToDateSurfacesPendingAvailable() {
        val vm = newSurfaceOnlyViewModel(UpdateState.UpToDate("1.0.3"))
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        assertTrue(current(vm) is UpdateState.Available)
    }

    @Test
    fun checkingIsNeverClobbered() {
        val vm = newSurfaceOnlyViewModel(UpdateState.Checking)
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        assertTrue(current(vm) is UpdateState.Checking)
    }

    @Test
    fun downloadingIsNeverClobbered() {
        val vm = newSurfaceOnlyViewModel(UpdateState.Downloading(0.5f))
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        val cur = current(vm)
        assertTrue(cur is UpdateState.Downloading)
        assertEquals(0.5f, (cur as UpdateState.Downloading).progress)
    }

    @Test
    fun downloadedIsNeverClobbered() {
        val vm = newSurfaceOnlyViewModel(UpdateState.Downloaded("content://x/y.apk"))
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        val cur = current(vm)
        assertTrue(cur is UpdateState.Downloaded)
        assertEquals("content://x/y.apk", (cur as UpdateState.Downloaded).fileUri)
    }

    @Test
    fun staleAvailableIsReplaced() {
        val vm = newSurfaceOnlyViewModel(
            UpdateState.Available("1.0.3", "", "https://example.invalid/old.apk", 1L)
        )
        val p = pending()
        vm.surfaceAvailableUpdate(p[0] as String, p[1] as String, p[2] as String, p[3] as Long)
        val cur = current(vm)
        assertTrue(cur is UpdateState.Available)
        assertEquals("1.0.4", (cur as UpdateState.Available).version)
    }

    // ------------------------------------------------------- handoff extras

    @Test
    fun workerAndInstallerPendingKeysAreDistinct() {
        // Same simple name, different values by design: the worker flag
        // carries a check payload (store-backed), the installer extra a file
        // uri. Unifying them would route installs through the check path.
        assertEquals("com.layarkita.tv.extra.PENDING_UPDATE", UpdateCheckWorker.EXTRA_PENDING_UPDATE)
        assertEquals("com.layarkita.tv.EXTRA_PENDING_UPDATE", UpdateInstaller.EXTRA_PENDING_UPDATE)
        assertNotEquals(UpdateCheckWorker.EXTRA_PENDING_UPDATE, UpdateInstaller.EXTRA_PENDING_UPDATE)
    }

    @Test
    fun workerPayloadKeysAreNamespaced() {
        assertEquals("com.layarkita.tv.extra.UPDATE_VERSION", UpdateCheckWorker.EXTRA_UPDATE_VERSION)
        assertEquals("com.layarkita.tv.extra.UPDATE_NOTES", UpdateCheckWorker.EXTRA_UPDATE_NOTES)
        assertEquals("com.layarkita.tv.extra.UPDATE_ASSET_URL", UpdateCheckWorker.EXTRA_UPDATE_ASSET_URL)
        assertEquals("com.layarkita.tv.extra.UPDATE_SIZE_BYTES", UpdateCheckWorker.EXTRA_UPDATE_SIZE_BYTES)
    }

    @Test
    fun mainActivityExposesIntentRoutingShape() {
        val activity = try {
            Class.forName("com.layarkita.tv.MainActivity")
        } catch (_: Throwable) {
            null
        }
        Assume.assumeTrue("Seam: MainActivity not on test classpath.", activity != null)
        // singleTop warm dispatch (onNewIntent) + cold drain for the
        // SEARCH / play-from-search / pending-update routing.
        assertTrue(activity!!.methods.any { it.name == "onNewIntent" })
        assertTrue(activity.methods.any { it.name == "drainPendingIntents" })
        assertTrue(activity.declaredMethods.any { it.name == "handleIntent" })
    }
}
