package com.layarkita.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.layarkita.tv.startup.ErrorLog
import com.layarkita.tv.ui.input.GamepadDevices
import com.layarkita.tv.ui.navigation.AppNavigation
import com.layarkita.tv.ui.theme.LayarKitaTvTheme
import com.layarkita.tv.update.UpdateCheckWorker

class MainActivity : ComponentActivity() {

    private var inputManager: InputManager? = null

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            reportGamepadChange()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            reportGamepadChange()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            reportGamepadChange()
        }
    }

    /**
     * Warm-intent dispatch targets, registered by AppNavigation once the
     * graph composes. Cold-start intents land before composition, so they
     * are stashed below and drained once via [drainPendingIntents].
     */
    var onSearchQuery: ((String) -> Unit)? = null
    var onPendingUpdate: (() -> Unit)? = null
    var onDeepLink: ((Intent) -> Unit)? = null
    private var pendingSearchQuery: String? = null
    private var pendingUpdateNavigate: Boolean = false
    private var pendingDeepLink: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while TV app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(inputDeviceListener, null)
        // Silent inventory at launch: log only, no toast (avoids noise on start).
        GamepadDevices.findConnectedGamepadName()?.let { name ->
            ErrorLog.appendMessage(this, "gamepad", "Gamepad present at launch: $name")
        }

        // Cold start: stash for AppNavigation to drain after compose.
        handleIntent(intent, dispatch = false)

        setContent {
            LayarKitaTvTheme {
                AppNavigation()
            }
        }
    }

    // singleTop (see manifest): warm layarkita:// / SEARCH intents re-deliver
    // here instead of a new instance. setIntent keeps the cold-start
    // deep-link read (AppNavigation) correct for the next launch; search and
    // update intents dispatch to the composed graph immediately.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, dispatch = true)
    }

    override fun onDestroy() {
        try {
            inputManager?.unregisterInputDeviceListener(inputDeviceListener)
        } catch (_: Exception) {
        } finally {
            inputManager = null
        }
        super.onDestroy()
    }

    /**
     * Consumed once by AppNavigation after the graph composes. Re-reads the
     * latest activity intent (covers setIntent races), then clears the
     * consumed extras so a later drain never refires.
     */
    fun drainPendingIntents(): Pair<String?, Boolean> {
        handleIntent(intent, dispatch = false)
        val query = pendingSearchQuery
        val update = pendingUpdateNavigate
        pendingSearchQuery = null
        pendingUpdateNavigate = false
        intent?.removeExtra(SearchManager.QUERY)
        intent?.removeExtra(MediaStore.EXTRA_MEDIA_TITLE)
        intent?.removeExtra(UpdateCheckWorker.EXTRA_PENDING_UPDATE)
        return query to update
    }

    /**
     * Consumed once by AppNavigation after the graph composes. Warm VIEW
     * intents (layarkita://detail, layarkita://player) that arrived before composition
     * are stashed by [handleIntent]; this returns and clears the latest one.
     */
    fun drainPendingDeepLink(): Intent? {
        handleIntent(intent, dispatch = false)
        val pending = pendingDeepLink
        pendingDeepLink = null
        return pending
    }

    /**
     * Routes the three warm/cold intent kinds (see manifest intent-filters):
     * global SEARCH QUERY, voice play-from-search EXTRA_MEDIA_TITLE (raw
     * QUERY is the fallback), and the worker pending-update flag (payload
     * rides PendingUpdateStore, consumed by SettingsViewModel on init —
     * this flag only selects the settings route). With [dispatch] true and
     * a registered listener the target fires now; otherwise the values are
     * stashed for [drainPendingIntents].
     *
     * The installer-uri path (UpdateInstaller.EXTRA_PENDING_UPDATE) is
     * intentionally NOT handled here: installs route through the Settings
     * Available row, never via a file-uri extra.
     */
    private fun handleIntent(intent: Intent?, dispatch: Boolean) {
        if (intent == null) return
        val searchQuery = when (intent.action) {
            Intent.ACTION_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
                intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                    ?: intent.getStringExtra(SearchManager.QUERY)
            else -> null
        }
        if (!searchQuery.isNullOrBlank()) {
            if (dispatch && onSearchQuery != null) onSearchQuery?.invoke(searchQuery)
            else pendingSearchQuery = searchQuery
        }
        if (intent.getBooleanExtra(UpdateCheckWorker.EXTRA_PENDING_UPDATE, false)) {
            if (dispatch && onPendingUpdate != null) onPendingUpdate?.invoke()
            else pendingUpdateNavigate = true
        }
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            if (dispatch && onDeepLink != null) onDeepLink?.invoke(intent)
            else pendingDeepLink = Intent(intent)
        }
    }

    /**
     * Gamepad connect/disconnect indicator: toast the gamepad name when one is
     * present, always log to ErrorLog. Remote-only users never trigger this
     * (remotes don't report BUTTON_A).
     */
    private fun reportGamepadChange() {
        val name = GamepadDevices.findConnectedGamepadName()
        if (name != null) {
            Toast.makeText(this, "Gamepad connected: $name", Toast.LENGTH_SHORT).show()
            ErrorLog.appendMessage(this, "gamepad", "Gamepad connected: $name")
        } else {
            ErrorLog.appendMessage(this, "gamepad", "Input devices changed; no gamepad present")
        }
    }
}
