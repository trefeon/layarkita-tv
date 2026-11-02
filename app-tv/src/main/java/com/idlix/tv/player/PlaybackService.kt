package com.idlix.tv.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.idlix.data.model.SubtitleTrack
import com.idlix.tv.MainActivity
import com.idlix.tv.ui.screens.player.AudioStabilizer
import com.idlix.tv.ui.screens.player.LoudnessNormalizeProcessor
import com.idlix.tv.ui.screens.player.MediaSourceHelper
import com.idlix.tv.ui.screens.player.TvPlaybackQuality
import com.idlix.tv.ui.screens.player.TvPlaybackSpeed
import com.idlix.tv.ui.screens.player.applyPlaybackQuality
import com.idlix.tv.ui.screens.player.applyPlaybackSpeed

/**
 * Foreground [MediaSessionService] owning the single shared [ExoPlayer].
 *
 * The player build below moved verbatim from the old in-Compose construction:
 * deep LoadControl backlog (30s min / 120s max / 1.5s start / 5s rebuffer),
 * 4 Mbps initial ABR estimate, damped AdaptiveTrackSelection
 * (15s/25s/25s/0.7), USAGE_MEDIA/MOVIE AudioAttributes with becoming-noisy
 * handling, the PCM loudness-normalizer renderers factory, per-stream token
 * DataSource, and an EventLogger. Offload stays off (a processor in the chain
 * forces the PCM path), so the normalizer always sees decoded audio.
 *
 * Session id is [SESSION_ID] (`idlix-tv-playback`). The UI drives playback
 * through a [MediaController] (see [PlayerSessionManager]); custom
 * [androidx.media3.exoplayer.source.MediaSource] assembly (HLS + subtitle
 * merge) cannot travel over the controller, so [prepareStream] and
 * [rebuildSubtitleSource] run here, in-process, on the owned player.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** Stage-1 PCM loudness normalizer (session-less; toggle via setEnabled). */
    val normalizer = LoudnessNormalizeProcessor()

    /** Stage-2 session-bound [android.media.audiofx.DynamicsProcessing]. */
    val stabilizer = AudioStabilizer()

    override fun onCreate() {
        super.onCreate()
        val player = buildPlayer()
        currentPlayer = player
        currentService = this
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(pending)
            .build()
    }

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = MediaSourceHelper.createRenderersFactory(this, normalizer)

        // Deep backlog rides out mirror stalls; lean start for a fast first frame.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Start ABR at 4 Mbps so the first pick is HD on decent links.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(4_000_000L)
            .build()

        // Damped ABR on mirror-CDN variance; deep buffers tolerate near-defaults.
        val trackSelector = DefaultTrackSelector(
            this,
            AdaptiveTrackSelection.Factory(15_000, 25_000, 25_000, 0.7f)
        )
        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector)
            // Movie content over TV speakers: MOVIE type + media usage with
            // audio-focus handling; becoming-noisy (HDMI/BT drop) pauses.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { addAnalyticsListener(EventLogger()) }
    }

    /**
     * Assembles and starts the stream: token-scoped HLS source (+ optional
     * subtitle merge), persisted quality/speed/audio-language, the 800 kbps
     * SD-mush floor, seamless-refresh strategy, and both DSP stages bound to
     * [stabilizerEnabled].
     */
    fun prepareStream(
        masterUrl: String,
        subtitle: SubtitleTrack?,
        startPositionMs: Long,
        quality: TvPlaybackQuality,
        speed: TvPlaybackSpeed,
        audioLanguage: String?,
        stabilizerEnabled: Boolean,
    ) {
        val player = currentPlayer ?: return
        val tokenQuery = try {
            Uri.parse(masterUrl).query.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val httpFactory = MediaSourceHelper.createHttpDataSourceFactory(tokenQuery)
        player.setMediaSource(
            MediaSourceHelper.buildMediaSource(this, masterUrl, subtitle, httpFactory)
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(subtitle?.lang ?: "id")
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitle == null)
            // Floor kills the sub-SD mush rung; user quality caps still win above.
            .setMinVideoBitrate(800_000)
            .apply { audioLanguage?.let { setPreferredAudioLanguage(it) } }
            .build()
        applyPlaybackQuality(player, quality)
        applyPlaybackSpeed(player, speed)
        // 24p films on 60 Hz panels: seamless refresh switch kills judder.
        player.setVideoChangeFrameRateStrategy(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        )
        stabilizer.attach(player, stabilizerEnabled)
        normalizer.setEnabled(stabilizerEnabled)
        player.prepare()
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.playWhenReady = true
    }

    /**
     * Rebuilds the HLS+subtitle merge around [subtitle] preserving the exact
     * position/play state (subtitle picker path; no full [prepareStream]).
     */
    fun rebuildSubtitleSource(masterUrl: String, subtitle: SubtitleTrack?) {
        val player = currentPlayer ?: return
        val tokenQuery = try {
            Uri.parse(masterUrl).query.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val httpFactory = MediaSourceHelper.createHttpDataSourceFactory(tokenQuery)
        val pos = player.currentPosition
        val wasPlaying = player.isPlaying
        player.setMediaSource(
            MediaSourceHelper.buildMediaSource(this, masterUrl, subtitle, httpFactory),
            pos
        )
        val selector = player.trackSelectionParameters.buildUpon()
        if (subtitle != null) {
            selector
                .setPreferredTextLanguage(subtitle.lang)
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        } else {
            selector.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = selector.build()
        if (wasPlaying) player.play()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = session

    override fun onDestroy() {
        val s = session
        session = null
        currentService = null
        try {
            s?.player?.let { p ->
                try {
                    stabilizer.detach(p as ExoPlayer)
                } catch (_: Exception) {
                }
                try {
                    p.release()
                } catch (_: Exception) {
                }
            }
        } finally {
            currentPlayer = null
            try {
                s?.release()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "idlix-tv-playback"

        @Volatile
        private var currentPlayer: ExoPlayer? = null

        @Volatile
        private var currentService: PlaybackService? = null

        /** In-process owned player (same-process UI; never crosses IPC). */
        fun currentPlayer(): ExoPlayer? = currentPlayer

        fun currentService(): PlaybackService? = currentService

        /** Player-interface view for UI state reads (poll loop, controls). */
        fun currentPlayerInterface(): Player? = currentPlayer
    }
}
