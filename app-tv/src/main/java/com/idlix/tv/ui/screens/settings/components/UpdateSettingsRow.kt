package com.idlix.tv.ui.screens.settings.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.idlix.tv.R
import com.idlix.tv.ui.components.tvGridFocusFrame
import com.idlix.tv.ui.components.TvLoadingSpinner
import com.idlix.tv.ui.theme.NetflixBlack
import com.idlix.tv.ui.theme.NetflixDarkCard
import com.idlix.tv.ui.theme.NetflixLightGray
import com.idlix.tv.ui.theme.NetflixRed
import com.idlix.tv.ui.theme.NetflixWhite
import com.idlix.tv.update.UpdateState

/**
 * Self-update row for the settings screen. Pure view: every transition is
 * driven by [state] from the owning ViewModel, clicks delegate outward.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateSettingsRow(
    state: UpdateState,
    onCheckClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier,
    checkButtonModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NetflixDarkCard, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.update_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = NetflixWhite
        )
        Spacer(modifier = Modifier.height(8.dp))

        when (state) {
            is UpdateState.Idle -> {
                UpdateRow(
                    label = stringResource(R.string.update_check),
                    onClick = onCheckClick,
                    modifier = checkButtonModifier
                )
            }
            is UpdateState.Checking -> {
                Box(modifier = Modifier.fillMaxWidth()) {
                    TvLoadingSpinner(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_checking),
                    fontSize = 14.sp,
                    color = NetflixLightGray
                )
            }
            is UpdateState.UpToDate -> {
                Text(
                    text = stringResource(
                        R.string.update_up_to_date,
                        state.currentVersion
                    ),
                    fontSize = 14.sp,
                    color = NetflixLightGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                UpdateRow(
                    label = stringResource(R.string.update_check),
                    onClick = onCheckClick,
                    modifier = checkButtonModifier
                )
            }
            is UpdateState.Available -> {
                Text(
                    text = stringResource(
                        R.string.update_available_title,
                        state.version
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NetflixWhite
                )
                if (state.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.notes.trim().take(600),
                        fontSize = 13.sp,
                        color = NetflixLightGray,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.update_size,
                        formatSize(state.sizeBytes)
                    ),
                    fontSize = 13.sp,
                    color = NetflixLightGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                UpdateRow(
                    label = stringResource(R.string.update_download),
                    onClick = onDownloadClick,
                    modifier = checkButtonModifier
                )
            }
            is UpdateState.Downloading -> {
                val percent = (state.progress.coerceIn(0f, 1f) * 100).toInt()
                Text(
                    text = stringResource(R.string.update_downloading, percent),
                    fontSize = 14.sp,
                    color = NetflixWhite
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(NetflixBlack, RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                            .height(8.dp)
                            .background(NetflixRed, RoundedCornerShape(4.dp))
                    )
                }
            }
            is UpdateState.Downloaded -> {
                Text(
                    text = stringResource(R.string.update_downloaded),
                    fontSize = 14.sp,
                    color = NetflixWhite
                )
                Spacer(modifier = Modifier.height(8.dp))
                UpdateRow(
                    label = stringResource(R.string.update_install),
                    onClick = onInstallClick,
                    modifier = checkButtonModifier
                )
            }
            is UpdateState.Failed -> {
                Text(
                    text = stringResource(R.string.update_failed, state.reason),
                    fontSize = 14.sp,
                    color = NetflixLightGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                UpdateRow(
                    label = stringResource(R.string.update_retry),
                    onClick = onCheckClick,
                    modifier = checkButtonModifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpdateRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Focus frame: 4dp headroom covers the zoom overflow + 2dp ring so the
    // entry row never crops at the scroll-viewport edge. The ring is drawn
    // explicitly (not via ListItem defaults) so focus stays visible on any
    // theme container.
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .tvGridFocusFrame(horizontalPadding = 0.dp, verticalPadding = 4.dp)
    ) {
        ListItem(
            selected = false,
            onClick = onClick,
            headlineContent = {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .border(
                    width = if (focused) 2.5.dp else 0.dp,
                    color = if (focused) NetflixBlack else NetflixDarkCard,
                    shape = RoundedCornerShape(8.dp)
                )
        )
    }
}

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "—"
    val kb = sizeBytes / 1024.0
    if (kb < 1024) return "%d KB".format(kb.toInt())
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
