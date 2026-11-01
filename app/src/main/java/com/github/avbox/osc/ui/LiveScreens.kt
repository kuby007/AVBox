@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionRow
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.theme.cardContainer
import java.util.ArrayList
import java.util.Date
import kotlin.math.max
import com.github.avbox.osc.ui.components.marqueeOnOverflow

@Composable
internal fun LiveScreen(state: LivePlayUiState, actions: LivePlayActions) {
    val background = if (state.frame.isFullBox) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        when (state.frame.pageState) {
            PageState.LOADING -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier.fillMaxSize(),
                loadingContent = { ContainedLoadingIndicator(Modifier.size(64.dp)) },
            )

            PageState.EMPTY -> LoadStateBox(
                state = LoadState.Error(stringResource(R.string.live_empty_channels)),
                emptyText = "",
                errorText = stringResource(R.string.live_empty_channels),
                retryText = stringResource(R.string.common_retry),
                modifier = Modifier.fillMaxSize(),
                onRetry = actions.page.onRetryLoad,
            )

            PageState.READY -> LiveReadyContent(state, actions)
        }
        if (state.epg.sheetVisible) {
            EpgSheet(
                epg = state.epg,
                onDismiss = actions.epg.onDismiss,
                onRowClicked = actions.epg.onRowClicked,
            )
        }
        if (state.settings.sheetVisible) {
            SettingsSheet(
                settings = state.settings,
                onItemClick = actions.settings.onItemClick,
                onItemLongClick = actions.settings.onItemLongClick,
                onDismiss = actions.settings.onDismiss,
            )
        }
        state.passwordDialogTarget?.let {
            LivePasswordDialog(
                onConfirm = actions.page.onPasswordConfirm,
                onDismiss = actions.page.onPasswordDismiss,
            )
        }
    }
}

@Composable
private fun LivePasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_password_title)) },
        text = {
            val dismissThen = LocalSheetDismissThen.current
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text(stringResource(R.string.live_password_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (password.isNotBlank()) dismissThen { onConfirm(password.trim()) }
                    },
                ),
            )
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                enabled = password.isNotBlank(),
                onClick = { dismissThen { onConfirm(password.trim()) } },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            val dismissAnimated = LocalSheetDismiss.current
            TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun LiveReadyContent(state: LivePlayUiState, actions: LivePlayActions) {
    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))
    Column(modifier = Modifier.fillMaxSize()) {
        PlayerArea(
            state = state,
            actions = actions,
            modifier = if (state.frame.isFullBox) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewHeight)
            },
        )
        if (!state.frame.isFullBox) {
            ChannelInfoSection(info = state.channelInfo, timeshift = state.timeshift, overlay = state.overlay)
            ChannelListSection(
                channelList = state.channelList,
                onToggleGroup = actions.list.onToggleGroup,
                onSelectChannel = actions.list.onSelectChannel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlayerArea(state: LivePlayUiState, actions: LivePlayActions, modifier: Modifier) {
    val videoView = actions.player.videoView()
    Box(modifier = modifier.background(Color.Black)) {
        if (videoView != null) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.player.snapshotVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                state.player.snapshotBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        }
        if (!state.player.snapshotVisible &&
            (state.player.playState == PlayState.PREPARING || state.player.playState == PlayState.BUFFERING)
        ) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
        }
        if (state.player.resolutionVisible && state.player.resolutionText.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = state.player.resolutionText,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        state.player.gestureHintText?.let { hint ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        if (state.timeshift.isBackState && state.overlay.visible) {
            TimeshiftBar(
                timeshift = state.timeshift,
                paused = state.player.playState == PlayState.PAUSED,
                onSeek = actions.player.onTimeshiftSeek,
                onTogglePlay = actions.player.onTimeshiftTogglePlay,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (!state.frame.isFullBox) {
            PlayerCornerButtons(actions.player, Modifier.align(Alignment.TopEnd))
        } else if (state.overlay.visible) {
            IconButton(
                onClick = actions.player.onExitFullscreen,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.live_exit_fullscreen),
                    tint = Color.White,
                )
            }
            PlayerCornerButtons(actions.player, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PlayerCornerButtons(actions: LivePlayerActions, modifier: Modifier) {
    Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerCornerButton(
            icon = {
                Icon(
                    Icons.Filled.Event,
                    contentDescription = stringResource(R.string.live_epg),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = actions.onEpgSheetOpen,
        )
        PlayerCornerButton(
            icon = {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.live_settings),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = actions.onSettingsSheetOpen,
        )
    }
}

@Composable
private fun PlayerCornerButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun TimeshiftBar(
    timeshift: LiveTimeshiftUi,
    paused: Boolean,
    onSeek: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onTogglePlay) {
            Icon(
                imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(R.string.player_play_pause),
                tint = Color.White,
            )
        }
        Slider(
            value = timeshift.position.toFloat().coerceIn(0f, max(timeshift.duration, 1).toFloat()),
            onValueChange = onSeek,
            valueRange = 0f..max(timeshift.duration, 1).toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = LiveEpgParser.durationToString(timeshift.position) + " / " + LiveEpgParser.durationToString(timeshift.duration),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ChannelInfoSection(
    info: ChannelInfoUi,
    timeshift: LiveTimeshiftUi,
    overlay: LiveOverlayUi,
) {
    if (info.name.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = info.num.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .marqueeOnOverflow(),
            )
            if (timeshift.isBackState) {
                Text(text = stringResource(R.string.live_replaying), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(text = stringResource(R.string.live_on_air), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (info.sourceText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = info.sourceText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            modifier = Modifier.marqueeOnOverflow(),
            text = info.currentEpgTime + "  " + info.currentEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            modifier = Modifier.marqueeOnOverflow(),
            text = info.nextEpgTime + "  " + info.nextEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (overlay.showTimeOn || overlay.showNetSpeedOn) {
            Spacer(modifier = Modifier.height(2.dp))
            val parts = ArrayList<String>()
            if (overlay.showTimeOn && overlay.timeText.isNotEmpty()) parts.add(overlay.timeText)
            if (overlay.showNetSpeedOn && overlay.netSpeedText.isNotEmpty()) parts.add(overlay.netSpeedText)
            Text(
                text = parts.joinToString("  "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelListSection(
    channelList: LiveChannelListUi,
    onToggleGroup: (Int) -> Unit,
    onSelectChannel: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(channelList.scrollRequestId) {
        var target = -1
        for (i in channelList.rows.indices) {
            val row = channelList.rows[i]
            if (row.channel != null &&
                row.group?.groupIndex == channelList.playingGroupIndex &&
                row.channel.channelIndex == channelList.playingChannelIndex
            ) {
                target = i
                break
            }
        }
        if (target > 0) listState.animateScrollToItem(max(0, target - 2))
    }
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = navBarInset + 24.dp),
    ) {
        itemsIndexed(channelList.rows, key = { _, row -> row.key }) { _, row ->
            val channel = row.channel
            if (channel == null) {
                val group = row.group ?: return@itemsIndexed
                GroupHeaderRow(
                    group = group,
                    expanded = channelList.expandedGroups.contains(group.groupIndex),
                    locked = channelList.lockedGroups.contains(group.groupIndex),
                    onToggle = { onToggleGroup(group.groupIndex) },
                )
            } else {
                val group = row.group
                ChannelRow(
                    row = row,
                    selected = group != null &&
                        group.groupIndex == channelList.playingGroupIndex &&
                        channel.channelIndex == channelList.playingChannelIndex,
                    onSelect = { onSelectChannel(group?.groupIndex ?: 0, row.channelPos) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(
    group: LiveChannelGroup,
    expanded: Boolean,
    locked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.groupName ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.live_need_password),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f),
        )
    }
}

@Composable
private fun ChannelRow(
    row: LiveListRow,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val channel = row.channel ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.cardContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.channelNum.toString(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            modifier = Modifier.marqueeOnOverflow(),
            text = channel.channelName ?: "",
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EpgSheet(
    epg: LiveEpgUi,
    onDismiss: () -> Unit,
    onRowClicked: (Int) -> Boolean,
) {
    val channelNameStr = epg.channelName
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = if (channelNameStr.isEmpty()) {
            stringResource(R.string.live_epg)
        } else {
            stringResource(R.string.live_epg_of, channelNameStr)
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        val epgList = epg.epgList
        if (epgList.isEmpty()) {
            Text(
                text = stringResource(R.string.live_epg_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            return@AVBoxBottomSheet
        }
        val canCatchup = epg.canCatchup
        val now = Date()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(epgList) { index, item ->
                val isNow = item.startdateTime != null && item.enddateTime != null &&
                        !now.before(item.startdateTime) && !now.after(item.enddateTime)
                val clickable = item.startdateTime != null && !now.before(item.startdateTime) &&
                        (canCatchup || (item.enddateTime != null && !now.after(item.enddateTime)))
                val selected = index == epg.lookBackIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = clickable) {
                            if (onRowClicked(index)) dismissAnimated()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.start + "-" + item.end,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = item.title.orEmpty(),
                        fontSize = 14.sp,
                        fontWeight = if (selected || isNow) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            selected -> MaterialTheme.colorScheme.primary
                            isNow -> MaterialTheme.colorScheme.onSurface
                            clickable -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .marqueeOnOverflow(),
                    )
                    when {
                        selected -> Text(
                            text = stringResource(R.string.live_replaying),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        isNow -> Text(
                            text = stringResource(R.string.live_now_playing),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: LiveSettingsUi,
    onItemClick: (Int, Int) -> Unit,
    onItemLongClick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.live_settings),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            settings.groups.forEach { group ->
                item(key = "sg" + group.groupIndex) {
                    SettingsGroup(
                        title = if (group.longPressDelete) {
                            stringResource(R.string.live_group_long_press_delete, group.title)
                        } else {
                            group.title
                        },
                    ) {
                        group.items.forEachIndexed { index, item ->
                            val position = when {
                                group.items.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == group.items.size - 1 -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            }
                            SettingsCard(
                                position = position,
                                color = MaterialTheme.colorScheme.surfaceBright,
                            ) {
                                if (group.switchRow) {
                                    SettingsSwitchRow(
                                        title = item.title,
                                        checked = item.checked,
                                        onCheckedChange = { onItemClick(group.groupIndex, item.itemIndex) },
                                    )
                                } else {
                                    SettingsOptionRow(
                                        title = item.title,
                                        selected = item.selected,
                                        onClick = { onItemClick(group.groupIndex, item.itemIndex) },
                                        onLongClick = if (group.groupIndex == LiveSettingsSnapshot.CONFIG_GROUP_INDEX && item.itemIndex > 0) {
                                            { onItemLongClick(item.itemIndex - 1) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
