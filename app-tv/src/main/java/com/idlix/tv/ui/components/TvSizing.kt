package com.idlix.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch

/**
 * Shared adaptive sizing for 10-foot screens.
 *
 * All values derive from the REAL screen size ([LocalConfiguration] at the
 * screen root — never BoxWithConstraints inside lazy containers, whose
 * infinite parent constraints clamp to max and crop on short viewports).
 *
 * Viewport facts: phone rig is 731x411dp landscape, 1080p TVs are ~960x540dp
 * at tvdpi, 720p panels are smaller, and overscan can eat ~5% per edge.
 */
object TvSizing {
    /** Minimum side inset that survives overscan on any panel. */
    val MinSideInset: Dp = 32.dp

    /** Rail inset: keeps focus-zoom headroom at rail ends. */
    val RailInset: Dp = 48.dp

    /** Focus-frame headroom around rail cards. */
    val RailFocusHorizontal: Dp = 12.dp
    val RailFocusVertical: Dp = 12.dp
    const val CardFocusScale: Float = 1.06f
}

fun tvGridColumnCount(
    screenWidthDp: Int,
    minCellWidthDp: Int,
    horizontalPaddingDp: Int = 96,
    contentInsetDp: Int = 24,
    spacingDp: Int = 16
): Int {
    val safeMin = minCellWidthDp.coerceAtLeast(1)
    val gridW = (screenWidthDp - horizontalPaddingDp - contentInsetDp).coerceAtLeast(safeMin)
    return ((gridW + spacingDp) / (safeMin + spacingDp)).coerceAtLeast(1)
}

data class TvMatrixNeighbors(
    val leftIndex: Int?,
    val rightIndex: Int?,
    val upIndex: Int?,
    val downIndex: Int?,
    val isFirstRow: Boolean,
    val isLastRow: Boolean,
    val isFirstCol: Boolean,
    val isLastCol: Boolean
)

fun tvMatrixNeighbors(index: Int, size: Int, cols: Int): TvMatrixNeighbors {
    val safeCols = cols.coerceAtLeast(1)
    val row = index / safeCols
    val col = index % safeCols
    val lastRowStart = if (size <= 0) 0 else ((size - 1) / safeCols) * safeCols
    val isFirstRow = row == 0
    val isLastRow = index >= lastRowStart
    val isFirstCol = col == 0
    val isLastCol = col == safeCols - 1 || index >= size - 1
    val leftIndex = if (isFirstCol) null else index - 1
    val rightIndex = if (isLastCol) null else index + 1
    val upIndex = if (isFirstRow) null else index - safeCols
    val downIndex = if (isLastRow || size <= 0) null else (index + safeCols).coerceAtMost(size - 1)
    return TvMatrixNeighbors(
        leftIndex = leftIndex,
        rightIndex = rightIndex,
        upIndex = upIndex,
        downIndex = downIndex,
        isFirstRow = isFirstRow,
        isLastRow = isLastRow,
        isFirstCol = isFirstCol,
        isLastCol = isLastCol
    )
}

/**
 * Side inset for full-bleed screens: 5% of the real screen width, floored at
 * [TvSizing.MinSideInset]. On the 731dp rig this is ~36.5dp; on a 960dp-wide
 * TV it is 48dp, matching the rail inset everywhere else.
 */
@Composable
fun tvContentSidePadding(): Dp {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    return (screenW * 0.05f).coerceAtLeast(TvSizing.MinSideInset)
}

/**
 * Detail header height: 78% of the real screen height, clamped so the Play
 * CTA stays above the fold on the 411dp rig (300dp) without stretching on
 * tall panels (420dp). Mirrors the [TvHeroBillboard] pattern (55% - top bar);
 * detail is a fullscreen route with no top bar, so no subtraction.
 */
@Composable
fun tvDetailHeaderHeight(): Dp {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    return (screenH * 0.78f).coerceIn(300.dp, 420.dp)
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusFrame(
    horizontalPadding: Dp = 0.dp,
    verticalPadding: Dp = 0.dp
): Modifier = composed {
    var myRect by remember { mutableStateOf(Rect.Zero) }
    val responder = remember {
        object : BringIntoViewResponder {
            @ExperimentalFoundationApi
            override fun calculateRectForParent(localRect: Rect): Rect {
                return if (myRect != Rect.Zero) myRect else localRect
            }

            @ExperimentalFoundationApi
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
            }
        }
    }
    this
        .onSizeChanged { myRect = Rect(Offset.Zero, it.toSize()) }
        .bringIntoViewResponder(responder)
        .padding(horizontal = horizontalPadding, vertical = verticalPadding)
}

fun Modifier.railFocusFrame(): Modifier =
    tvFocusFrame(horizontalPadding = TvSizing.RailFocusHorizontal, verticalPadding = 0.dp)

fun Modifier.tvGridFocusFrame(
    horizontalPadding: Dp = 0.dp,
    verticalPadding: Dp = 10.dp
): Modifier = tvFocusFrame(horizontalPadding = horizontalPadding, verticalPadding = verticalPadding)

/**
 * Top-edge bar escape for scrollable TV content under the floating bar.
 *
 * When D-pad navigation scrolls a focused node up under the transparent bar,
 * plain UP search dies: the bar tabs overlap the node instead of sitting
 * strictly above it, so no candidate qualifies and focus sticks. This modifier
 * watches the node's window top and, only while it sits at/under the bar's
 * bottom edge ([TvTopBarHeight] + 12dp slack), routes preview-UP to [barLink]
 * (the selected tab / gear, attached in TvTopBar). Anywhere lower it returns
 * false, so plain in-content search proceeds untouched.
 *
 * Null [barLink] is a no-op, so call sites can pass their screen link
 * straight through without branching. Pinned top-most controls (search
 * field) that can never scroll under the bar pass [force] = true to skip
 * the position gate and always escape.
 *
 * [onEscape] is an optional suspend hook the owner runs on a genuine escape
 * (UP key only — tab LEFT/RIGHT and entry focus never enter this path).
 * Entry screens pass `{ listState.scrollToItem(0) }` so the tabs take focus
 * while the hero scrolls back into view underneath. It launches async in the
 * modifier's own scope, so the focus hop never waits on (or loops from) the
 * scroll — and a null hook keeps the legacy focus-only behavior.
 */
fun Modifier.topEdgeBarEscape(
    barLink: FocusRequester?,
    force: Boolean = false,
    onEscape: (suspend () -> Unit)? = null
): Modifier = composed {
    if (barLink == null) return@composed this
    var topPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val edgePx = with(LocalDensity.current) { (TvTopBarHeight + 12.dp).toPx() }
    val focusManager = LocalFocusManager.current
    val escapeScope = rememberCoroutineScope()
    val positionModifier = if (!force) {
        Modifier.onGloballyPositioned { topPx = it.positionInWindow().y }
    } else {
        Modifier
    }
    this
        .then(positionModifier)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
                return@onPreviewKeyEvent false
            }
            if (force || topPx <= edgePx) {
                try {
                    // Scroll-to-top first (fire-and-forget): the column may
                    // sit mid-list with the hero scrolled off — the bar takes
                    // focus immediately while the list settles to index 0.
                    val scroll = onEscape
                    if (scroll != null) {
                        escapeScope.launch {
                            try {
                                scroll()
                            } catch (_: Exception) {}
                        }
                    }
                    // Text-field precedent (SearchScreen): a held focus session
                    // can veto the outgoing transfer, so release it first —
                    // bare requestFocus() silently no-ops (STUCK-1).
                    try {
                        focusManager.clearFocus(force = true)
                    } catch (_: Exception) {}
                    barLink.requestFocus()
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        }
}

/**
 * Absolute-control edge trap: explicitly Cancel the given D-pad directions so
 * geometry can never move focus off a terminal edge (col-0 LEFT, last-cell
 * RIGHT, last-row DOWN, fullscreen UP). Non-cancelled directions keep the
 * default search untouched. Apply on the focusable node itself (after
 * focusRequester, inside the same focusProperties chain).
 */
fun Modifier.edgeCancels(
    left: Boolean = false,
    right: Boolean = false,
    up: Boolean = false,
    down: Boolean = false
): Modifier = composed {
    if (!left && !right && !up && !down) return@composed this
    this.focusProperties {
        if (left) this.left = FocusRequester.Cancel
        if (right) this.right = FocusRequester.Cancel
        if (up) this.up = FocusRequester.Cancel
        if (down) this.down = FocusRequester.Cancel
    }
}

/**
 * D-pad arrow safety net for mixed-input sessions (mouse/keyboard + remote).
 *
 * Bubble-phase only: this fires solely for arrow keys that the focused child
 * did NOT consume — healthy D-pad paths (search moves, Cancel terminals that
 * hold, rail UP/DOWN preview hops, bar escapes) consume before bubbling, so
 * they are untouched. When an arrow does bubble up, the focused node (if any)
 * is a deterministic dead end — typically a link whose target was disposed
 * by a list change — so focus re-parks on [fallback] (the rail's last-known
 * card) instead of stranding the user.
 *
 * Deliberately narrow: arrows only. Tab/Enter pass through untouched, so
 * keyboard traversal order is never trapped or reordered by this modifier,
 * and hover is purely visual (never requests focus). A null [fallback] is a
 * no-op. A disposed [fallback] throws inside requestFocus and is reported as
 * unconsumed (false), preserving the pre-existing behavior exactly.
 */
fun Modifier.tvDpadFocusRecovery(fallback: FocusRequester?): Modifier = composed {
    if (fallback == null) return@composed this
    this.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                try {
                    fallback.requestFocus()
                    true
                } catch (_: Exception) {
                    false
                }
            }
            else -> false
        }
    }
}

/**
 * BACK pop for fullscreen/child routes (detail, genre/collection detail).
 * Key events bubble from the focused child, so a parked loading holder or any
 * focused control still pops — no focus-dependent dead window. Gamepad B is
 * handled in AppNavigation; this covers the system BACK key only.
 */
fun Modifier.dpadBackHandler(onBack: () -> Unit): Modifier = composed {
    this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val code = try {
            event.nativeKeyEvent.keyCode
        } catch (_: Exception) {
            -1
        }
        if (code == android.view.KeyEvent.KEYCODE_BACK) {
            try {
                onBack()
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }
}

/**
 * Upgrades TMDB w342 poster URLs to high-resolution w780 for 1080p/4K TV panels
 * while preserving non-TMDB or blank URLs untouched.
 */
fun String.toFullQualityPosterUrl(): String =
    if (contains("image.tmdb.org/t/p/w342")) replace("/t/p/w342", "/t/p/w780") else this

/**
 * Upgrades TMDB backdrop/still URLs to full quality (w1280 -> original, w500/w342 -> w780)
 * for full-bleed hero billboards and HD cards.
 */
fun String.toFullQualityBackdropUrl(): String = when {
    contains("image.tmdb.org/t/p/w1280") -> replace("/t/p/w1280", "/t/p/original")
    contains("image.tmdb.org/t/p/w500") -> replace("/t/p/w500", "/t/p/w780")
    contains("image.tmdb.org/t/p/w342") -> replace("/t/p/w342", "/t/p/w780")
    else -> this
}
