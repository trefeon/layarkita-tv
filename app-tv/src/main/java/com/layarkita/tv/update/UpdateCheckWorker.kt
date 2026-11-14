package com.layarkita.tv.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.layarkita.tv.BuildConfig
import com.layarkita.tv.R
import java.util.concurrent.TimeUnit

/**
 * Daily background update check. Notifies ONLY when a newer release is
 * [UpdateState.Available]; every other state (up to date, offline, failed)
 * stays silent so the TV never nags.
 *
 * Requires `androidx.work:work-runtime-ktx`. Schedule once from app startup
 * via [schedule].
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val state = UpdateChecker.checkForUpdate(applicationContext, BuildConfig.VERSION_CODE.toLong())
        if (state is UpdateState.Available) {
            PendingUpdateStore.save(applicationContext, state)
            showAvailableNotification(state)
        }
        return Result.success()
    }

    private fun showAvailableNotification(state: UpdateState.Available) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.update_channel_desc)
                }
            )
        }
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let { intent ->
                // Notification handoff: the flag routes MainActivity to
                // Settings; the payload mirrors PendingUpdateStore so the
                // Available row surfaces even if the store was cleared.
                // Distinct from UpdateInstaller.EXTRA_PENDING_UPDATE (install
                // step uri) — this flag carries a check payload, not a file.
                intent.putExtra(EXTRA_PENDING_UPDATE, true)
                intent.putExtra(EXTRA_UPDATE_VERSION, state.version)
                intent.putExtra(EXTRA_UPDATE_NOTES, state.notes.take(1000))
                intent.putExtra(EXTRA_UPDATE_ASSET_URL, state.assetUrl)
                intent.putExtra(EXTRA_UPDATE_SIZE_BYTES, state.sizeBytes)
                android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            }
        // Content shows the release summary, not the generic channel blurb;
        // the full notes ride along as BigText.
        val summary = state.notes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" ")
            .take(200)
            .ifBlank { context.getString(R.string.update_channel_desc) }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.getString(R.string.update_available_title, state.version)
            )
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .apply { if (launch != null) setContentIntent(launch) }
        if (state.notes.isNotBlank()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(state.notes.take(1000))
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        const val WORK_NAME = "layarkita_update_check"
        private const val CHANNEL_ID = "layarkita_updates"
        private const val NOTIFICATION_ID = 9401

        /**
         * Notification content-intent handoff: boolean flag routing
         * MainActivity.onNewIntent to Settings, plus the Available payload.
         * The same payload is persisted in [PendingUpdateStore] (consumed by
         * SettingsViewModel on init); the extras are a mirror, not the source.
         */
        const val EXTRA_PENDING_UPDATE = "com.layarkita.tv.extra.PENDING_UPDATE"
        const val EXTRA_UPDATE_VERSION = "com.layarkita.tv.extra.UPDATE_VERSION"
        const val EXTRA_UPDATE_NOTES = "com.layarkita.tv.extra.UPDATE_NOTES"
        const val EXTRA_UPDATE_ASSET_URL = "com.layarkita.tv.extra.UPDATE_ASSET_URL"
        const val EXTRA_UPDATE_SIZE_BYTES = "com.layarkita.tv.extra.UPDATE_SIZE_BYTES"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
