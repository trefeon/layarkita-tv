package com.layarkita.tv.update

import android.content.Context

/**
 * Handoff for a background update check that found a newer release: the
 * worker persists the [UpdateState.Available] payload here AND flags the
 * notification content intent with [UpdateCheckWorker.EXTRA_PENDING_UPDATE].
 *
 * Tapping the notification routes to Settings (existing route, no new args);
 * [com.layarkita.tv.ui.screens.settings.SettingsViewModel] consumes the payload
 * on init and surfaces it as [UpdateState.Available], so entry focus parks
 * on the Available action. Consume-once: reading clears the prefs.
 */
object PendingUpdateStore {

    private const val PREFS = "layarkita_tv_pending_update"
    private const val KEY_VERSION = "version"
    private const val KEY_NOTES = "notes"
    private const val KEY_ASSET_URL = "asset_url"
    private const val KEY_SIZE_BYTES = "size_bytes"

    fun save(context: Context, state: UpdateState.Available) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VERSION, state.version)
                .putString(KEY_NOTES, state.notes)
                .putString(KEY_ASSET_URL, state.assetUrl)
                .putLong(KEY_SIZE_BYTES, state.sizeBytes)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun consume(context: Context): UpdateState.Available? {
        return try {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = prefs.getString(KEY_VERSION, null)
            val assetUrl = prefs.getString(KEY_ASSET_URL, null)
            val notes = prefs.getString(KEY_NOTES, null).orEmpty()
            val sizeBytes = prefs.getLong(KEY_SIZE_BYTES, 0L)
            prefs.edit().clear().apply()
            if (version.isNullOrBlank() || assetUrl.isNullOrBlank()) {
                null
            } else {
                UpdateState.Available(
                    version = version,
                    notes = notes,
                    assetUrl = assetUrl,
                    sizeBytes = sizeBytes
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
