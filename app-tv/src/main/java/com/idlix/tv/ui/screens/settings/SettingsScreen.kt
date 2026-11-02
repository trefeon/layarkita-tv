package com.idlix.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.idlix.tv.R
import com.idlix.tv.ui.components.TvTextField
import com.idlix.tv.ui.screens.settings.components.UpdateSettingsRow
import com.idlix.tv.ui.components.tvGridFocusFrame
import com.idlix.tv.ui.components.edgeCancels
import com.idlix.tv.ui.components.tvContentSidePadding
import com.idlix.tv.ui.components.topEdgeBarEscape
import com.idlix.tv.ui.theme.NetflixBlack
import com.idlix.tv.ui.theme.NetflixDarkCard
import com.idlix.tv.ui.theme.NetflixLightGray
import com.idlix.tv.ui.theme.NetflixRed
import com.idlix.tv.ui.theme.NetflixWhite
import com.idlix.tv.update.UpdateState

/**
 * TV Settings: a USER-SAFE section on top, an ADVANCED section hidden behind
 * an unlock gesture (7 taps on the version row within 3s, or D-pad
 * Up-Up-Down-Down anywhere on this screen).
 *
 * The user section never renders raw URLs, API keys, timeouts, UA strings or
 * cache sizes. Everything technical lives in the advanced section, which is
 * session-only: relocked on process death ([SettingsViewModel] init) and when
 * leaving this screen (dispose → [SettingsViewModel.relockAdvanced]), so BACK
 * from DevTools always lands on the locked user section.
 *
 * D-pad order is the visual order: update, subtitles, cache, history, about.
 * The unlock affordance itself is not focusable.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
    // Bar DOWN target on the update-check button (see AppNavigation.contentEntryLink).
    entryLinkRequester: FocusRequester? = null,
    // Content-to-bar escape for scrolled rows (see topEdgeBarEscape).
    barLinkRequester: FocusRequester? = null,
    // Same-card return from DevTools (mirrors GenreDetailScreen): the key is
    // hoisted to AppNavigation because this screen disposes under DevTools.
    refocusKey: String? = null,
    onRefocusKeyChange: (String?) -> Unit = {},
    // Opens the in-app developer tools (advanced section only).
    onOpenDevTools: () -> Unit = {},
    // Hoisted column scroll (see AppNavigation): this screen disposes under
    // DevTools, so a local state would reset to 0 and an off-screen return
    // target would never recompose to take focus back.
    scrollState: ScrollState = rememberScrollState()
) {

    val updateState by viewModel.updateState.collectAsState()
    val advancedUnlocked by viewModel.advancedUnlocked.collectAsState()
    val subtitleLang by viewModel.subtitleLanguage.collectAsState()
    val stabilizerEnabled by viewModel.audioStabilizer.collectAsState()
    val cacheMessage by viewModel.cacheMessage.collectAsState()
    val historyCleared by viewModel.historyCleared.collectAsState()

    // Update row owns entry focus: without this the screen opens with no
    // focused node and D-pad input goes nowhere until the user backs out.
    // Delayed like the detail screen: an immediate request fires before the
    // window has focus and is silently dropped.
    val checkFocusRequester = remember { FocusRequester() }
    // Same-card return target for BACK from DevTools (see refocusKey).
    val devToolsFocusRequester = remember { FocusRequester() }
    // Gated update-row refocus: the old LaunchedEffect(updateState) yanked
    // focus back to the check button on EVERY terminal state, stealing the
    // user away from subtitle/cache/history rows. Now only two cases move
    // focus: screen entry (first run, no pending return key) and completion
    // of a user-initiated check/download/install (pending flag set by the
    // click wrappers below — focus is then on the update row or nowhere,
    // because the Checking/Downloading spinners are not focusable).
    // Navigating away never trips either gate, so no yank mid-navigation.
    // Screen-to-top on genuine content→bar escape (see topEdgeBarEscape):
    // UP from a scrolled row lands on the tabs, so pull the column back
    // into view underneath. Suspend lambda — the modifier launches it async.
    val scrollToTop: suspend () -> Unit = {
        try {
            scrollState.scrollTo(0)
        } catch (_: Exception) {}
    }
    var firstEntry by remember { mutableStateOf(true) }
    var updateFocusPending by remember { mutableStateOf(false) }
    LaunchedEffect(updateState) {
        when (updateState) {
            is UpdateState.Idle,
            is UpdateState.UpToDate,
            is UpdateState.Available,
            is UpdateState.Downloaded,
            is UpdateState.Failed -> {
                if ((firstEntry && refocusKey == null) || updateFocusPending) {
                    firstEntry = false
                    updateFocusPending = false
                    // Park + retry: the nav-transition window drops the first
                    // request, so pull the column to 0 and re-assert (8x40ms).
                    try {
                        scrollState.scrollTo(0)
                    } catch (_: Exception) {}
                    repeat(8) {
                        try {
                            checkFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                        kotlinx.coroutines.delay(40)
                    }
                }
            }
            is UpdateState.Checking,
            is UpdateState.Downloading -> Unit
        }
    }
    // Return from DevTools parks on the DevTools entry card; an unknown key
    // — or a relocked advanced section hiding that card — parks on entry.
    // Scroll-then-focus with watchdog: the hoisted scroll may have kept a
    // deep position while this screen was disposed; scroll back so the target
    // recomposes, then fall back to entry if the card never consumes.
    // The retry loops double as the settle window; the effect always ends
    // parked on a focusable, so no separate watchdog is needed.
    LaunchedEffect(refocusKey) {
        val key = refocusKey ?: return@LaunchedEffect
        firstEntry = false
        updateFocusPending = false
        if (key == "devtools" && advancedUnlocked) {
            try {
                scrollState.scrollTo(scrollState.maxValue)
            } catch (_: Exception) {}
            repeat(8) {
                try {
                    devToolsFocusRequester.requestFocus()
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(40)
            }
        } else {
            try {
                scrollState.scrollTo(0)
            } catch (_: Exception) {}
            repeat(4) {
                try {
                    checkFocusRequester.requestFocus()
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(40)
            }
        }
        onRefocusKeyChange(null)
    }
    // Leaving Settings relocks the advanced section (session-only gate).
    DisposableEffect(Unit) {
        onDispose { viewModel.relockAdvanced() }
    }
    var showHistoryConfirm by remember { mutableStateOf(false) }
    // Safe option ("No") owns focus while the destructive confirm is visible,
    // so a stray OK press can never land on "Yes, clear".
    val historyNoFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showHistoryConfirm) {
        if (showHistoryConfirm) {
            try {
                historyNoFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // BACK dismisses the destructive confirm first: the tab root consumes
    // system Back, so without this the confirm could only be left via D-pad.
    BackHandler(enabled = showHistoryConfirm) { showHistoryConfirm = false }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack)
            .verticalScroll(scrollState)
            .focusRestorer()
            // D-pad Up-Up-Down-Down unlock. Never consumes the key (returns
            // false unless the sequence completes) so remote navigation keeps
            // working; focus order is untouched.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> viewModel.onDpadUp()
                    Key.DirectionDown -> viewModel.onDpadDown()
                    else -> false
                }
            }
            .padding(horizontal = tvContentSidePadding(), vertical = 24.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ---- Header ------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = NetflixRed,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings),
                    color = NetflixWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_desc),
                    color = NetflixLightGray,
                    fontSize = 13.sp
                )
            }
        }

        // ================================================================
        // USER-SAFE SECTION
        // ================================================================
        SectionHeader(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.settings_updates_title)
        )
        val stateSnapshot = updateState
        UpdateSettingsRow(
            state = stateSnapshot,
            onCheckClick = { updateFocusPending = true; viewModel.checkForUpdates() },
            checkButtonModifier = Modifier
                .focusRequester(checkFocusRequester)
                .then(if (entryLinkRequester != null) Modifier.focusRequester(entryLinkRequester) else Modifier)
                .topEdgeBarEscape(barLinkRequester, onEscape = scrollToTop),
            onDownloadClick = {
                val available = stateSnapshot as? UpdateState.Available ?: return@UpdateSettingsRow
                updateFocusPending = true
                viewModel.downloadUpdate(available.assetUrl)
            },
            onInstallClick = {
                val downloaded = stateSnapshot as? UpdateState.Downloaded ?: return@UpdateSettingsRow
                updateFocusPending = true
                viewModel.installUpdate(downloaded.fileUri)
            }
        )

        SettingsCard(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.settings_subtitle_title),
            desc = stringResource(R.string.settings_subtitle_desc)
        ) {
            SettingsRow(
                headline = stringResource(R.string.settings_subtitle_indonesian),
                selected = subtitleLang == SettingsViewModel.LANG_INDONESIAN,
                onClick = { viewModel.setSubtitleLanguage(SettingsViewModel.LANG_INDONESIAN) },
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
            )
            SettingsRow(
                headline = stringResource(R.string.settings_subtitle_english),
                selected = subtitleLang == SettingsViewModel.LANG_ENGLISH,
                onClick = { viewModel.setSubtitleLanguage(SettingsViewModel.LANG_ENGLISH) },
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
            )
            SettingsRow(
                headline = stringResource(R.string.settings_subtitle_off),
                selected = subtitleLang == SettingsViewModel.LANG_OFF,
                onClick = { viewModel.setSubtitleLanguage(SettingsViewModel.LANG_OFF) },
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
            )
        }

        SettingsCard(
            icon = Icons.Default.VolumeUp,
            title = stringResource(R.string.settings_audio_title),
            desc = stringResource(R.string.settings_audio_desc)
        ) {
            SettingsRow(
                headline = stringResource(R.string.settings_audio_on),
                selected = stabilizerEnabled,
                onClick = { viewModel.setAudioStabilizer(true) },
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
            )
            SettingsRow(
                headline = stringResource(R.string.settings_audio_off),
                selected = !stabilizerEnabled,
                onClick = { viewModel.setAudioStabilizer(false) },
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
            )
        }

        SettingsCard(
            icon = Icons.Default.Image,
            title = stringResource(R.string.settings_cache_title),
            desc = stringResource(R.string.settings_cache_desc)
        ) {
            SettingsRow(
                headline = stringResource(R.string.settings_cache_title),
                leadingIcon = Icons.Default.Refresh,
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop,
                onClick = { viewModel.clearImageCache() }
            )
            if (cacheMessage != null) {
                StatusLine(
                    ok = cacheMessage == "done",
                    text = if (cacheMessage == "done") {
                        stringResource(R.string.settings_cache_done)
                    } else {
                        stringResource(R.string.loading_error)
                    }
                )
            }
        }

        SettingsCard(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.settings_history_title),
            desc = stringResource(R.string.settings_history_desc)
        ) {
            if (!showHistoryConfirm) {
                SettingsRow(
                    headline = stringResource(R.string.settings_history_title),
                    leadingIcon = Icons.Default.Delete,
                    barEscapeLink = barLinkRequester, onBarEscape = scrollToTop,
                    onClick = { showHistoryConfirm = true }
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_history_confirm_title),
                    color = NetflixWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_history_confirm_desc),
                    color = NetflixLightGray,
                    fontSize = 12.sp
                )
                SettingsRow(
                    headline = stringResource(R.string.settings_history_no),
                    leadingIcon = Icons.Default.Refresh,
                    barEscapeLink = barLinkRequester, onBarEscape = scrollToTop,
                    onClick = { showHistoryConfirm = false },
                    modifier = Modifier.focusRequester(historyNoFocusRequester)
                )
                SettingsRow(
                    headline = stringResource(R.string.settings_history_yes),
                    leadingIcon = Icons.Default.Delete,
                    barEscapeLink = barLinkRequester, onBarEscape = scrollToTop,
                    destructive = true,
                    onClick = {
                        showHistoryConfirm = false
                        viewModel.clearWatchHistory()
                    }
                )
            }
            if (historyCleared) {
                StatusLine(
                    ok = true,
                    text = stringResource(R.string.settings_history_done)
                )
            }
        }

        // About / version row. Tapping (OK on the remote) 7 times within 3s
        // unlocks the advanced section. Focusable, last in the user order.
        SettingsCard(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about_title),
            desc = stringResource(
                R.string.settings_version_label,
                viewModel.appVersionName,
                viewModel.appVersionCode
            )
        ) {
            SettingsRow(
                headline = stringResource(R.string.settings_about_title),
                leadingIcon = Icons.Default.Info,
                barEscapeLink = barLinkRequester, onBarEscape = scrollToTop,
                onClick = { viewModel.registerVersionClick() },
                // Locked section: About is the last focusable — DOWN stays.
                // Unlocked: advanced cards below, leave DOWN to geometry.
                isBottom = !advancedUnlocked
            )
            if (advancedUnlocked) {
                StatusLine(
                    ok = true,
                    text = stringResource(R.string.adv_unlocked)
                )
            }
        }

        // ================================================================
        // ADVANCED SECTION (gated, session-only)
        // ================================================================
        if (advancedUnlocked) {
            SectionHeader(
                icon = Icons.Default.Build,
                title = stringResource(R.string.adv_section_title),
                desc = stringResource(R.string.adv_section_desc)
            )
            // In-app developer tools: key-event log, state inspector, manual
            // actions. Entry lives here so the unlock gate guards it. Card
            // order below is free: the key/override fields are TvTextFields
            // whose BACK handling releases the input session, so rows after
            // them stay reachable by remote.
            SettingsCard(
                icon = Icons.Default.Build,
                title = stringResource(R.string.dev_tools_title),
                desc = stringResource(R.string.dev_tools_desc)
            ) {
                SettingsRow(
                    headline = stringResource(R.string.dev_tools_open),
                    leadingIcon = Icons.Default.Build,
                    onClick = {
                        // Entry leaves the refocus key alone: the BACK path
                        // from DevTools sets "devtools" (see AppNavigation),
                        // so a pre-set here would be consumed by this
                        // screen's own return effect before we leave.
                        onOpenDevTools()
                    },
                    modifier = Modifier.focusRequester(devToolsFocusRequester),
                    barEscapeLink = barLinkRequester, onBarEscape = scrollToTop
                )
            }
            AdvancedSubdlCard(viewModel = viewModel, barEscapeLink = barLinkRequester, onBarEscape = scrollToTop)
            AdvancedResolverCard(viewModel = viewModel, barEscapeLink = barLinkRequester, onBarEscape = scrollToTop)
            AdvancedErrorLogCard(viewModel = viewModel, barEscapeLink = barLinkRequester, onBarEscape = scrollToTop)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // One-shot flags are consumed a few seconds after they appear.
    if (cacheMessage != null) {
        androidx.compose.runtime.LaunchedEffect(cacheMessage) {
            kotlinx.coroutines.delay(4_000)
            viewModel.consumeCacheMessage()
        }
    }
    if (historyCleared) {
        androidx.compose.runtime.LaunchedEffect(historyCleared) {
            kotlinx.coroutines.delay(4_000)
            viewModel.consumeHistoryCleared()
        }
    }
}

// ------------------------------------------------------------------ pieces

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    desc: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            // Neutral landmark (never red): red is reserved for primary
            // actions, selection and errors.
            tint = NetflixWhite,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = NetflixWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (desc != null) {
                Text(
                    text = desc,
                    color = NetflixLightGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    desc: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NetflixDarkCard, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = NetflixWhite,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                color = NetflixWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = desc,
            color = NetflixLightGray,
            fontSize = 12.sp
        )
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRow(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
    destructive: Boolean = false,
    // Content-to-bar escape (see topEdgeBarEscape): position-gated, so only a
    // row scrolled under the transparent bar escapes and in-content UP is
    // untouched. Every settings row passes the screen link. [onBarEscape]
    // pulls the column back to 0 on a genuine escape (see scrollToTop).
    barEscapeLink: FocusRequester? = null,
    onBarEscape: (suspend () -> Unit)? = null,
    // True-last row on screen hardens DOWN (geometry no-op there anyway).
    // Full-width rows need no LEFT/RIGHT traps: no horizontal neighbor exists.
    isBottom: Boolean = false,
) {
    // Focus frame: 4dp headroom covers the zoom overflow + 2dp ring so a
    // row settling at the scroll-viewport edge never crops. The ring is
    // explicit so focus stays visible on any container; TalkBack reads the
    // headline + supporting as one unit in visual order.
    var focused by remember { mutableStateOf(false) }
    val supportingContent: (@Composable () -> Unit)? = if (supporting != null) {
        { Text(text = supporting, fontSize = 12.sp) }
    } else {
        null
    }
    Box(
        Modifier
            .tvGridFocusFrame(horizontalPadding = 0.dp, verticalPadding = 4.dp)
    ) {
        ListItem(
            selected = selected,
            onClick = onClick,
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = headline,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (selected) "● $headline" else headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (destructive) NetflixRed else Color.Unspecified
                    )
                }
            },
            supportingContent = supportingContent,
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .border(
                    // Dark ring (not white): focused rows invert to white,
                    // so a white ring would vanish — black reads on the
                    // white row and doubles as the mouse-hover outline.
                    width = if (focused) 2.5.dp else 0.dp,
                    color = if (focused) NetflixBlack else NetflixDarkCard,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .topEdgeBarEscape(barEscapeLink, onEscape = onBarEscape)
                .edgeCancels(down = isBottom)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = text,
            tint = if (ok) NetflixWhite else NetflixRed,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = if (ok) NetflixWhite else NetflixRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ------------------------------------------------------- advanced: SubDL key

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdvancedSubdlCard(
    viewModel: SettingsViewModel,
    // Content-to-bar escape for the rows (see topEdgeBarEscape); the key
    // field is a TvTextField whose BACK handling releases the input session.
    barEscapeLink: FocusRequester? = null,
    // Genuine escapes also pull the column to 0 (see scrollToTop).
    onBarEscape: (suspend () -> Unit)? = null,
) {
    val context = LocalContext.current
    val subdlKey by viewModel.subdlKey.collectAsState()
    val testing by viewModel.subdlTesting.collectAsState()
    val testOk by viewModel.subdlTestOk.collectAsState()
    val testStatus by viewModel.subdlTestStatus.collectAsState()
    var visible by remember { mutableStateOf(false) }
    // Done chains to Save (Next/Done chaining via TvTextField).
    val saveKeyFocusRequester = remember { FocusRequester() }

    SettingsCard(
        icon = Icons.Default.Key,
        title = stringResource(R.string.adv_subdl_title),
        desc = stringResource(R.string.adv_subdl_desc)
    ) {
        TvTextField(
            value = subdlKey,
            onValueChange = viewModel::setSubdlKeyInput,
            hint = stringResource(R.string.adv_subdl_hint),
            leadingIcon = { focused ->
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = stringResource(R.string.adv_subdl_title),
                    tint = if (focused) NetflixWhite else NetflixLightGray,
                    modifier = Modifier.size(18.dp)
                )
            },
            imeAction = ImeAction.Done,
            nextFieldRequester = saveKeyFocusRequester,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation('*')
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            barEscapeLink = barEscapeLink,
            onBarEscape = onBarEscape
        )

        SettingsRow(
            headline = if (testing) {
                context.getString(R.string.subdl_testing)
            } else {
                stringResource(R.string.adv_subdl_test)
            },
            leadingIcon = Icons.Default.Refresh,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = {
                viewModel.testSubdlKey(
                    testingLabel = context.getString(R.string.subdl_testing),
                    validLabel = context.getString(R.string.subdl_valid),
                    quotaLabel = context.getString(R.string.subdl_quota_error),
                    failedLabel = { detail ->
                        context.getString(R.string.subdl_failed_error, detail)
                    }
                )
            }
        )
        SettingsRow(
            headline = stringResource(
                if (visible) R.string.adv_subdl_hide else R.string.adv_subdl_show
            ),
            leadingIcon = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = { visible = !visible }
        )
        SettingsRow(
            headline = stringResource(R.string.adv_subdl_save),
            leadingIcon = Icons.Default.Save,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = { viewModel.saveSubdlKey() },
            modifier = Modifier.focusRequester(saveKeyFocusRequester)
        )
        SettingsRow(
            headline = stringResource(R.string.adv_subdl_clear),
            leadingIcon = Icons.Default.Delete,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = { viewModel.clearSubdlKey() }
        )

        if (testStatus != null) {
            StatusLine(ok = testOk == true, text = testStatus ?: "")
        }
    }
}

// ------------------------------------------------------ advanced: resolver

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdvancedResolverCard(
    viewModel: SettingsViewModel,
    // Same bar-escape contract as the SubDL card.
    barEscapeLink: FocusRequester? = null,
    // Genuine escapes also pull the column to 0 (see scrollToTop).
    onBarEscape: (suspend () -> Unit)? = null,
) {
    val context = LocalContext.current
    val baseUrl by viewModel.resolverBaseUrl.collectAsState()
    val lastChecked by viewModel.resolverLastChecked.collectAsState()
    val refreshing by viewModel.resolverRefreshing.collectAsState()
    val notice by viewModel.resolverNotice.collectAsState()
    var override by remember { mutableStateOf("") }
    // Done chains to Apply (Next/Done chaining via TvTextField).
    val applyOverrideFocusRequester = remember { FocusRequester() }

    SettingsCard(
        icon = Icons.Default.Dns,
        title = stringResource(R.string.adv_resolver_title),
        desc = stringResource(R.string.adv_resolver_desc)
    ) {
        Text(
            text = stringResource(R.string.adv_resolver_current, baseUrl),
            color = NetflixWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(
                R.string.adv_resolver_last_checked,
                lastChecked ?: stringResource(R.string.adv_resolver_never)
            ),
            color = NetflixLightGray,
            fontSize = 12.sp
        )
        SettingsRow(
            headline = stringResource(R.string.adv_resolver_refresh),
            leadingIcon = Icons.Default.Refresh,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = {
                viewModel.setResolverNotice(null)
                viewModel.refreshResolverNow()
            }
        )
        if (refreshing) {
            Text(
                text = stringResource(R.string.subdl_testing),
                color = NetflixLightGray,
                fontSize = 12.sp
            )
        }

        Text(
            text = stringResource(R.string.adv_resolver_override_label),
            color = NetflixWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        TvTextField(
            value = override,
            onValueChange = {
                override = it
                viewModel.setResolverNotice(null)
            },
            hint = stringResource(R.string.adv_resolver_override_hint),
            imeAction = ImeAction.Done,
            nextFieldRequester = applyOverrideFocusRequester,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            barEscapeLink = barEscapeLink,
            onBarEscape = onBarEscape
        )
        SettingsRow(
            headline = stringResource(R.string.adv_resolver_apply),
            leadingIcon = Icons.Default.Save,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = {
                val ok = viewModel.applyResolverOverride(override)
                viewModel.setResolverNotice(
                    if (ok) {
                        context.getString(R.string.adv_resolver_saved)
                    } else {
                        context.getString(R.string.adv_resolver_invalid)
                    }
                )
                if (ok) override = ""
            },
            modifier = Modifier.focusRequester(applyOverrideFocusRequester)
        )
        if (notice != null) {
            StatusLine(ok = notice == context.getString(R.string.adv_resolver_saved), text = notice ?: "")
        }
    }
}

// ------------------------------------------------------ advanced: error log

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdvancedErrorLogCard(
    viewModel: SettingsViewModel,
    // Content-to-bar escape for the row buttons (see topEdgeBarEscape).
    barEscapeLink: FocusRequester? = null,
    // Genuine escapes also pull the column to 0 (see scrollToTop).
    onBarEscape: (suspend () -> Unit)? = null,
) {
    val logText by viewModel.errorLogText.collectAsState()

    SettingsCard(
        icon = Icons.Default.BugReport,
        title = stringResource(R.string.adv_log_title),
        desc = stringResource(R.string.adv_log_desc)
    ) {
        if (logText.isBlank()) {
            Text(
                text = stringResource(R.string.adv_log_empty),
                color = NetflixLightGray,
                fontSize = 12.sp
            )
        } else {
            Text(
                text = logText.take(2_000),
                color = NetflixLightGray,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(NetflixBlack, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(10.dp)
            )
        }
        SettingsRow(
            headline = stringResource(R.string.adv_log_refresh),
            leadingIcon = Icons.Default.Refresh,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = { viewModel.refreshErrorLog() }
        )
        SettingsRow(
            headline = stringResource(R.string.adv_log_clear),
            leadingIcon = Icons.Default.Delete,
            barEscapeLink = barEscapeLink, onBarEscape = onBarEscape,
            onClick = { viewModel.clearErrorLog() },
            // True-last row on screen — DOWN stays.
            isBottom = true
        )
    }
}
