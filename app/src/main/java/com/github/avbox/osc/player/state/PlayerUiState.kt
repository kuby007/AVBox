package com.github.avbox.osc.player.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.bean.SourceSubtitle
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.bean.CastVideo
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.effect.PictureEffectUnavailableReason
import com.github.avbox.osc.player.effect.PicturePreset
import com.github.avbox.osc.player.effect.PictureProfile

class PlayerUiState {

    var playState: PlayState by mutableStateOf(PlayState.IDLE)
        private set
    var playerState: Int by mutableStateOf(ScreenMode.PLAYER_NORMAL)
        private set
    var duration: Int by mutableStateOf(0)
        private set
    var position: Int by mutableStateOf(0)
        private set
    var bufferedPercent: Int by mutableStateOf(0)
        private set
    var locked: Boolean by mutableStateOf(false)
        private set

    var controlsVisible: Boolean by mutableStateOf(false)
        private set
    var dragging: Boolean by mutableStateOf(false)
        private set
    var seekPreviewPositionMs: Long by mutableStateOf(0L)
        private set

    var seekHintVisible: Boolean by mutableStateOf(false)
        private set
    var seekHintForward: Boolean by mutableStateOf(true)
        private set
    var seekHintText: String by mutableStateOf("")
        private set

    var slideHintVisible: Boolean by mutableStateOf(false)
        private set
    var slideHintText: String by mutableStateOf("")
        private set
    var slideHintBrightness: Boolean by mutableStateOf(true)
        private set

    var speedBoostVisible: Boolean by mutableStateOf(false)
        private set
    var speedBoostValue: Float by mutableStateOf(3.0f)
        private set

    var title: String by mutableStateOf("")
        private set
    var videoSize: String by mutableStateOf(VideoSizeGate.UNKNOWN)
        private set
    var videoRect: VideoRect by mutableStateOf(VideoRect(0, 0, 0, 0))
        private set
    var videoQuality: String by mutableStateOf("")
        private set
    var sysTime: String by mutableStateOf("")
        private set
    var batteryPercent: Int by mutableStateOf(-1)
        private set
    var batteryCharging: Boolean by mutableStateOf(false)
        private set
    var netSpeedTopRight: String by mutableStateOf("")
        private set
    var netSpeedCenter: String by mutableStateOf("")
        private set

    var topLeftVisible: Boolean by mutableStateOf(false)
        private set
    var topRightVisible: Boolean by mutableStateOf(false)
        private set
    var sysTimeVisible: Boolean by mutableStateOf(false)
        private set
    var netSpeedTopRightVisible: Boolean by mutableStateOf(false)
        private set

    var backVisible: Boolean by mutableStateOf(false)
        private set
    var lockState: LockVisibility by mutableStateOf(LockVisibility.GONE)
        private set

    var infoOsdVisible: Boolean by mutableStateOf(false)
        private set
    var infoOsdLeft: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set
    var infoOsdRight: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set
    var infoOsdFooter: Pair<String, String>? by mutableStateOf(null)
        private set
    var showParseRow: Boolean by mutableStateOf(false)
        private set
    var isPortrait: Boolean by mutableStateOf(true)
        private set
    var playerType: Int by mutableStateOf(2)
        private set
    var liveButtonsVisible: Boolean by mutableStateOf(true)
        private set
    var danmuOpen: Boolean by mutableStateOf(false)
        private set
    var danmuSearchAvailable: Boolean by mutableStateOf(false)
        private set
    var sessionVod: VodInfo? by mutableStateOf(null)
        private set
    var previewMode: Boolean by mutableStateOf(false)
        private set
    var timeStartText: String by mutableStateOf("")
        private set
    var timeEndText: String by mutableStateOf("")
        private set
    var parseListVersion: Int by mutableStateOf(0)
        private set

    var selectDialog: SelectDialogState? by mutableStateOf(null)
        private set

    var paramsSheet: ParamsSheetState? by mutableStateOf(null)
        private set

    var paramsTab: ParamsTab by mutableStateOf(ParamsTab.Playback)
        private set

    fun openSelectDialog(dialog: SelectDialogState) {
        selectDialog = dialog
    }

    fun dismissSelectDialog() {
        selectDialog = null
    }

    fun openParamsSheet(sheet: ParamsSheetState?) {
        paramsSheet = sheet
    }

    fun dismissParamsSheet() {
        paramsSheet = null
    }

    fun selectParamsTab(tab: ParamsTab) {
        paramsTab = tab
    }

    var tipMsg: String by mutableStateOf("")
        private set
    var tipLoading: Boolean by mutableStateOf(false)
        private set
    var tipErr: Boolean by mutableStateOf(false)
        private set

    val tipVisible: Boolean get() = tipLoading || tipErr

    fun applyTip(msg: String, loading: Boolean, err: Boolean) {
        tipMsg = msg
        tipLoading = loading
        tipErr = err
    }

    var danmuSettingSheet: DanmuSettingSheetState? by mutableStateOf(null)
        private set
    var danmuSearchSheet: DanmuSearchSheetState? by mutableStateOf(null)
        private set
    var subtitleSheet: SubtitleSheetState? by mutableStateOf(null)
        private set
    var subtitleSearchSheet: SubtitleSearchSheetState? by mutableStateOf(null)
        private set
    var castSheet: CastSheetState? by mutableStateOf(null)
        private set
    var episodeSheetOpen: Boolean by mutableStateOf(false)

    fun openDanmuSettingSheet(sheet: DanmuSettingSheetState) {
        danmuSettingSheet = sheet
    }

    fun dismissDanmuSettingSheet() {
        danmuSettingSheet = null
    }

    fun openDanmuSearchSheet(sheet: DanmuSearchSheetState) {
        danmuSearchSheet = sheet
    }

    fun dismissDanmuSearchSheet() {
        danmuSearchSheet = null
    }

    fun openSubtitleSheet(sheet: SubtitleSheetState) {
        subtitleSheet = sheet
    }

    fun dismissSubtitleSheet() {
        subtitleSheet = null
    }

    fun openSubtitleSearchSheet(sheet: SubtitleSearchSheetState) {
        subtitleSearchSheet = sheet
    }

    fun dismissSubtitleSearchSheet() {
        subtitleSearchSheet = null
    }

    fun openCastSheet(sheet: CastSheetState) {
        castSheet = sheet
    }

    fun dismissCastSheet() {
        castSheet = null
    }

    fun clearOverlays() {
        selectDialog = null
        paramsSheet = null
        danmuSettingSheet = null
        danmuSearchSheet = null
        subtitleSheet = null
        subtitleSearchSheet = null
        castSheet = null
        episodeSheetOpen = false
    }

    val overlayPanelOpen: Boolean
        get() = selectDialog != null || paramsSheet != null || danmuSettingSheet != null ||
                danmuSearchSheet != null || subtitleSheet != null || subtitleSearchSheet != null ||
                castSheet != null || episodeSheetOpen

    val danmuBtnVisible: Boolean get() = danmuOpen

    val episodeBtnVisible: Boolean
        get() {
            val vod = sessionVod ?: return false
            val flag = vod.playFlag ?: return false
            return (vod.seriesMap?.get(flag)?.size ?: 0) > 1
        }

    var lifecyclePaused: Boolean by mutableStateOf(false)
        private set

    var canChangePosition: Boolean by mutableStateOf(true)
        private set

    var enableInNormal: Boolean by mutableStateOf(false)
        private set

    var gestureSwitch: Boolean by mutableStateOf(true)
        private set

    var speedOld: Float by mutableStateOf(1.0f)
        private set

    var isClickBackBtn: Boolean by mutableStateOf(false)
        private set

    var exitPaused: Boolean by mutableStateOf(false)
        private set

    val playbackActive: Boolean
        get() = lifecyclePaused ||
                playState == PlayState.PLAYING ||
                playState == PlayState.BUFFERING ||
                playState == PlayState.BUFFERED

    val pauseOverlayVisible: Boolean
        get() = playState == PlayState.PAUSED && !controlsVisible && !lifecyclePaused && !exitPaused

    val seekPreviewOrPosition: Int
        get() = if (dragging && duration > 0) seekPreviewPositionMs.toInt().coerceIn(0, duration) else position

    val loadingVisible: Boolean
        get() = playState == PlayState.PREPARING || playState == PlayState.BUFFERING

    val centerControlsVisible: Boolean
        get() = controlsVisible && !loadingVisible && !tipVisible && !locked

    val netSpeedCenterVisible: Boolean
        get() = playState == PlayState.IDLE && !previewMode

    fun updateDragging(value: Boolean) {
        dragging = value
    }

    fun updateLockState(value: LockVisibility) {
        lockState = value
    }

    fun updateLocked(value: Boolean) {
        locked = value
    }

    fun updatePosition(value: Int) {
        position = value
    }

    fun updateSysTimeVisible(value: Boolean) {
        sysTimeVisible = value
    }

    fun updateVideoSize(value: String) {
        videoSize = value
    }

    fun updateExitPaused(value: Boolean) {
        exitPaused = value
    }

    fun updateSeekPreviewPositionMs(value: Long) {
        seekPreviewPositionMs = value
    }

    fun updateBackVisible(value: Boolean) {
        backVisible = value
    }

    fun updateSpeedBoostVisible(value: Boolean) {
        speedBoostVisible = value
    }

    fun updateTopLeftVisible(value: Boolean) {
        topLeftVisible = value
    }

    fun updateControlsVisible(value: Boolean) {
        controlsVisible = value
    }

    fun updateSpeedOld(value: Float) {
        speedOld = value
    }

    fun updateNetSpeedTopRightVisible(value: Boolean) {
        netSpeedTopRightVisible = value
    }

    fun updateDuration(value: Int) {
        duration = value
    }

    fun updateSlideHintVisible(value: Boolean) {
        slideHintVisible = value
    }

    fun updateTopRightVisible(value: Boolean) {
        topRightVisible = value
    }

    fun updateIsPortrait(value: Boolean) {
        isPortrait = value
    }

    fun updateIsClickBackBtn(value: Boolean) {
        isClickBackBtn = value
    }

    fun updateSeekHintVisible(value: Boolean) {
        seekHintVisible = value
    }

    fun updateNetSpeedCenter(value: String) {
        netSpeedCenter = value
    }

    fun updateInfoOsdVisible(value: Boolean) {
        infoOsdVisible = value
    }

    fun updateSlideHintText(value: String) {
        slideHintText = value
    }

    fun updateSeekHintForward(value: Boolean) {
        seekHintForward = value
    }

    fun updateDanmuOpen(value: Boolean) {
        danmuOpen = value
    }

    fun updateBatteryCharging(value: Boolean) {
        batteryCharging = value
    }

    fun updatePlayState(value: PlayState) {
        playState = value
    }

    fun updateNetSpeedTopRight(value: String) {
        netSpeedTopRight = value
    }

    fun updateSlideHintBrightness(value: Boolean) {
        slideHintBrightness = value
    }

    fun updateTitle(value: String) {
        title = value
    }

    fun updateSysTime(value: String) {
        sysTime = value
    }

    fun updateSessionVod(value: VodInfo?) {
        sessionVod = value
    }

    fun updateLiveButtonsVisible(value: Boolean) {
        liveButtonsVisible = value
    }

    fun updatePlayerState(value: Int) {
        playerState = value
    }

    fun updateInfoOsdLeft(value: List<Pair<String, String>>) {
        infoOsdLeft = value
    }

    fun updateCanChangePosition(value: Boolean) {
        canChangePosition = value
    }

    fun updateGestureSwitch(value: Boolean) {
        gestureSwitch = value
    }

    fun updateLifecyclePaused(value: Boolean) {
        lifecyclePaused = value
    }

    fun updatePlayerType(value: Int) {
        playerType = value
    }

    fun updateShowParseRow(value: Boolean) {
        showParseRow = value
    }

    fun updateBatteryPercent(value: Int) {
        batteryPercent = value
    }

    fun updateEnableInNormal(value: Boolean) {
        enableInNormal = value
    }

    fun updateInfoOsdFooter(value: Pair<String, String>?) {
        infoOsdFooter = value
    }

    fun updatePreviewMode(value: Boolean) {
        previewMode = value
    }

    fun updateDanmuSearchAvailable(value: Boolean) {
        danmuSearchAvailable = value
    }

    fun updateTimeStartText(value: String) {
        timeStartText = value
    }

    fun updateVideoQuality(value: String) {
        videoQuality = value
    }

    fun updateSeekHintText(value: String) {
        seekHintText = value
    }

    fun updateInfoOsdRight(value: List<Pair<String, String>>) {
        infoOsdRight = value
    }

    fun updateTimeEndText(value: String) {
        timeEndText = value
    }

    fun updateBufferedPercent(value: Int) {
        bufferedPercent = value
    }

    fun updateVideoRect(value: VideoRect) {
        videoRect = value
    }

    fun updateSpeedBoostValue(value: Float) {
        speedBoostValue = value
    }

    fun updateParseListVersion(value: Int) {
        parseListVersion = value
    }
}

enum class LockVisibility { GONE, HIDDEN, SHOWN }

data class VideoRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val empty: Boolean get() = width <= 0 || height <= 0
}

enum class ParamsTab { Playback, Picture }

class SelectDialogState(
    val tip: String,
    val items: List<String>,
    val defaultIndex: Int,
    val onSelected: (Int) -> Unit,
)

class ParamsChoice(
    val options: List<String>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

class ParamsSheetState(
    val speed: ParamsChoice,
    val player: ParamsChoice,
    val scale: ParamsChoice,
    val picture: PictureParamsState,
    val timeStartText: String,
    val timeEndText: String,
    val onSetTimeStart: () -> Unit,
    val onSetTimeEnd: () -> Unit,
    val onResetTime: () -> Unit,
    val onSearchDanmu: (() -> Unit)?,
)

class PictureParamsState(
    val preset: PicturePreset,
    val tuning: PictureProfile,
    val unavailableReason: PictureEffectUnavailableReason,
    val anime4kTierText: String,
    val anime4kEnabled: Boolean,
    val anime4kUnavailable: Boolean,
    val anime4kSharpen: Float,
    val anime4kDeblur: Boolean,
    val onAnime4kToggled: (Boolean) -> Unit,
    val onAnime4kSharpenChanged: (Float) -> Unit,
    val onAnime4kDeblurToggled: (Boolean) -> Unit,
    val onPresetSelected: (PicturePreset) -> Unit,
    val onTuningChanged: (PictureProfile) -> Unit,
    val onReset: () -> Unit,
    val onCompareChanged: (Boolean) -> Unit,
)

class DanmuSettingSheetState(
    val onOpenSearch: () -> Unit,
    val onReset: () -> Unit = {},
    val onSettingsApplied: (Boolean) -> Unit = {},
)

class DanmuSearchSheetState(
    val episode: String,
    val searchWord: String,
    val onLoad: (String) -> Unit,
)

class SubtitleSheetState(
    val exoInternal: Boolean,
    val hasInternal: Boolean,
    val onSelectInternal: () -> Unit,
    val onSelectLocal: () -> Unit,
    val onSelectRemote: () -> Unit,
    val onSelectStyle: (Int) -> Unit = {},
    val onTextSizeChange: () -> Unit = {},
    val onReset: () -> Unit = {},
)

class SubtitleSearchSheetState(
    val searchWord: String,
    val onLoadSubtitle: (SourceSubtitle, String) -> Unit,
)

class CastSheetState(
    val video: CastVideo,
    val onCastSuccess: () -> Unit,
)

interface PlayerActions {
    fun toggleControls()
    fun keepControlsAlive()

    fun onNextClicked()
    fun onPreClicked()
    fun onPlayPauseClicked()
    fun onScaleClicked()
    fun onScaleLongClicked()
    fun onSpeedClicked()
    fun onSpeedLongClicked()
    fun onPlayerClicked()
    fun onPlayerLongClicked()
    fun onTimeStartClicked()
    fun onTimeStartLongClicked()
    fun onTimeEndClicked()
    fun onTimeEndLongClicked()
    fun onTimeResetClicked()
    fun onEpisodeClicked()
    fun onCastClicked()
    fun onSubtitleClicked()
    fun onSubtitleLongClicked()
    fun onAudioTrackClicked()
    fun onVideoTrackClicked()
    fun onDanmuSettingClicked()
    fun onDanmuSettingLongClicked()
    fun onDanmuSearchClicked()
    fun onDanmuSearchLongClicked()
    fun onRotateClicked()
    fun onPipClicked()
    fun onParamsClicked()
    fun onInfoOsdClicked()
    fun onBackClicked()
    fun onLockClicked()

    fun onParseSelected(position: Int)

    fun onSeekStarted()
    fun onSeekPreview(progress: Int)
    fun onSeekFinished(progress: Int)
    fun onSeekCancelled()
    fun onSeekStep(dir: Int)

    fun onSeekRelative(deltaMs: Long)

    fun refreshSystemInfo()

    fun hideSeekHint()
    fun hideSlideHint()
}
