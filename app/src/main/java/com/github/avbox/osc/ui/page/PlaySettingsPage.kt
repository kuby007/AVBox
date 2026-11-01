package com.github.avbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.player.BasePlayerView
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.controller.PlayerTextFormat
import com.github.avbox.osc.player.effect.anime4k.Anime4kTier
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.player.thirdparty.PlayerCatalog
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SettingsOptionMenuRow
import com.github.avbox.osc.ui.components.SettingsSliderRow
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.ui.settings.UiSettingsState
import kotlin.math.roundToInt
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

private const val DecodeHard = "硬解码" // i18n: keep
private const val DecodeSoft = "软解码" // i18n: keep

@Composable
fun PlaySettingsScreen(onNavigateBack: () -> Unit) {
    val config = PlaybackSettingsState.config
    val context = LocalContext.current
    var showPrewarmWarning by remember { mutableStateOf(false) }
    var autoLandscape by remember { mutableStateOf(KV.get(KVKeys.PLAY_AUTO_LANDSCAPE, KVKeys.PLAY_AUTO_LANDSCAPE_DEFAULT)) }
    var pipEnabled by remember { mutableStateOf(KV.get(KVKeys.PLAY_PIP_ENABLED, KVKeys.PLAY_PIP_ENABLED_DEFAULT)) }
    var followSystem by remember { mutableStateOf(KV.get(KVKeys.ORIENTATION_FOLLOW_SYSTEM, KVKeys.ORIENTATION_FOLLOW_SYSTEM_DEFAULT)) }
    var sliderPreloadDuration by remember(config.preloadDuration) { mutableStateOf(config.preloadDuration) }
    var sliderCacheSize by remember(config.exoCacheSizeMb) { mutableStateOf(config.exoCacheSizeMb) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_play),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            val playerTypes = PlayerCatalog.all().sortedDescending()
            val currentPlayType = if (playerTypes.contains(config.playType)) config.playType else 2
            SettingsGroup(title = stringResource(R.string.settings_group_play_picture)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_kernel),
                        leadingIconRes = R.drawable.ic_play_kernel,
                        valueText = PlayerCatalog.name(currentPlayType),
                        options = playerTypes.map { PlayerCatalog.name(it) },
                        selectedIndex = playerTypes.indexOf(currentPlayType).coerceAtLeast(0),
                        onSelect = { idx -> PlaybackSettingsState.playType = playerTypes[idx] },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_render),
                        leadingIconRes = R.drawable.ic_play_render,
                        valueText = PlayerTextFormat.renderName(config.playRender),
                        options = listOf("SurfaceView", "TextureView"),
                        selectedIndex = 1 - config.playRender,
                        onSelect = { idx ->
                            val render = 1 - idx
                            if (render == 0 && config.playTunnel) PlaybackSettingsState.playTunnel = false
                            PlaybackSettingsState.playRender = render
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    val scales = listOf(
                        BasePlayerView.SCREEN_SCALE_DEFAULT to stringResource(R.string.common_default),
                        BasePlayerView.SCREEN_SCALE_16_9 to "16:9",
                        BasePlayerView.SCREEN_SCALE_4_3 to "4:3",
                        BasePlayerView.SCREEN_SCALE_MATCH_PARENT to stringResource(R.string.player_scale_fill),
                        BasePlayerView.SCREEN_SCALE_ORIGINAL to stringResource(R.string.player_scale_origin),
                        BasePlayerView.SCREEN_SCALE_CENTER_CROP to stringResource(R.string.player_scale_crop),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_scale),
                        leadingIconRes = R.drawable.ic_play_scale,
                        valueText = PlayerTextFormat.scaleName(config.playScale),
                        options = scales.map { it.second },
                        selectedIndex = scales.indexOfFirst { it.first == config.playScale },
                        onSelect = { idx -> PlaybackSettingsState.playScale = scales[idx].first },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    val codec = config.exoDecode
                    val decodeLabels = listOf(
                        stringResource(R.string.player_decode_hard),
                        stringResource(R.string.player_decode_soft),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_decode),
                        leadingIconRes = R.drawable.ic_play_decode,
                        valueText = when (codec) {
                            DecodeSoft -> decodeLabels[1]
                            DecodeHard -> decodeLabels[0]
                            else -> codec
                        },
                        enabled = currentPlayType == 2,
                        options = decodeLabels,
                        selectedIndex = if (codec == DecodeSoft) 1 else 0,
                        onSelect = { idx -> PlaybackSettingsState.exoDecode = if (idx == 1) DecodeSoft else DecodeHard },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    val tiers = Anime4kTier.entries
                    val labels = tiers.map { stringResource(it.labelRes) }
                    val selected = tiers.indexOfFirst { it.name == config.anime4kTier }.coerceAtLeast(0)
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_anime4k),
                        leadingIconRes = R.drawable.ic_play_anime4k,
                        valueText = labels[selected],
                        enabled = currentPlayType == 2,
                        options = labels,
                        selectedIndex = selected,
                        onSelect = { idx -> PlaybackSettingsState.anime4kTier = tiers[idx].name },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_behavior)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_kernel_prewarm),
                        leadingIconRes = R.drawable.ic_play_prewarm,
                        subtitle = stringResource(R.string.settings_play_kernel_prewarm_subtitle),
                        checked = config.kernelPrewarm,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showPrewarmWarning = true
                            } else {
                                PlaybackSettingsState.kernelPrewarm = false
                                PlaybackService.onPrewarmPreferenceChanged(context, false)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_tunnel),
                        leadingIconRes = R.drawable.ic_play_tunnel,
                        checked = config.playTunnel,
                        onCheckedChange = { checked ->
                            if (checked && config.playRender != 1) PlaybackSettingsState.playRender = 1
                            PlaybackSettingsState.playTunnel = checked
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_prefer_aac),
                        leadingIconRes = R.drawable.ic_play_aac,
                        checked = config.preferAac,
                        onCheckedChange = { PlaybackSettingsState.preferAac = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_music_page),
                        leadingIconRes = R.drawable.ic_music_page,
                        subtitle = stringResource(R.string.settings_music_page_subtitle),
                        checked = UiSettingsState.config.musicAutoOpenPage,
                        onCheckedChange = { UiSettingsState.musicAutoOpenPage = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_auto_landscape),
                        leadingIconRes = R.drawable.ic_player_rotate,
                        subtitle = stringResource(R.string.settings_play_auto_landscape_subtitle),
                        checked = autoLandscape,
                        onCheckedChange = {
                            KV.put(KVKeys.PLAY_AUTO_LANDSCAPE, it)
                            autoLandscape = it
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_pip),
                        leadingIconRes = R.drawable.ic_player_pip,
                        subtitle = stringResource(R.string.settings_play_pip_subtitle),
                        checked = pipEnabled,
                        onCheckedChange = {
                            KV.put(KVKeys.PLAY_PIP_ENABLED, it)
                            pipEnabled = it
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_orientation_follow_system),
                        leadingIconRes = R.drawable.ic_player_rotate,
                        subtitle = stringResource(R.string.settings_orientation_follow_system_subtitle),
                        checked = followSystem,
                        onCheckedChange = {
                            KV.put(KVKeys.ORIENTATION_FOLLOW_SYSTEM, it)
                            followSystem = it
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_preload_cache)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_next_episode),
                        leadingIconRes = R.drawable.ic_preload_next,
                        subtitle = stringResource(R.string.preload_next_episode_subtitle),
                        checked = config.preloadNextEpisode,
                        onCheckedChange = { PlaybackSettingsState.preloadNextEpisode = it },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_duration),
                        leadingIconRes = R.drawable.ic_preload_duration,
                        value = sliderPreloadDuration.toFloat(),
                        valueText = "${sliderPreloadDuration}s",
                        valueRange = 20f..120f,
                        steps = 9,
                        onValueChange = { sliderPreloadDuration = ((it - 20) / 10).roundToInt() * 10 + 20 },
                        onValueChangeFinished = {
                            if (sliderPreloadDuration != config.preloadDuration) {
                                PlaybackSettingsState.preloadDuration = sliderPreloadDuration
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_play_cache),
                        leadingIconRes = R.drawable.ic_play_cache,
                        subtitle = stringResource(R.string.preload_play_cache_subtitle),
                        checked = config.playCache,
                        onCheckedChange = { PlaybackSettingsState.playCache = it },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_cache_size),
                        leadingIconRes = R.drawable.ic_cache_size,
                        value = sliderCacheSize.toFloat(),
                        valueText = if (sliderCacheSize >= 1024) "%.1fGB".format(sliderCacheSize / 1024f) else "${sliderCacheSize}MB",
                        valueRange = 128f..4096f,
                        steps = 30,
                        onValueChange = { sliderCacheSize = ((it - 128) / 128).roundToInt() * 128 + 128 },
                        onValueChangeFinished = {
                            if (sliderCacheSize != config.exoCacheSizeMb) {
                                PlaybackSettingsState.exoCacheSizeMb = sliderCacheSize
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (showPrewarmWarning) {
        AVBoxAlertDialog(
            onDismissRequest = { showPrewarmWarning = false },
            title = { Text(stringResource(R.string.dialog_kernel_prewarm_title)) },
            text = { Text(stringResource(R.string.dialog_kernel_prewarm_message)) },
            dismissButton = {
                val dismiss = LocalSheetDismiss.current
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_cancel))
                }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        PlaybackSettingsState.kernelPrewarm = true
                        PlaybackService.onPrewarmPreferenceChanged(context, true)
                        showPrewarmWarning = false
                    }
                }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_confirm))
                }
            },
        )
    }
}
