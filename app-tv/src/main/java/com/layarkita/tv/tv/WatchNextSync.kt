package com.layarkita.tv.tv

import android.content.Context
import android.media.tv.TvContract
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.layarkita.data.local.WatchHistoryStore
import com.layarkita.data.model.WatchHistoryItem
import com.layarkita.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Leanback launcher integration: mirrors unfinished watch history into the
 * launcher "Continue Watching" preview channel and the system Watch Next
 * list. Everything is fail-silent — launcher provider absence, missing
 * permissions, or dead ContentResolver paths never surface to the UI.
 *
 * Deep links use the `layarkita://detail/<contentType>/<slug>` scheme (handled
 * by the MainActivity VIEW/BROWSABLE filter + nav deepLinks in AppNavigation).
 */
object WatchNextSync {

    const val DEEP_LINK_SCHEME = "layarkita"

    private const val PREFS = "layarkita_tv_watchnext"
    private const val KEY_CHANNEL_ID = "preview_channel_id"
    private const val KEY_PUBLISHED_IDS = "watchnext_published_ids"
    private const val CHANNEL_PROVIDER_ID = "layarkita-continue-watching"
    private const val MAX_PROGRAMS = 20

    /** Deep link for a history item; falls back to contentId when slugless. */
    fun deepLinkFor(contentType: String, slugOrId: String): Uri =
        Uri.parse("$DEEP_LINK_SCHEME://detail/$contentType/$slugOrId")

    fun deepLinkFor(item: WatchHistoryItem): Uri =
        deepLinkFor(item.contentType, if (item.slug.isNullOrBlank()) item.contentId else item.slug!!)

    /**
     * Rebuilds launcher rows from [WatchHistoryStore] unfinished items (most
     * recent first, capped). Safe to call on every history change; runs on IO.
     */
    suspend fun syncFromStore(context: Context) = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val items = WatchHistoryStore.getInstance(app).getUnfinishedItems()
                .sortedByDescending { it.lastWatchedTime }
                .take(MAX_PROGRAMS)
            val channelId = ensureChannel(app)
            if (channelId != null) {
                publishPreviewRow(app, channelId, items)
            }
            publishWatchNext(app, items)
        } catch (_: Throwable) {
            // Fail-silent: launcher sync never breaks playback or settings.
        }
    }

    /** Removes every launcher row this app published. Runs on IO. */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val resolver = app.contentResolver
            channelId(app)?.let { id ->
                runCatching {
                    resolver.delete(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        "${TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID}=?",
                        arrayOf(id.toString())
                    )
                }
            }
            publishedIds(app).forEach { pid ->
                runCatching {
                    resolver.delete(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        "${TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}=?",
                        arrayOf(pid)
                    )
                }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PUBLISHED_IDS).apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ channel

    private fun channelId(app: Context): Long? {
        val id = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CHANNEL_ID, -1L)
        return if (id > 0L) id else null
    }

    private fun publishedIds(app: Context): Set<String> {
        return try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_PUBLISHED_IDS, emptySet())
                .orEmpty().toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun ensureChannel(app: Context): Long? {
        val resolver = app.contentResolver
        try {
            resolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID),
                "${TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID}=?",
                arrayOf(CHANNEL_PROVIDER_ID),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    if (id > 0L) return id
                }
            }
        } catch (_: Throwable) {
            return channelId(app)
        }
        return try {
            val name = app.getString(R.string.continue_watching)
            val values = PreviewChannel.Builder()
                .setDisplayName(name)
                .setInternalProviderId(CHANNEL_PROVIDER_ID)
                .build()
                .toContentValues()
                .apply {
                    put(TvContractCompat.Channels.COLUMN_BROWSABLE, 1)
                }
            val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull()
            if (id != null && id > 0L) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_CHANNEL_ID, id).apply()
                id
            } else {
                channelId(app)
            }
        } catch (_: Throwable) {
            channelId(app)
        }
    }

    // ------------------------------------------------------------ preview

    private fun publishPreviewRow(app: Context, channelId: Long, items: List<WatchHistoryItem>) {
        val resolver = app.contentResolver
        runCatching {
            resolver.delete(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                "${TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID}=?",
                arrayOf(channelId.toString())
            )
        }
        items.forEach { item ->
            try {
                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(previewProgramType(item.contentType))
                    .setTitle(item.title)
                    .setInternalProviderId(namespaced(item.contentId))
                    .setIntentUri(deepLinkFor(item))
                posterUri(item)?.let { uri ->
                    builder.setPosterArtUri(uri)
                    builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                }
                runCatching {
                    resolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        builder.build().toContentValues()
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------- watch next

    private fun publishWatchNext(app: Context, items: List<WatchHistoryItem>) {
        val resolver = app.contentResolver
        val current = items.map { namespaced(it.contentId) }.toSet()
        val previous = publishedIds(app)
        (previous - current).forEach { stale ->
            runCatching {
                resolver.delete(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    "${TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}=?",
                    arrayOf(stale)
                )
            }
        }
        items.forEach { item ->
            try {
                val builder = WatchNextProgram.Builder()
                    .setType(watchNextProgramType(item.contentType))
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(item.lastWatchedTime)
                    .setTitle(item.title)
                    .setInternalProviderId(namespaced(item.contentId))
                    .setContentId(item.contentId)
                    .setIntentUri(deepLinkFor(item))
                posterUri(item)?.let { uri ->
                    builder.setPosterArtUri(uri)
                    builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                }
                if (item.durationMs > 0L) {
                    builder.setDurationMillis(item.durationMs.toInt())
                    builder.setLastPlaybackPositionMillis(item.positionMs.toInt())
                }
                val values = builder.build().toContentValues()
                val selection = "${TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}=?"
                val args = arrayOf(namespaced(item.contentId))
                val updated = runCatching {
                    resolver.update(TvContractCompat.WatchNextPrograms.CONTENT_URI, values, selection, args)
                }.getOrDefault(0)
                if (updated <= 0) {
                    runCatching {
                        resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_PUBLISHED_IDS, current).apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- helpers

    private fun namespaced(contentId: String): String = "layarkita:\$contentId"

    private fun watchNextType(contentType: String): Int = when (contentType) {
        "movie" -> TvContract.WatchNextPrograms.TYPE_MOVIE
        "episode" -> TvContract.WatchNextPrograms.TYPE_TV_EPISODE
        else -> TvContract.WatchNextPrograms.TYPE_TV_SERIES
    }

    private fun previewType(contentType: String): Int = when (contentType) {
        "movie" -> TvContract.PreviewPrograms.TYPE_MOVIE
        "episode" -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
        else -> TvContract.PreviewPrograms.TYPE_TV_SERIES
    }

    /**
     * tvprovider int program type for [PreviewProgram.Builder.setType].
     * Same buckets as [previewType]; ints are what the support builder takes.
     */
    private fun previewProgramType(contentType: String): Int = when (contentType) {
        "movie" -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        "episode" -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        else -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
    }

    /**
     * tvprovider int program type for [WatchNextProgram.Builder.setType].
     * Same buckets as [watchNextType]; ints are what the support builder takes.
     */
    private fun watchNextProgramType(contentType: String): Int = when (contentType) {
        "movie" -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        "episode" -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        else -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
    }

    private fun posterUri(item: WatchHistoryItem): Uri? {
        val raw = when {
            !item.backdropUrl.isNullOrBlank() -> item.backdropUrl
            !item.posterUrl.isNullOrBlank() -> item.posterUrl
            else -> return null
        }
        return try {
            val uri = Uri.parse(raw)
            if (uri.scheme == null) null else uri
        } catch (_: Throwable) {
            null
        }
    }
}
