package com.layarkita.tv.ui.screens.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import android.view.KeyEvent as NativeKeyEvent
import com.layarkita.tv.BuildConfig
import com.layarkita.tv.R
import com.layarkita.tv.ui.components.topEdgeBarEscape
import com.layarkita.tv.ui.components.edgeCancels
import com.layarkita.tv.ui.components.TvSizing
import com.layarkita.tv.ui.components.tvContentSidePadding
import com.layarkita.tv.ui.components.tvGridFocusFrame
import com.layarkita.tv.ui.components.tvMatrixNeighbors
import com.layarkita.tv.ui.components.TvMovieCard
import com.layarkita.tv.ui.navigation.Screen
import com.layarkita.tv.ui.screens.settings.SettingsViewModel
import com.layarkita.tv.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------
// Dev key log: process-wide circular buffer (last 20 DOWN events).
//
// The recorder lives on the DevTools root via onPreviewKeyEvent (never
// consumes, so D-pad/BACK keep working) and each focusable on this screen
// reports its label via [DevKeyLog.noteFocus]. Because preview fires BEFORE
// focus moves, a record captures `focusBefore` immediately and the next focus
// arrival patches `focusAfter` — the row then reads "KEY · before → after".
// ---------------------------------------------------------------------------

data class DevKeyRecord(
    val seq: Long,
    val name: String,
    val focusBefore: String,
    val focusAfter: String?,
    val atMs: Long = System.currentTimeMillis(),
)

object DevKeyLog {
    const val CAPACITY = 20
    const val NO_FOCUS = "—"

    /**
     * A focus arrival only patches the latest pending record when it lands
     * soon after the key (a normal D-pad move settles in milliseconds).
     * Anything later — e.g. re-entering this screen minutes after a BACK
     * that popped it — leaves the record's after-label empty ("…") instead
     * of attributing an unrelated arrival to the old key.
     */
    const val AFTER_PATCH_WINDOW_MS = 1_500L

    private val _records = MutableStateFlow<List<DevKeyRecord>>(emptyList())
    val records: StateFlow<List<DevKeyRecord>> = _records.asStateFlow()

    @Volatile
    var lastFocus: String = NO_FOCUS
        private set

    private var seq = 0L

    @Synchronized
    fun record(name: String) {
        seq += 1
        _records.value = (_records.value + DevKeyRecord(seq, name, lastFocus, null))
            .takeLast(CAPACITY)
    }

    @Synchronized
    fun noteFocus(label: String) {
        lastFocus = label
        val cur = _records.value
        val idx = cur.indexOfLast { it.focusAfter == null }
        if (idx >= 0 &&
            System.currentTimeMillis() - cur[idx].atMs <= AFTER_PATCH_WINDOW_MS
        ) {
            _records.value = cur.toMutableList().also {
                it[idx] = it[idx].copy(focusAfter = label)
            }
        }
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
    }
}

// ---------------------------------------------------------------------------
// Dev carousel feed: the home hero carousel owns its index locally
// (remember), so it reports here for the state inspector. Last write wins;
// untouched until Home composes (shows "not observed").
// ---------------------------------------------------------------------------

object DevToolsCarousel {
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _observed = MutableStateFlow(false)
    val observed: StateFlow<Boolean> = _observed.asStateFlow()

    fun report(index: Int, count: Int) {
        _index.value = index
        _count.value = count
        _observed.value = true
    }
}

/**
 * Short remote/gamepad-friendly key names from the platform key code.
 * Compose Key constants are deliberately avoided: the native code covers
 * remotes, gamepads and media keys uniformly with a keyCodeToString fallback.
 */
fun devKeyName(keyCode: Int): String {
    return when (keyCode) {
        NativeKeyEvent.KEYCODE_DPAD_UP -> "UP"
        NativeKeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        NativeKeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        NativeKeyEvent.KEYCODE_DPAD_CENTER -> "CENTER(DPAD)"
        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> "CENTER(Enter)"
        NativeKeyEvent.KEYCODE_BUTTON_A -> "CENTER(Gamepad-A)"
        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_ESCAPE -> "BACK"
        NativeKeyEvent.KEYCODE_BUTTON_B -> "BACK(Gamepad-B)"
        NativeKeyEvent.KEYCODE_MENU -> "MENU"
        NativeKeyEvent.KEYCODE_INFO -> "INFO"
        NativeKeyEvent.KEYCODE_GUIDE -> "GUIDE"
        NativeKeyEvent.KEYCODE_BUTTON_START -> "MENU(Gamepad-Start)"
        NativeKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        NativeKeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
        NativeKeyEvent.KEYCODE_MEDIA_PAUSE -> "MEDIA_PAUSE"
        NativeKeyEvent.KEYCODE_MEDIA_STOP -> "MEDIA_STOP"
        NativeKeyEvent.KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT"
        NativeKeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREV"
        NativeKeyEvent.KEYCODE_MEDIA_REWIND -> "MEDIA_REWIND"
        NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MEDIA_FFWD"
        NativeKeyEvent.KEYCODE_BUTTON_X -> "GAMEPAD-X"
        NativeKeyEvent.KEYCODE_BUTTON_Y -> "GAMEPAD-Y"
        NativeKeyEvent.KEYCODE_BUTTON_L1 -> "GAMEPAD-L1"
        NativeKeyEvent.KEYCODE_BUTTON_R1 -> "GAMEPAD-R1"
        NativeKeyEvent.KEYCODE_BUTTON_SELECT -> "GAMEPAD-Select"
        else -> NativeKeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

private fun describeUpdateState(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "Idle"
    is UpdateState.Checking -> "Checking"
    is UpdateState.UpToDate -> "UpToDate(${state.currentVersion})"
    is UpdateState.Available -> "Available(${state.version}, ${state.sizeBytes}B)"
    is UpdateState.Downloading -> "Downloading(${(state.progress * 100).toInt()}%)"
    is UpdateState.Downloaded -> "Downloaded"
    is UpdateState.Failed -> "Failed(${state.reason.take(80)})"
}

/**
 * In-app developer tools, reachable only from the hidden advanced section of
 * Settings (the gate itself is untouched). Read-only inspector rows up top,
 * three manual actions, and the key-event log at the bottom.
 *
 * Focus: entry parks on "Run update check" (delayed like every other screen —
 * an immediate request fires before the window has focus and is dropped).
 * The log rows are display-only Text (never focusable) so D-pad UP/DOWN can
 * never trap inside the log. System BACK (or the back button) pops to
 * Settings; the recorder never consumes keys.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DevToolsScreen(
    modifier: Modifier = Modifier,
    // Own nav-entry instance: resolver/update state derives from the same
    // singletons + prefs as Settings, so refresh/check hit the real engine.
    settingsViewModel: SettingsViewModel = viewModel(),
    currentRoute: String = Screen.DevTools.route,
    // Bar DOWN target on the first action (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for the first action (see topEdgeBarEscape).
    barLinkRequester: FocusRequester? = null,
    // Detail-style return key, hoisted in AppNavigation like every other
    // destination (this screen pushes nothing, so the key is normally null;
    // entry still respects it for contract symmetry).
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    // Hoisted column scroll (see AppNavigation): this screen disposes on
    // BACK, so a local state would reset to 0 on re-entry.
    scrollState: ScrollState = rememberScrollState(),
    onBack: () -> Unit = {},
    // Dummy-QA navigation (see the fixtures section below): opening a DUMMY
    // id exercises the real detail/player error states (Retry + Back) with
    // zero catalog dependence. Defaults no-op for previews/tests.
    onOpenDummyDetail: (contentType: String, slug: String) -> Unit = { _, _ -> },
    onOpenDummyPlayer: (contentType: String, contentId: String) -> Unit = { _, _ -> },
) {
    val records by DevKeyLog.records.collectAsState()
    val updateState by settingsViewModel.updateState.collectAsState()
    val resolverBase by settingsViewModel.resolverBaseUrl.collectAsState()
    val resolverChecked by settingsViewModel.resolverLastChecked.collectAsState()
    val carouselIndex by DevToolsCarousel.index.collectAsState()
    val carouselCount by DevToolsCarousel.count.collectAsState()
    val carouselObserved by DevToolsCarousel.observed.collectAsState()

    // Screen-to-top on genuine content→bar escape (see topEdgeBarEscape).
    val scrollToTop: suspend () -> Unit = {
        try {
            scrollState.scrollTo(0)
        } catch (_: Exception) {}
    }

    val runUpdateFocus = remember { FocusRequester() }
    // Park + retry: the nav-transition window drops the first request, so
    // pull the column to 0 and re-assert (8x40ms) — entry never parks black.
    // First-entry gated so a settings BACK-loop re-entry still parks cleanly
    // without yanking focus on every recomposition.
    var firstEntry by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (!firstEntry || refocusKey != null) return@LaunchedEffect
        firstEntry = false
        try {
            scrollState.scrollTo(0)
        } catch (_: Exception) {}
        repeat(8) {
            try {
                runUpdateFocus.requestFocus()
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(40)
        }
        onRefocusKeyChange(null)
    }

    val cfg = LocalConfiguration.current
    val density = LocalDensity.current.density
    val flavor = if (BuildConfig.DEBUG) {
        stringResource(R.string.dev_row_debug)
    } else {
        stringResource(R.string.dev_row_release)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(com.layarkita.tv.ui.theme.NetflixBlack)
            .verticalScroll(scrollState)
            // Root preview recorder: sees keys before children consume them,
            // never consumes (returns false) so focus + BACK keep working.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    DevKeyLog.record(devKeyName(event.nativeKeyEvent.keyCode))
                }
                false
            }
            .padding(horizontal = tvContentSidePadding(), vertical = 24.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ---- Header ------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = stringResource(R.string.dev_tools_title),
                tint = com.layarkita.tv.ui.theme.NetflixRed,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.dev_tools_title),
                    color = com.layarkita.tv.ui.theme.NetflixWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.dev_tools_desc),
                    color = com.layarkita.tv.ui.theme.NetflixLightGray,
                    fontSize = 13.sp
                )
            }
        }

        // ---- Actions -----------------------------------------------------
        DevSection(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.dev_actions_title)
        ) {
            DevButton(
                focusKey = "$currentRoute/action:run-update-check",
                label = stringResource(R.string.dev_action_update),
                icon = Icons.Default.Refresh,
                onClick = { settingsViewModel.checkForUpdates() },
                modifier = Modifier
                    .focusRequester(runUpdateFocus)
                    .then(
                        if (entryLinkRequester != null) {
                            Modifier.focusRequester(entryLinkRequester)
                        } else {
                            Modifier
                        }
                    )
                    .topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop)
            )
            DevButton(
                focusKey = "$currentRoute/action:force-resolver-refresh",
                label = stringResource(R.string.dev_action_resolver),
                icon = Icons.Default.Refresh,
                onClick = { settingsViewModel.refreshResolverNow() },
                modifier = Modifier.topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop)
            )
            DevButton(
                focusKey = "$currentRoute/action:clear-key-log",
                label = stringResource(R.string.dev_keylog_clear),
                icon = Icons.Default.Delete,
                onClick = { DevKeyLog.clear() },
                modifier = Modifier.topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop)
            )
        }

        // ---- State inspector (read-only) ---------------------------------
        DevSection(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.dev_inspector_title)
        ) {
            DevRow(
                label = stringResource(R.string.dev_row_version),
                value = "${settingsViewModel.appVersionName} " +
                    "(${settingsViewModel.appVersionCode}) · $flavor"
            )
            DevRow(
                label = stringResource(R.string.dev_row_screen),
                value = stringResource(
                    R.string.dev_screen_format,
                    cfg.screenWidthDp,
                    cfg.screenHeightDp,
                    String.format("%.2f", density)
                )
            )
            DevRow(
                label = stringResource(R.string.dev_row_resolver),
                value = resolverBase
            )
            DevRow(
                label = stringResource(R.string.dev_row_resolver_checked),
                value = resolverChecked ?: stringResource(R.string.dev_row_never)
            )
            DevRow(
                label = stringResource(R.string.dev_row_update),
                value = describeUpdateState(updateState)
            )
            DevRow(
                label = stringResource(R.string.dev_row_carousel),
                value = if (carouselObserved && carouselCount > 0) {
                    stringResource(
                        R.string.dev_carousel_format,
                        carouselIndex + 1,
                        carouselCount
                    )
                } else {
                    stringResource(R.string.dev_row_never)
                }
            )
            DevRow(
                label = stringResource(R.string.dev_row_route),
                value = currentRoute
            )
        }

        // ---- Key event log (display-only, never focusable) ----------------
        DevSection(
            icon = Icons.Default.Build,
            title = stringResource(R.string.dev_keylog_title),
            desc = stringResource(R.string.dev_keylog_desc)
        ) {
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.dev_keylog_empty),
                    color = com.layarkita.tv.ui.theme.NetflixLightGray,
                    fontSize = 12.sp
                )
            } else {
                // Newest first: the latest press is visible without scrolling.
                records.asReversed().forEach { record ->
                    Text(
                        text = "#${record.seq} ${record.name} · " +
                            "${record.focusBefore} → ${record.focusAfter ?: "…"}",
                        color = com.layarkita.tv.ui.theme.NetflixWhite,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // ---- UI QA fixtures (DUMMY — no network) ---------------------------
        // Labeled placeholders only: extreme card content, missing art,
        // 0.0/blank ratings, quality pills, aspect stills and partial-row
        // grids. Every tile opens the real DUMMY error route (Retry + Back),
        // so no dummy is a dead button and nothing hits the catalog.
        DevSection(
            icon = Icons.Default.Movie,
            title = "UI QA fixtures (DUMMY)",
            desc = "Placeholder art + fake ids only — never real titles. Opens real error states."
        ) {
            // Extreme card content: missing poster, 0.0/blank rating, pills.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TvMovieCard(
                    title = "DUMMY — an extremely long fixture title that must ellipsize across lines without breaking the card",
                    posterUrl = "",
                    rating = "0.0",
                    quality = "WEB-DL",
                    width = 135,
                    height = 200,
                    onClick = { onOpenDummyDetail("movie", "dummy-qa-nonexistent") }
                )
                TvMovieCard(
                    title = "DUMMY — short",
                    posterUrl = "",
                    rating = "",
                    quality = "4K",
                    width = 135,
                    height = 200,
                    onClick = { onOpenDummyDetail("tv_series", "dummy-qa-nonexistent") }
                )
                TvMovieCard(
                    title = "DUMMY — medium two-line fixture title here",
                    posterUrl = "",
                    rating = "8.8",
                    quality = "HD",
                    width = 135,
                    height = 200,
                    rank = 7,
                    onClick = { onOpenDummyPlayer("movie", "dummy-qa-nonexistent") }
                )
            }
            // Aspect-ratio stills: layout proofs at 21:9 / 16:9 / 4:3.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DummyAspectStill(
                    label = "DUMMY 21:9",
                    ratio = 21f / 9f,
                    onClick = { onOpenDummyPlayer("movie", "dummy-qa-nonexistent") },
                    modifier = Modifier.weight(1f)
                )
                DummyAspectStill(
                    label = "DUMMY 16:9",
                    ratio = 16f / 9f,
                    onClick = { onOpenDummyPlayer("movie", "dummy-qa-nonexistent") },
                    modifier = Modifier.weight(1f)
                )
                DummyAspectStill(
                    label = "DUMMY 4:3",
                    ratio = 4f / 3f,
                    onClick = { onOpenDummyPlayer("movie", "dummy-qa-nonexistent") },
                    modifier = Modifier.weight(1f)
                )
            }
            // Partial-row grids (1 item; 5 items in 4 columns): same tile
            // styling + matrix edge math as the real genre grid.
            DummyQaGrid(
                title = "DUMMY grid — 1 item",
                count = 1,
                cols = 4,
                onTileClick = { onOpenDummyDetail("movie", "dummy-qa-nonexistent") }
            )
            DummyQaGrid(
                title = "DUMMY grid — 5 items (partial last row)",
                count = 5,
                cols = 4,
                onTileClick = { onOpenDummyDetail("movie", "dummy-qa-nonexistent") }
            )
            // Real error states with Retry, via the dummy ids.
            DevButton(
                focusKey = "$currentRoute/action:open-dummy-detail",
                label = "Open DUMMY detail (invalid id)",
                icon = Icons.Default.Movie,
                onClick = { onOpenDummyDetail("movie", "dummy-qa-nonexistent") },
                modifier = Modifier.topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop)
            )
            DevButton(
                focusKey = "$currentRoute/action:open-dummy-player",
                label = "Open DUMMY player (invalid id)",
                icon = Icons.Default.Movie,
                onClick = { onOpenDummyPlayer("movie", "dummy-qa-nonexistent") },
                modifier = Modifier.topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop)
            )
        }

        // ---- Back ----------------------------------------------------------
        DevButton(
            focusKey = "$currentRoute/action:back-to-settings",
            label = stringResource(R.string.dev_action_back),
            icon = Icons.Default.Settings,
            onClick = onBack,
            modifier = Modifier.topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop),
            // Last focusable on screen — DOWN stays.
            isLast = true
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ pieces

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DevSection(
    icon: ImageVector,
    title: String,
    desc: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                com.layarkita.tv.ui.theme.NetflixDarkCard,
                androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = com.layarkita.tv.ui.theme.NetflixWhite,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                color = com.layarkita.tv.ui.theme.NetflixWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (desc != null) {
            Text(
                text = desc,
                color = com.layarkita.tv.ui.theme.NetflixLightGray,
                fontSize = 12.sp
            )
        }
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DevButton(
    focusKey: String,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // True-last button (Back) traps DOWN so focus can never leave the screen.
    isLast: Boolean = false,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = com.layarkita.tv.ui.theme.NetflixBlack,
            // Stay dark on focus (never invert to white): the ring must read
            // on the dark card, same as settings rows.
            focusedContainerColor = com.layarkita.tv.ui.theme.NetflixBlack,
            contentColor = com.layarkita.tv.ui.theme.NetflixWhite,
            focusedContentColor = com.layarkita.tv.ui.theme.NetflixWhite
        ),
        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, com.layarkita.tv.ui.theme.NetflixWhite)
            )
        ),
        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
        modifier = modifier
            .height(38.dp)
            .edgeCancels(left = true, right = true, down = isLast)
            .onFocusChanged { if (it.isFocused) DevKeyLog.noteFocus(focusKey) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DevRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = com.layarkita.tv.ui.theme.NetflixLightGray,
            fontSize = 12.sp,
            modifier = Modifier.width(130.dp)
        )
        Text(
            text = value,
            color = com.layarkita.tv.ui.theme.NetflixWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Aspect-ratio layout proof (dummy harness): a focusable still at an exact
 * ratio with a DUMMY label. Activating it opens the dummy player error
 * route, so the tile is never a dead button.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DummyAspectStill(
    label: String,
    ratio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = com.layarkita.tv.ui.theme.NetflixDarkCard,
            contentColor = com.layarkita.tv.ui.theme.NetflixLightGray,
            focusedContainerColor = com.layarkita.tv.ui.theme.NetflixRed,
            focusedContentColor = com.layarkita.tv.ui.theme.NetflixWhite
        ),
        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.5.dp, com.layarkita.tv.ui.theme.NetflixWhite)
            )
        ),
        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        modifier = modifier.aspectRatio(ratio)
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

/**
 * Partial-row grid proof (dummy harness): [count] focusable tiles in
 * [cols] columns using the same tile styling + [tvMatrixNeighbors] edge
 * math as the real genre grid, so 0/1/partial-last-row focus behavior can
 * be verified without network. Tiles open the dummy detail error route.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DummyQaGrid(
    title: String,
    count: Int,
    cols: Int,
    onTileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = com.layarkita.tv.ui.theme.NetflixLightGray,
            fontSize = 12.sp
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            // Fixed window, no nested scroll: D-pad still moves between the
            // tiles; the outer column owns scrolling.
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .focusRestorer()
        ) {
            items(count) { index ->
                val nav = tvMatrixNeighbors(index = index, size = count, cols = cols)
                val tileRequester = tileRequesters.getOrPut(index) { FocusRequester() }
                Box(
                    Modifier.tvGridFocusFrame(horizontalPadding = 4.dp, verticalPadding = 8.dp)
                ) {
                    Button(
                        onClick = onTileClick,
                        colors = ButtonDefaults.colors(
                            containerColor = com.layarkita.tv.ui.theme.NetflixDarkCard,
                            contentColor = com.layarkita.tv.ui.theme.NetflixLightGray,
                            focusedContainerColor = com.layarkita.tv.ui.theme.NetflixRed,
                            focusedContentColor = com.layarkita.tv.ui.theme.NetflixWhite
                        ),
                        scale = ButtonDefaults.scale(focusedScale = TvSizing.CardFocusScale),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.5.dp, com.layarkita.tv.ui.theme.NetflixWhite)
                            )
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .focusRequester(tileRequester)
                            .focusProperties {
                                up = if (nav.isFirstRow) FocusRequester.Cancel else (tileRequesters[nav.upIndex] ?: FocusRequester.Default)
                                down = if (nav.downIndex == null) FocusRequester.Cancel else (tileRequesters[nav.downIndex] ?: FocusRequester.Default)
                                left = if (nav.leftIndex == null) FocusRequester.Cancel else (tileRequesters[nav.leftIndex] ?: FocusRequester.Default)
                                right = if (nav.rightIndex == null) FocusRequester.Cancel else (tileRequesters[nav.rightIndex] ?: FocusRequester.Default)
                            }
                    ) {
                        Text(
                            text = if (index == 0) "DUMMY — long tile title must ellipsize" else "DUMMY G$index",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
