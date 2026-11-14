package com.layarkita.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.layarkita.tv.R
import com.layarkita.tv.ui.theme.NetflixBlack
import com.layarkita.tv.ui.theme.NetflixDarkGray
import com.layarkita.tv.ui.theme.NetflixLightGray
import com.layarkita.tv.ui.theme.NetflixRed
import com.layarkita.tv.ui.theme.NetflixWhite

/**
 * Central route mapping for the top chrome (replaces the old drawer enum).
 * LIBRARY reuses the existing "My List" label and drives the
 * continue_watching route — no new strings, no ViewModel/route changes.
 */
enum class TopBarTab(val labelRes: Int, val route: String) {
    HOME(R.string.home, "home"),
    MOVIES(R.string.movies, "movies"),
    SERIES(R.string.series, "series"),
    GENRES(R.string.genres, "genres"),
    SEARCH(R.string.search, "search"),
    LIBRARY(R.string.my_list, "continue_watching");

    companion object {
        fun fromRoute(route: String?): TopBarTab? = when {
            route == null -> null
            route.startsWith("search") -> SEARCH
            route.startsWith("continue_watching") -> LIBRARY
            route.startsWith("movies") -> MOVIES
            route.startsWith("series") -> SERIES
            route.startsWith("genre") -> GENRES
            else -> HOME
        }
    }
}

private val TabShape = RoundedCornerShape(20.dp)

/**
 * Netflix-style top bar (~64dp, transparent over hero art with a top
 * gradient scrim for legibility — no solid fill, no blur layer).
 *
 * - Left: red LAYARKITA wordmark (text idiom, fenced brand red).
 * - Tabs: [TopBarTab] order; selected = white text + red 2dp underline,
 *   unselected = gray text; focused (any tab) = white 2dp border.
 * - Right: settings gear, monochrome (white when selected/focused,
 *   gray otherwise, white 2dp border on focus).
 *
 * Focus: no autofocus here — launch focus stays on the hero Play button.
 * LEFT/RIGHT moves between tabs and the gear, DOWN drops into content,
 * and UP from the first content rail returns to the bar via normal
 * focus order (bar floats above the NavHost in the shell Box).
 */
/** Shared chrome height (64dp): hero bleeds under the floating bar while text
 *  screens and the AppNavigation shell use [TvTopBarHeight] as their top
 *  inset so focused content never traps under the bar. */
val TvTopBarHeight = 64.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTopBar(
    selectedTab: TopBarTab?,
    onTabSelected: (TopBarTab) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    settingsSelected: Boolean = false,
    // Entry link: D-pad DOWN from any tab/gear parks on the current screen's
    // entry node (hero Play, Back, first tile/card, search field...). Plain
    // focus search dies crossing this overlay boundary into LazyColumn
    // content, so the link is explicit: key-handler first (deterministic),
    // focusProperties as backup for non-key paths.
    downFocusRequester: FocusRequester? = null,
    // Selected-tab link: first-column UP from content escapes to this
    // requester. Attached only to the selected tab / gear — one node at a
    // time, so the shared instance stays unambiguous.
    tabFocusLink: FocusRequester? = null
) {
    // Transparent over hero art (web-style): legibility comes from the top
    // gradient scrim, not a solid bar. No blur layer (overdraw on low-end).
    Column(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(
                    NetflixBlack.copy(alpha = 0.82f),
                    NetflixBlack.copy(alpha = 0.35f),
                    Color.Transparent
                )
            )
        )
    ) {
        // 5%-or-32dp-min side rule: 24dp sat inside the overscan danger zone.
        val sidePadding = tvContentSidePadding()
        val tabs = TopBarTab.values()
        val tabRequesters = remember { List(tabs.size) { FocusRequester() } }
        val gearRequester = remember { FocusRequester() }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTopBarHeight)
                .padding(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand wordmark — red is fenced to this + selected indicator.
            Text(
                text = "LAYARKITA",
                color = NetflixRed,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.width(32.dp))

            // Fixed tab strip: every tab always composed and visible — no
            // horizontal scroll (the escape link needs its target composed).
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for ((index, tab) in tabs.withIndex()) {
                    val leftRequester = if (index == 0) FocusRequester.Cancel else tabRequesters[index - 1]
                    val rightRequester = if (index == tabs.lastIndex) gearRequester else tabRequesters[index + 1]

                    TopBarTabItem(
                        tab = tab,
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        focusRequester = tabRequesters[index],
                        leftRequester = leftRequester,
                        rightRequester = rightRequester,
                        downFocusRequester = downFocusRequester,
                        tabFocusLink = if (tab == selectedTab) tabFocusLink else null
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            SettingsGear(
                selected = settingsSelected,
                onClick = onSettingsClick,
                focusRequester = gearRequester,
                leftRequester = tabRequesters.lastOrNull() ?: FocusRequester.Cancel,
                downFocusRequester = downFocusRequester,
                tabFocusLink = if (settingsSelected) tabFocusLink else null
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBarTabItem(
    tab: TopBarTab,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    rightRequester: FocusRequester,
    downFocusRequester: FocusRequester? = null,
    tabFocusLink: FocusRequester? = null
) {
    val tabInteraction = remember { MutableInteractionSource() }
    val focused by tabInteraction.collectIsFocusedAsState()
    val hovered by tabInteraction.collectIsHoveredAsState()
    val isActive = focused || hovered
    val textColor = if (selected || isActive) NetflixWhite else NetflixLightGray
    val focusManager = LocalFocusManager.current

    val pillModifier = when {
        isActive -> Modifier
            .background(NetflixWhite.copy(alpha = 0.16f), TabShape)
            .border(2.dp, NetflixWhite, TabShape)
        selected -> Modifier
            .background(NetflixRed.copy(alpha = 0.18f), TabShape)
            .border(1.dp, NetflixRed.copy(alpha = 0.7f), TabShape)
        else -> Modifier
    }

    Box(
        modifier = Modifier
            .then(pillModifier)
            .clickable(
                interactionSource = tabInteraction,
                indication = null,
                onClick = {
                    // Action first (usually a route switch — the bar itself
                    // survives, so this node stays composed), then park
                    // D-pad focus here so arrows continue from this tab.
                    // Hover never parks: it only drives the pill highlight.
                    onClick()
                    try {
                        focusRequester.requestFocus()
                    } catch (_: Exception) {
                    }
                }
            )
            .focusRequester(focusRequester)
            .then(if (tabFocusLink != null) Modifier.focusRequester(tabFocusLink) else Modifier)
            .focusProperties {
                up = FocusRequester.Cancel
                down = downFocusRequester ?: FocusRequester.Default
                left = leftRequester
                right = rightRequester
            }
            .onPreviewKeyEvent { event ->
                // Bar-to-bar requester hops (requestFocus on tab nodes) are
                // silently vetoed by the held-session quirk (STUCK-1) —
                // moveFocus runs the framework's own transfer machinery and
                // honors the declarative neighbor redirect, so it works.
                // Cancel edges (Home's LEFT, gear's RIGHT) hold in place.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        val target = downFocusRequester ?: return@onPreviewKeyEvent false
                        try {
                            target.requestFocus()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    else -> false
                }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(tab.labelRes),
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsGear(
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    leftRequester: FocusRequester,
    downFocusRequester: FocusRequester? = null,
    tabFocusLink: FocusRequester? = null
) {
    val gearInteraction = remember { MutableInteractionSource() }
    val focused by gearInteraction.collectIsFocusedAsState()
    val hovered by gearInteraction.collectIsHoveredAsState()
    val isActive = focused || hovered
    val tint = if (selected || isActive) NetflixWhite else NetflixLightGray
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .border(
                width = 2.dp,
                color = if (isActive) NetflixWhite else Color.Transparent,
                shape = TabShape
            )
            .clickable(
                interactionSource = gearInteraction,
                indication = null,
                onClick = {
                    // Same mixed-input contract as the tabs: action first,
                    // then park D-pad focus on the gear. Hover never parks.
                    onClick()
                    try {
                        focusRequester.requestFocus()
                    } catch (_: Exception) {
                    }
                }
            )
            .focusRequester(focusRequester)
            .then(if (tabFocusLink != null) Modifier.focusRequester(tabFocusLink) else Modifier)
            .focusProperties {
                up = FocusRequester.Cancel
                down = downFocusRequester ?: FocusRequester.Default
                left = leftRequester
                right = FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                // Same moveFocus walk as the tabs (requester hops are vetoed);
                // LEFT lands on the Library tab via the declarative redirect,
                // RIGHT is Cancel and holds.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        val target = downFocusRequester ?: return@onPreviewKeyEvent false
                        try {
                            target.requestFocus()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    else -> false
                }
            }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = stringResource(R.string.settings),
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
