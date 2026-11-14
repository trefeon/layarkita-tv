package com.layarkita.tv.ui.screens.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.layarkita.data.model.SubtitleTrack
import com.layarkita.tv.ui.screens.player.TvPlaybackQuality
import com.layarkita.tv.ui.screens.player.TvPlaybackSpeed
import com.layarkita.tv.player.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-side owner of the [MediaController] connection to [PlaybackService].
 *
 * The controller is the ONLY playback handle the Compose layer touches for
 * transport (play/pause/seek), track parameters, and state polling — every
 * member used is on the [Player] interface, so the D-pad key table never needs
 * the concrete player. Custom MediaSource assembly still needs the owned
 * [androidx.media3.exoplayer.ExoPlayer], so [prepareStream] and
 * [rebuildSubtitleSource] delegate in-process to the service; the stabilizer
 * bridge ([syncStabilizerEnabled]) flips both DSP stages live.
 *
 * Lifecycle: [connect] once (idempotent) per player screen entry, [release]
 * when it leaves. Releasing the controller does NOT release the service player.
 */
@OptIn(UnstableApi::class)
class PlayerSessionManager(appContext: Context) {

    private val app = appContext.applicationContext

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private var controllerFuture:
        com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    /** Idempotent async connect; the service starts on first bind. */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    _controller.value = future.get()
                } catch (_: Exception) {
                }
            },
            ContextCompat.getMainExecutor(app)
        )
    }

    /** Releases the controller handle; the service player keeps ownership. */
    fun release() {
        try {
            _controller.value?.release()
        } catch (_: Exception) {
        }
        _controller.value = null
        try {
            controllerFuture?.cancel(true)
        } catch (_: Exception) {
        }
        controllerFuture = null
    }

    /** Live toggle for both DSP stages (Settings flip while playing). */
    fun syncStabilizerEnabled(enabled: Boolean) {
        try {
            PlaybackService.currentService()?.normalizer?.setEnabled(enabled)
        } catch (_: Exception) {
        }
        try {
            PlaybackService.currentService()?.stabilizer?.setEnabled(enabled)
        } catch (_: Exception) {
        }
    }

    fun prepareStream(
        masterUrl: String,
        subtitle: SubtitleTrack?,
        startPositionMs: Long,
        quality: TvPlaybackQuality,
        speed: TvPlaybackSpeed,
        audioLanguage: String?,
        stabilizerEnabled: Boolean,
    ) {
        try {
            PlaybackService.currentService()?.prepareStream(
                masterUrl = masterUrl,
                subtitle = subtitle,
                startPositionMs = startPositionMs,
                quality = quality,
                speed = speed,
                audioLanguage = audioLanguage,
                stabilizerEnabled = stabilizerEnabled
            )
        } catch (_: Exception) {
        }
    }

    fun rebuildSubtitleSource(masterUrl: String, subtitle: SubtitleTrack?) {
        try {
            PlaybackService.currentService()
                ?.rebuildSubtitleSource(masterUrl, subtitle)
        } catch (_: Exception) {
        }
    }
}
