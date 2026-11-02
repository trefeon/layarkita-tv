package com.idlix.tv.ui.screens.player.playback

import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface

/** Captions sit at a fixed fraction of SCREEN height, independent of aspect. */
const val PlayerSubtitleBottomFraction = 0.08f

/**
 * Video surface + screen-level caption overlay.
 *
 * The film always auto-fits the screen, sized from the REAL decoded video
 * dimensions ([Player.Listener.onVideoSizeChanged]): the surface is laid out
 * with [Modifier.aspectRatio], so a 21:9 feature renders at full width with
 * natural black bars top/bottom and a 16:9 feature fills the panel — never
 * cropped, never stretched, on any TV size. No manual zoom control exists.
 * VIDEO_SCALING_MODE_SCALE_TO_FIT is additionally applied service-side
 * (PlayerScreen) as defense in depth.
 *
 * The caption overlay fills the screen — outside the video frame — so
 * captions always follow the SCREEN with fixed-fraction padding (8% bottom
 * inset) on every TV panel size, never the movie frame; text cues sit at
 * [PlayerSubtitleBottomFraction] of screen height and bitmap cues scale to
 * the screen. Cue text runs through the text-renderer normalization in
 * MediaSourceHelper, so the overlay bottom fraction applies uniformly.
 *
 * The host owns no player: [player] is the connected [MediaController]
 * (a [Player]). Cue delivery stays push-based — the screen's
 * [Player.Listener.onCues] feeds [subtitleViewRef].
 */
@UnstableApi
@Composable
fun PlayerSurfaceHost(
    player: Player?,
    subtitleViewRef: MutableState<SubtitleView?>,
    modifier: Modifier = Modifier,
) {
    // Null = controller not connected yet: compose nothing rather than crash;
    // PlayerScreen passes its nullable MediaController unconditionally, so a
    // bare requireNotNull here would take down the player on every open.
    val connectedPlayer = player ?: return
    // Real decoded frame aspect (16:9 until the first frame reports, so the
    // loading state behind us is a plain full-screen backdrop, not a guess).
    var videoAspect by remember(connectedPlayer) { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(connectedPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    val ratio = w * videoSize.pixelWidthHeightRatio / h
                    val oriented = if (videoSize.unappliedRotationDegrees == 90 ||
                        videoSize.unappliedRotationDegrees == 270
                    ) {
                        if (ratio != 0f) 1f / ratio else 0f
                    } else {
                        ratio
                    }
                    if (oriented > 0f && oriented.isFinite()) {
                        videoAspect = oriented
                    }
                }
            }
        }
        connectedPlayer.addListener(listener)
        onDispose { connectedPlayer.removeListener(listener) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        PlayerSurface(
            player = connectedPlayer,
            // fit-inside: largest ratio-matching size within the screen.
            // 21:9 -> full width + natural bars; 16:9 -> full-bleed.
            modifier = Modifier.aspectRatio(videoAspect)
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setApplyEmbeddedStyles(false)
                    setStyle(
                        CaptionStyleCompat(
                            Color.WHITE,
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            Color.BLACK,
                            null
                        )
                    )
                    setFractionalTextSize(0.053f)
                    setBottomPaddingFraction(PlayerSubtitleBottomFraction)
                    subtitleViewRef.value = this
                }
            },
            update = { view ->
                view.setBottomPaddingFraction(PlayerSubtitleBottomFraction)
            },
            onRelease = { view ->
                if (subtitleViewRef.value === view) subtitleViewRef.value = null
                try {
                    view.setCues(null)
                } catch (_: Exception) {
                }
            }
        )
    }
}
