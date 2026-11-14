package com.layarkita.tv.ui.screens.player

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.layarkita.tv.ui.components.TvLoadingSpinner
import com.layarkita.tv.ui.input.GamepadKeys
import com.layarkita.tv.ui.input.GamepadKeys.PlayerActions
import com.layarkita.tv.ui.screens.player.playback.PlayerSessionManager
import com.layarkita.tv.ui.screens.player.playback.PlayerSurfaceHost
import com.layarkita.tv.player.PlaybackService
import com.layarkita.tv.ui.components.TvSizing
import com.layarkita.tv.R
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    contentType: String,
    contentId: String,
    title: String,
    posterUrl: String? = null,
    backdropUrl: String? = null,
    slug: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var showControls by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var userInteractionCount by remember { mutableIntStateOf(0) }

    val remainingMs = if (duration > 0L) (duration - currentPos).coerceAtLeast(0L) else Long.MAX_VALUE
    val isNearEnd = duration > 60_000L && remainingMs in 0L..90_000L
    val isNextPromptVisible = isNearEnd && uiState.nextEpisode != null && !uiState.isNextEpisodeDismissed

    val rootFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

    // Transport handle: the connected MediaController (a Player). The concrete
    // ExoPlayer lives in PlaybackService; every call below stays on the
    // Player interface so the D-pad key table never needs the service player.
    val sessionManager = remember(context) { PlayerSessionManager(context) }
    val controller by sessionManager.controller.collectAsState()
    var activePlayer: Player? by remember { mutableStateOf(null) }
    // Both DSP stages live in the service now; the screen only flips the
    // shared persisted toggle live through the manager bridge.
    var stabilizerEnabled by remember { mutableStateOf(loadAudioStabilizer(context)) }
    val qualityReturnFocusRequester = remember { FocusRequester() }
    var isQualityPickerVisible by remember { mutableStateOf(false) }
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    // Caption overlay ref: PlayerSurfaceHost owns the screen-level
    // SubtitleView (outside the video frame, so captions always follow the
    // screen with fixed-fraction padding on every panel size). Fed via
    // scale to the screen, text cues sit at screen-height fraction.
    val screenSubsRef = remember { mutableStateOf<SubtitleView?>(null) }
    var availableHeights by remember { mutableStateOf(emptySet<Int>()) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var bufferedPos by remember { mutableLongStateOf(0L) }
    fun closeQualityPicker() {
        isQualityPickerVisible = false
        // Re-show controls so the invoker button exists when focus returns.
        showControls = true
        // Deferred in the close path itself (not a keyed effect): guaranteed
        // to launch whenever the picker closes, and ordered after the Play
        // re-show so the invoker wins deterministically.
        scope.launch {
            delay(200)
            try {
                qualityReturnFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }
    val speedReturnFocusRequester = remember { FocusRequester() }
    val subtitleReturnFocusRequester = remember { FocusRequester() }
    var isSpeedPickerVisible by remember { mutableStateOf(false) }
    val selectedSpeed by viewModel.selectedSpeed.collectAsState()
    var availableAudioTracks by remember { mutableStateOf(emptyList<TvAudioOption>()) }
    val selectedAudioLang by viewModel.audioLanguage.collectAsState()
    val tryAgainFocusRequester = remember { FocusRequester() }
    fun closeSpeedPicker() {
        isSpeedPickerVisible = false
        // Re-show controls so the invoker button exists when focus returns.
        showControls = true
        // Deferred in the close path itself (not a keyed effect): guaranteed
        // to launch whenever the picker closes, and ordered after the Play
        // re-show so the invoker wins deterministically.
        scope.launch {
            delay(200)
            try {
                speedReturnFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(contentType, contentId) {
        viewModel.loadPickerTokens()
        viewModel.loadStream(
            contentType = contentType,
            contentId = contentId,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            slug = slug,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    // MediaController connect once per screen entry; released on dispose.
    // The service player keeps ownership across reconnects.
    LaunchedEffect(Unit) {
        sessionManager.connect()
    }
    DisposableEffect(Unit) {
        onDispose { sessionManager.release() }
    }
    // Mirror the controller into the transport handle the key table reads.
    LaunchedEffect(controller) {
        activePlayer = controller
    }

    // Aspect application (the ONLY scaling writer): the controller is a
    // MediaController, which exposes no setVideoScalingMode (the Media3 1.6.1
    // session protocol forwards surfaces but has no scaling command —
    // verified against the AAR). TV app version: the film always auto-fits
    // the screen — FIT shows the entire picture (black bars on 21:9 scope,
    // full-bleed on 16:9) and NEVER stretches; there is no zoom control.
    // Applied live with no rebuild; the fresh service player boots at FIT.
    LaunchedEffect(controller) {
        try {
            PlaybackService.currentPlayer()?.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    // Stabilizer toggle applies live to both service stages (Settings flip
    // while playing): stage-1 PCM normalizer + stage-2 session effect, no
    // rebuild. The screen only flips the shared persisted toggle.
    DisposableEffect(context) {
        val sp = context.applicationContext.getSharedPreferences(
            "layarkita_tv_prefs", Context.MODE_PRIVATE
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            stabilizerEnabled = loadAudioStabilizer(context)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(stabilizerEnabled) {
        sessionManager.syncStabilizerEnabled(stabilizerEnabled)
    }

    // Auto-hide controls after 6 seconds of inactivity, paused during modal, scrubbing, or next episode prompt
    LaunchedEffect(showControls, uiState.isSubtitlePickerVisible, isQualityPickerVisible, isSpeedPickerVisible, isScrubbing, userInteractionCount, isNextPromptVisible) {
        if (uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible || isScrubbing || isNextPromptVisible) {
            // Keep controls open or don't steal focus from prompt / modals
            return@LaunchedEffect
        }
        if (showControls) {
            delay(6000)
            showControls = false
        } else {
            // Post-exit beat: let the overlay fade-out release focus first so
            // the root request lands on a focus-less tree (no dead keys).
            delay(100)
            try {
                    if (!isNextPromptVisible && !uiState.isSubtitlePickerVisible && !isQualityPickerVisible) {
                    rootFocusRequester.requestFocus()
                }
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(50)
            try {
                playButtonFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }
    // Default focus on Try Again as soon as a playback error renders.
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            delay(50)
            try {
                tryAgainFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val rawCode = keyEvent.nativeKeyEvent.keyCode
                    // Gamepad extras first; unknown codes fall through to the
                    // existing remote/media branches below (remote paths untouched).
                    val gamepadConsumed = GamepadKeys.onGamepadKey(
                        rawCode,
                        true,
                        PlayerActions(
                            onConfirm = {
                                if (isNextPromptVisible) {
                                    false
                                } else if (!showControls) {
                                    showControls = true
                                    true
                                } else false
                            },
                            onTogglePlayPause = {
                                val p = activePlayer
                                if (p == null || isNextPromptVisible) {
                                    false
                                } else {
                                    if (p.isPlaying) p.pause() else p.play()
                                    showControls = true
                                    true
                                }
                            },
                            onSeekForward = {
                                val p = activePlayer
                                if (p == null || isNextPromptVisible || uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible) {
                                    false
                                } else {
                                    // duration is TIME_UNSET (< 0) before the first frame;
                                    // fall back to unbounded so the seek never goes negative.
                                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    p.seekTo((p.currentPosition + 10000L).coerceAtMost(dur))
                                    showControls = true
                                    true
                                }
                            },
                            onSeekBackward = {
                                val p = activePlayer
                                if (p == null || isNextPromptVisible || uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible) {
                                    false
                                } else {
                                    p.seekTo((p.currentPosition - 10000L).coerceAtLeast(0L))
                                    showControls = true
                                    true
                                }
                            },
                            onOpenSubtitlePicker = {
                                if (isNextPromptVisible || uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible ||
                                    uiState.resolvedStream == null
                                ) {
                                    false
                                } else {
                                    viewModel.openSubtitlePicker()
                                    userInteractionCount++
                                    true
                                }
                            },
                            onToggleControls = {
                                if (isNextPromptVisible || uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible) {
                                    false
                                } else {
                                    showControls = !showControls
                                    userInteractionCount++
                                    true
                                }
                            }
                        )
                    )
                    if (gamepadConsumed) {
                        true
                    } else when (
                        // Gamepad B mirrors system Back through the exact branch below.
                        if (rawCode == KeyEvent.KEYCODE_BUTTON_B) KeyEvent.KEYCODE_BACK
                        else rawCode
                    ) {
                        KeyEvent.KEYCODE_BACK -> {
                            if (isQualityPickerVisible) {
                                closeQualityPicker()
                                true
                            } else if (isSpeedPickerVisible) {
                                closeSpeedPicker()
                                true
                            } else if (uiState.isSubtitlePickerVisible) {
                                viewModel.closeSubtitlePicker()
                                showControls = true
                                scope.launch {
                                    delay(200)
                                    try {
                                        subtitleReturnFocusRequester.requestFocus()
                                    } catch (_: Exception) {}
                                }
                                true
                            } else if (isNextPromptVisible) {
                                viewModel.dismissNextEpisode()
                                showControls = true
                                userInteractionCount++
                                scope.launch {
                                    delay(200)
                                    try {
                                        playButtonFocusRequester.requestFocus()
                                    } catch (_: Exception) {}
                                }
                                true
                            } else {
                                // Single-press exit: scrub-dirty BACK is consumed
                                // by the seek bar itself (revert + Play refocus);
                                // clean focus, visible/hidden OSD, loading and
                                // error all exit here — no hide-first strand.
                                onBack()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (isNextPromptVisible) {
                                false
                            } else if (!showControls) {
                                showControls = true
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else if (keyEvent.nativeKeyEvent.repeatCount > 0) {
                                true
                            } else {
                                activePlayer?.let { p ->
                                    if (p.isPlaying) p.pause() else p.play()
                                }
                                showControls = true
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else {
                                activePlayer?.pause()
                                showControls = true
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else {
                                activePlayer?.let { p ->
                                    p.seekTo((p.currentPosition - 10000L).coerceAtLeast(0L))
                                }
                                showControls = true
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else {
                                activePlayer?.let { p ->
                                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    p.seekTo((p.currentPosition + 10000L).coerceAtMost(dur))
                                }
                                showControls = true
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else if (uiState.nextEpisode != null && !uiState.isNextEpisodeDismissed) {
                                viewModel.playNextEpisode()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else {
                                activePlayer?.seekTo(0L)
                                showControls = true
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_INFO,
                        KeyEvent.KEYCODE_GUIDE -> {
                            if (isNextPromptVisible || isQualityPickerVisible || isSpeedPickerVisible || uiState.isSubtitlePickerVisible) {
                                false
                            } else {
                                showControls = !showControls
                                userInteractionCount++
                                true
                            }
                        }
                        KeyEvent.KEYCODE_CAPTIONS -> {
                            if (isNextPromptVisible || uiState.isSubtitlePickerVisible || isQualityPickerVisible || isSpeedPickerVisible ||
                                uiState.resolvedStream == null
                            ) {
                                false
                            } else {
                                viewModel.openSubtitlePicker()
                                userInteractionCount++
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (isNextPromptVisible) {
                                false
                            } else if (!showControls) {
                                activePlayer?.let { p ->
                                    p.seekTo((p.currentPosition - 10000L).coerceAtLeast(0L))
                                }
                                showControls = true
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (isNextPromptVisible) {
                                false
                            } else if (!showControls) {
                                activePlayer?.let { p ->
                                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    p.seekTo((p.currentPosition + 10000L).coerceAtMost(dur))
                                }
                                showControls = true
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isNextPromptVisible) {
                                false
                            } else if (!showControls) {
                                showControls = true
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        if (uiState.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TvLoadingSpinner()
                Spacer(modifier = Modifier.height(16.dp))

                // Generic loading only: the gate wait is real but its length
                // is not the user's business — no countdown shown.
                Text(
                    text = stringResource(R.string.loading_secure_stream),
                    color = NetflixWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (uiState.error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.failed_to_play),
                    color = NetflixWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { viewModel.loadStream(contentType, contentId, title) },
                        colors = ButtonDefaults.colors(
                            containerColor = NetflixRed,
                            contentColor = NetflixWhite,
                            focusedContainerColor = NetflixRed,
                            focusedContentColor = NetflixWhite
                        ),
                        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.5.dp, NetflixWhite)
                            )
                        ),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
                        modifier = Modifier.focusRequester(tryAgainFocusRequester)
                    ) {
                        Text(text = stringResource(R.string.try_again), fontSize = 14.sp)
                    }

                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.colors(
                            containerColor = NetflixLightGray.copy(alpha = 0.3f),
                            contentColor = NetflixWhite,
                            // Stay translucent on focus (never invert to
                            // white): the white ring must read on the dark
                            // scrim, same treatment as the subtitle rows.
                            focusedContainerColor = NetflixLightGray.copy(alpha = 0.3f),
                            focusedContentColor = NetflixWhite
                        ),
                        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.5.dp, NetflixWhite)
                            )
                        ),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp))
                    ) {
                        Text(text = stringResource(R.string.back), fontSize = 14.sp)
                    }
                }
            }
        } else if (uiState.resolvedStream != null) {
            val resolved = uiState.resolvedStream!!

            // The concrete player lives in PlaybackService; the screen
            // holds only the connected controller (a Player). Custom HLS +
            // subtitle source assembly runs in-process on the service player.
            val controllerReady = controller

            fun applySubtitleTrack(track: com.layarkita.data.model.SubtitleTrack?) {
                sessionManager.rebuildSubtitleSource(resolved.hlsMasterUrl, track)
            }

            // Prepare once per resolved stream when the controller is ready.
            // Keyed on the resolved instance: Retry re-emits a fresh instance.
            LaunchedEffect(resolved, controllerReady) {
                if (controllerReady == null) return@LaunchedEffect
                sessionManager.prepareStream(
                    masterUrl = resolved.hlsMasterUrl,
                    subtitle = uiState.selectedSubtitle,
                    startPositionMs = uiState.initialPositionMs,
                    quality = selectedQuality,
                    speed = selectedSpeed,
                    audioLanguage = selectedAudioLang,
                    stabilizerEnabled = stabilizerEnabled,
                )
            }


            // Poll state from the controller (Player interface) and throttle
            // progress saves to avoid disk I/O lag.
            LaunchedEffect(controllerReady) {
                val c = controllerReady ?: return@LaunchedEffect
                var lastSavedPos = 0L
                while (true) {
                    currentPos = c.currentPosition
                    duration = c.duration.coerceAtLeast(0L)
                    bufferedPos = c.bufferedPosition
                    isPlaying = c.isPlaying
                    if (Math.abs(currentPos - lastSavedPos) >= 5000L && duration > 0) {
                        viewModel.saveProgress(currentPos, duration)
                        lastSavedPos = currentPos
                    }
                    delay(500)
                }
            }

            DisposableEffect(controllerReady) {
                val playerListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            if (uiState.nextEpisode != null && !uiState.isNextEpisodeDismissed) {
                                viewModel.playNextEpisode()
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        videoW = videoSize.width
                        videoH = videoSize.height
                    }

                    // Feed the screen-level overlay directly: Player callbacks
                    // arrive on the main thread, so setCues is safe here.
                    override fun onCues(cueGroup: CueGroup) {
                        try {
                            screenSubsRef.value?.setCues(cueGroup.cues)
                        } catch (_: Exception) {}
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        try {
                            availableHeights = tracks.groups
                                .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
                                .filter { it.sampleMimeType?.startsWith("video/") == true }
                                .mapNotNull { it.height.takeIf { h -> h > 0 } }
                                .toSet()
                            availableAudioTracks = tracks.groups
                                .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
                                .filter { it.sampleMimeType?.startsWith("audio/") == true }
                                .mapNotNull { format ->
                                    val lang = format.language?.takeIf { it.isNotBlank() }
                                        ?: return@mapNotNull null
                                    TvAudioOption(
                                        language = lang,
                                        label = format.label?.takeIf { it.isNotBlank() } ?: lang
                                    )
                                }
                                .distinctBy { it.language }
                        } catch (_: Exception) {}
                    }
                }
                val c = controllerReady ?: return@DisposableEffect onDispose {}
                c.addListener(playerListener)

                onDispose {
                    try {
                        val finalPos = c.currentPosition
                        val finalDur = c.duration
                        if (finalDur > 0) {
                            viewModel.saveProgress(finalPos, finalDur)
                        }
                    } catch (_: Exception) {}
                    try {
                        c.removeListener(playerListener)
                    } catch (_: Exception) {}
                    // Service owns the player: no stabilizer detach, no release.
                }
            }

            // Video surface + screen-level caption overlay (the overlay fills
            // the screen, so captions always follow the screen with
            // fixed-fraction padding, never the movie frame).
            PlayerSurfaceHost(
                player = controllerReady,
                subtitleViewRef = screenSubsRef,
            )

            // D-Pad Player Controls
            TvPlayerControls(
                title = resolved.title,
                isVisible = showControls && !uiState.isSubtitlePickerVisible && !isQualityPickerVisible && !isSpeedPickerVisible,
                isPlaying = isPlaying,
                currentPosMs = currentPos,
                durationMs = duration,
                isSubtitleEnabled = uiState.isSubtitleEnabled,
                selectedSubtitleLabel = uiState.selectedSubtitle?.label,
                playButtonModifier = Modifier.focusRequester(playButtonFocusRequester),
                onPlayPauseClick = {
                    val c = controllerReady
                    if (c != null) {
                        if (c.isPlaying) c.pause() else c.play()
                    }
                    showControls = true
                    userInteractionCount++
                },
                onSeekBackward = {
                    controllerReady?.let { c ->
                        c.seekTo((c.currentPosition - 10000L).coerceAtLeast(0L))
                    }
                    showControls = true
                    userInteractionCount++
                },
                onSeekForward = {
                    // duration is TIME_UNSET (< 0) before the first frame;
                    // same guard as the D-pad/gamepad seek branches above.
                    controllerReady?.let { c ->
                        val dur = c.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                        c.seekTo((c.currentPosition + 10000L).coerceAtMost(dur))
                    }
                    showControls = true
                    userInteractionCount++
                },
                onSeekTo = { targetMs ->
                    controllerReady?.let { c ->
                        c.seekTo(targetMs.coerceIn(0L, c.duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
                    }
                    showControls = true
                    userInteractionCount++
                },
                onScrubbingChanged = { scrubbing ->
                    isScrubbing = scrubbing
                },
                onUserInteraction = {
                    userInteractionCount++
                },
                onToggleSubtitle = {
                    val willBeEnabled = !uiState.isSubtitleEnabled
                    viewModel.toggleSubtitle()
                    controllerReady?.let { c ->
                        val trackSelector = c.trackSelectionParameters.buildUpon()
                        if (willBeEnabled) {
                            trackSelector
                                .setPreferredTextLanguage(uiState.selectedSubtitle?.lang ?: "id")
                                .setSelectUndeterminedTextLanguage(true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        } else {
                            trackSelector.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        }
                        c.trackSelectionParameters = trackSelector.build()
                    }
                    showControls = true
                    userInteractionCount++
                },
                onOpenSubtitlePicker = {
                    viewModel.openSubtitlePicker()
                    userInteractionCount++
                },
                subtitleButtonModifier = Modifier.focusRequester(subtitleReturnFocusRequester),
                bufferedPosMs = bufferedPos,
                videoWidth = videoW,
                videoHeight = videoH,
                qualityLabel = stringResource(selectedQuality.titleRes),
                onOpenQualityPicker = {
                    isQualityPickerVisible = true
                    userInteractionCount++
                },
                qualityButtonModifier = Modifier.focusRequester(qualityReturnFocusRequester),
                speedLabel = selectedSpeed.label,
                onOpenSpeedPicker = {
                    isSpeedPickerVisible = true
                    userInteractionCount++
                },
                speedButtonModifier = Modifier.focusRequester(speedReturnFocusRequester),
            )

            // Subtitle Picker Modal (IDLIX & SubDL.com third party)
            TvSubtitlePickerModal(
                isVisible = uiState.isSubtitlePickerVisible,
                availableSubtitles = resolved.subtitles,
                selectedSubtitle = uiState.selectedSubtitle,
                isSubtitleEnabled = uiState.isSubtitleEnabled,
                subdlSubtitles = uiState.subdlSubtitles,
                isSearchingSubdl = uiState.isSearchingSubdl,
                onSelectTrack = { track ->
                    viewModel.selectSubtitleTrack(track)
                    applySubtitleTrack(track)
                    showControls = true
                    scope.launch {
                        delay(200)
                        try {
                            subtitleReturnFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    }
                },
                onSelectSubdl = { subdl ->
                    viewModel.selectSubdlSubtitle(subdl) { newTrack ->
                        applySubtitleTrack(newTrack)
                    }
                    showControls = true
                    scope.launch {
                        delay(200)
                        try {
                            subtitleReturnFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    }
                },
                onSearchSubdlClick = {
                    viewModel.searchSubdlSubtitles()
                },
                onDismiss = {
                    viewModel.closeSubtitlePicker()
                    showControls = true
                    scope.launch {
                        delay(200)
                        try {
                            subtitleReturnFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    }
                },
                availableAudioTracks = availableAudioTracks,
                selectedAudioLanguage = selectedAudioLang,
                onSelectAudio = { language ->
                    viewModel.setAudioLanguage(language)
                    controllerReady?.let { c ->
                        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                            .setPreferredAudioLanguage(language)
                            .build()
                    }
                    userInteractionCount++
                }
            )
            // Quality Picker Modal (live cap, no player recreation)
            TvQualityPickerModal(
                isVisible = isQualityPickerVisible,
                currentQuality = selectedQuality,
                availableHeights = availableHeights,
                onSelectQuality = { quality ->
                    viewModel.setQuality(quality)
                    try {
                        controllerReady?.let { applyPlaybackQuality(it, quality) }
                    } catch (_: Exception) {}
                    // Brief beat so the checkmark on the selected row is
                    // visible before the modal vanishes (D-pad confirmation).
                    scope.launch {
                        delay(350)
                        closeQualityPicker()
                    }
                },
                onDismiss = {
                    closeQualityPicker()
                }
            )
            // Speed Picker Modal (live rate, no player rebuild)
            TvSpeedPickerModal(
                isVisible = isSpeedPickerVisible,
                currentSpeed = selectedSpeed,
                onSelectSpeed = { speed ->
                    viewModel.setSpeed(speed)
                    try {
                        controllerReady?.let { applyPlaybackSpeed(it, speed) }
                    } catch (_: Exception) {}
                    // Brief beat so the checkmark on the selected row is
                    // visible before the modal vanishes (D-pad confirmation).
                    scope.launch {
                        delay(350)
                        closeSpeedPicker()
                    }
                },
                onDismiss = {
                    closeSpeedPicker()
                }
            )
            // Next Episode Prompt for TV Series (When <= 90s remaining)
            if (uiState.nextEpisode != null) {
                // remainingMs is Long.MAX_VALUE while duration is unknown; clamp
                // before toInt() so the countdown text never wraps negative.
                val remainingSec = ((remainingMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong())).toInt().coerceAtLeast(0)
                TvNextEpisodePrompt(
                    nextEpisode = uiState.nextEpisode!!,
                    remainingSeconds = remainingSec,
                    isVisible = isNextPromptVisible && !uiState.isSubtitlePickerVisible && !isQualityPickerVisible && !isSpeedPickerVisible,
                    onPlayNext = {
                        viewModel.playNextEpisode()
                    },
                    onDismiss = {
                        viewModel.dismissNextEpisode()
                        // Deterministic landing: controls reappear with
                        // Play focused instead of a focus-less frame.
                        showControls = true
                        userInteractionCount++
                        scope.launch {
                            delay(200)
                            try {
                                playButtonFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 48.dp,
                            bottom = if (showControls) 115.dp else 40.dp
                        )
                )
            }
        }
    }
}
