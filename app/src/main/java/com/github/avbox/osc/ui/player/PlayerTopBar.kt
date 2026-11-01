package com.github.avbox.osc.ui.player

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.player.state.PlayerActions
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.ui.components.marqueeOnOverflow

private val TopBarLineHeight = 36.dp

private val PreviewTopPadding = 4.dp

private val FullscreenTopPadding = 12.dp

private val TopBarSysInfoMinHeight = 16.dp

private val TopBarBatteryIconSize = 16.dp

internal val TopBarActionSize = 42.dp

internal val TopBarActionIconSize = 28.dp

@Composable
internal fun PlayerActionIcons(
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    size: Dp = playerDim(R.dimen.vs_60),
    iconSize: Dp = playerDim(R.dimen.vs_40),
    spread: Boolean = false,
) {
    Row(
        modifier,
        horizontalArrangement = if (spread) Arrangement.Start else Arrangement.spacedBy(playerDim(R.dimen.vs_8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerActionIconButton(
            iconRes = R.drawable.ic_detail_cast,
            contentDescription = stringResource(R.string.common_cast),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onCastClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_subtitle,
            contentDescription = stringResource(R.string.player_menu_subtitle),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onSubtitleClicked,
            onLongClick = actions::onSubtitleLongClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_danmu,
            contentDescription = stringResource(R.string.player_menu_danmu),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onDanmuSettingClicked,
            onLongClick = actions::onDanmuSettingLongClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_audio,
            contentDescription = stringResource(R.string.player_menu_audio_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onAudioTrackClicked,
        )
        PlayerActionIconButton(
            iconRes = R.drawable.player_ic_menu_video,
            contentDescription = stringResource(R.string.player_menu_video_track),
            box = size,
            iconSize = iconSize,
            expand = spread,
            onClick = actions::onVideoTrackClicked,
        )
    }
}

@Composable
private fun RowScope.PlayerActionIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    box: Dp,
    iconSize: Dp,
    expand: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        if (expand) Modifier.weight(1f).height(box) else Modifier.size(box),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .playerPressEffect(onTap = onClick, onLongClick = onLongClick),
        )
    }
}

internal const val TOP_BAR_ROOT_TAG = "playerTopBarRoot"

internal const val TOP_BAR_BACK_TAG = "playerTopBarBack"

@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val configuration = LocalConfiguration.current
    val showActionsInTopBar = configuration.orientation != Configuration.ORIENTATION_PORTRAIT
    val rightVisible = state.topRightVisible && !state.previewMode
    val edge = playerEdgePadding()
    val topPad = if (state.previewMode) PreviewTopPadding else FullscreenTopPadding
    val batteryIconSize = if (configuration.smallestScreenWidthDp >= 600) {
        playerDim(R.dimen.vs_24)
    } else {
        TopBarBatteryIconSize
    }
    val density = LocalDensity.current
    val topInset = if (LocalConfiguration.current.screenWidthDp >= 600) {
        WindowInsets.displayCutout
    } else {
        WindowInsets.safeDrawing
    }
    val topInsetPx = topInset.getTop(density)
    var barTopPx by remember { mutableStateOf(Float.NaN) }
    val extraTop = if (barTopPx.isNaN()) {
        0.dp
    } else {
        with(density) { (topInsetPx - barTopPx).coerceIn(0f, topInsetPx.toFloat()).toDp() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(TOP_BAR_ROOT_TAG)
            .onGloballyPositioned {
                barTopPx = it.positionInWindow().y
            }
    ) {
        Box {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_140))
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.65f),
                                0.45f to Color.Black.copy(alpha = 0.28f),
                                1f to Color.Transparent,
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = edge,
                            end = edge,
                            top = topPad + extraTop,
                            bottom = playerDim(R.dimen.vs_5),
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (state.topLeftVisible) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(TopBarLineHeight)
                                .playerPressEffect(onTap = { actions.onBackClicked() }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.player_ic_close),
                                contentDescription = stringResource(R.string.common_back),
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag(TOP_BAR_BACK_TAG),
                            )
                        }
                        Column(
                            modifier = Modifier.padding(start = playerDim(R.dimen.vs_10)),
                        ) {
                            Text(
                                modifier = Modifier.marqueeOnOverflow(),
                                text = state.title,
                                color = Color.White,
                                fontSize = playerTextSize(R.dimen.ts_26),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sizeLine = if (state.videoQuality.isBlank()) {
                                state.videoSize
                            } else {
                                state.videoSize + " · " + state.videoQuality
                            }
                            if (sizeLine.isNotBlank()) {
                                Text(
                                    text = sizeLine,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = playerTextSize(R.dimen.ts_18),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .marqueeOnOverflow(),
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (rightVisible) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.heightIn(min = TopBarSysInfoMinHeight),
                        ) {
                            if (state.batteryPercent in 0..100) {
                                Text(
                                    text = "${state.batteryPercent}%",
                                    color = Color.White,
                                    fontSize = playerTextSize(R.dimen.ts_18),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Image(
                                    painter = painterResource(batteryIcon(state)),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 4.dp, end = playerDim(R.dimen.vs_10))
                                        .size(batteryIconSize),
                                )
                            }
                            Text(
                                text = state.sysTime,
                                color = Color.White,
                                fontSize = playerTextSize(R.dimen.ts_18),
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        if (showActionsInTopBar) {
                            PlayerActionIcons(actions = actions)
                        }
                    }
                }
            }
        }
    }
}

private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
}
