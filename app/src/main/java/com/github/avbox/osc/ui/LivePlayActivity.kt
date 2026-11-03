@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui

import com.github.avbox.osc.player.pip.PipLauncher
import com.github.avbox.osc.player.pip.PlaybackForegroundGuard
import com.github.avbox.osc.receiver.PipActionReceiver
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.bean.EpgInfo
import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.bean.LiveSettingGroup
import com.github.avbox.osc.player.KernelDecision
import com.github.avbox.osc.player.KernelReusePolicy
import com.github.avbox.osc.player.LivePlayerManager
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.controller.ComposeLiveController
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.settings.LiveSettingsState
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LivePlayActivity : BaseActivity(), PipActionReceiver.PipLiveSwitch {

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    private val vm: LivePlayViewModel by viewModels()

    private val uiState: StateFlow<LivePlayUiState> get() = vm.state

    private val fullScreen: Boolean get() = vm.state.value.frame.fullScreen
    private val currentChannelGroupIndex: Int get() = vm.state.value.channelList.playingGroupIndex
    private val currentLiveChannelIndex: Int get() = vm.state.value.channelList.playingChannelIndex
    private val currentLiveChannelItem: LiveChannelItem? get() = vm.state.value.channelList.playingChannel
    private val selectedChannelGroupIndex: Int get() = vm.state.value.channelList.tappedGroupIndex
    private var kernelView: PlayerKernelView? = null
    private var liveController: ComposeLiveController? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var sourceSwitchJob: Job? = null
    private val livePlayerManager = LivePlayerManager()
    private var exitingLivePlay = false
    private var nowday = Date()

    private val epgController = LiveEpgController(object : LiveEpgController.Host {
        override fun currentChannel(): LiveChannelItem? = currentLiveChannelItem

        override fun currentChannelHasLogo(): Boolean = !vm.state.value.epg.logoUrl.isNullOrEmpty()

        override fun onEpgListChanged(list: ArrayList<EpgInfo>) {
            vm.setEpgList(list)
        }

        override fun onEpgSettled() {
            overlay.updateChannelInfoUi()
        }
    })

    private val liveHost = object :
        LiveOverlayController.Host,
        LiveCatchupController.Host,
        LiveChannelSourceLoader.Host {

        override fun text(resId: Int, vararg args: Any): String = getString(resId, *args)

        override fun videoView(): PlayerKernelView? = kernelView

        override fun cachedEpg(channelName: String): List<EpgInfo>? = epgController.cachedEpg(channelName)

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun liveChannelHeader(): HashMap<String, String>? = this@LivePlayActivity.liveChannelHeader()

        override fun updateLogoUrl(url: String?) {
            vm.updateLogoUrl(url)
        }

        override fun loadEpgAfterChannelStarted() {
            epgController.loadAfterChannelStarted()
        }

        override fun initPlayer(view: PlayerKernelView) {
            livePlayerManager.init(view)
        }

        override fun initCatchup() {
            catchupController.initLiveObj()
        }

        override fun cancelEpgPending() {
            epgController.cancelPending()
        }

        override fun cancelSourceSwitch() = cancelSourceSwitchCountdown()

        override fun isNeedInputPassword(groupIndex: Int): Boolean = this@LivePlayActivity.isNeedInputPassword(groupIndex)

        override fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
            this@LivePlayActivity.selectChannelGroup(groupIndex, liveChannelIndex)
        }

        override fun initLiveSettingGroupList() {
            this@LivePlayActivity.initLiveSettingGroupList()
        }

        override fun onSettingsInputsChanged() {
            this@LivePlayActivity.onSettingsInputsChanged()
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    internal lateinit var overlay: LiveOverlayController

    internal lateinit var catchupController: LiveCatchupController

    internal lateinit var channelSourceLoader: LiveChannelSourceLoader

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (fullScreen) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = fullScreen

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        vm.attachSettingsSource(settingSource)
        overlay = LiveOverlayController(vm, liveHost, handler, lifecycleScope)
        catchupController = LiveCatchupController(vm, liveHost, overlay)
        channelSourceLoader = LiveChannelSourceLoader(vm, liveHost, overlay)
        syncLandscapeNow()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    vm.state.value.epg.sheetVisible -> vm.hideEpgSheet()
                    vm.state.value.settings.sheetVisible -> vm.hideSettingsSheet()
                    fullScreen -> applyFullscreen(false)
                    vm.state.value.timeshift.isBackState -> catchupController.backToLiveFromEpg()
                    else -> {
                        exitingLivePlay = true
                        finish()
                    }
                }
            }
        })
        epgController.reloadAddress()
        nowday = Date()
        epgController.epgDayPresented = FORMAT_DATE1.format(nowday)
        initVideoView()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    val state by uiState.collectAsStateWithLifecycle()
                    LiveScreen(state = state, actions = remember { liveActions() })
                }
            }
        }
        channelSourceLoader.initLiveChannelList()
        initLiveSettingGroupList()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        exitingLivePlay = false
        val takenOverByVod = PlaybackService.peek()?.enterLiveState() ?: false
        rebindLiveControllerIfNeeded()
        if (takenOverByVod) {
            replayCurrentChannelAfterTakeover()
        } else {
            kernelView?.resume()
        }
        // 回到前台重新登记小窗参数，供 Android 12+ 自动进入使用
        registerPipParams()
    }

    override fun onPause() {
        super.onPause()
        // 画中画期间系统同样回调 onPause，此时要继续播，不能停
        val pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!exitingLivePlay && !pip) kernelView?.pause()
    }

    override fun onDestroy() {
        PlaybackForegroundGuard.cancel(this)
        super.onDestroy()
        overlay.hideSwitchChannelSnapshot()
        PlaybackService.peek()?.exitLive()
        kernelView = null
        handler.removeCallbacksAndMessages(null)
        epgController.cancelAll()
        channelSourceLoader.cancelAll()
    }

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.listener = liveControlListener
        liveController = controller
        val view = PlaybackService.engine(this).also { it.enterLive() }.player()
        view.setVideoController(controller)
        view.setProgressSink(null)
        kernelView = view
    }

    private fun rebindLiveControllerIfNeeded() {
        val view = kernelView ?: return
        val controller = liveController ?: return
        if (view.videoController !== controller) {
            view.setVideoController(controller)
            AppLog.i("echo-p4 re-bind live controller")
        }
    }

    private fun hideControllerUiForPip() {
        vm.setOverlayVisible(false)
        vm.setFullscreen(true)
    }

    private fun registerPipParams() {
        PipLauncher.registerAutoEnter(
            this,
            LivePlayActivity::class.java,
            kernelView,
            isPortraitVideoForPip(),
            true,
        )
    }

    private fun isPortraitVideoForPip(): Boolean {
        val view = kernelView
        if (view != null && view.hasVideoSizeForPip()) return view.isPortraitVideo()
        return resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    override fun pipSwitchChannel(direction: Int) {
        val pos = nextChannel(direction)
        selectChannelGroup(pos[0], pos[1])
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 直播源多数是横比例，仍按视频本身比例决定，取不到才退回屏幕方向
        PipLauncher.enter(
            this,
            LivePlayActivity::class.java,
            kernelView,
            isPortraitVideoForPip(),
            true,
        )
    }

    private var pipWasActive = false
    private var pipCloseHandled = false

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            pipWasActive = true
            hideControllerUiForPip()
        } else if (isFinishing) {
            stopPlaybackForPipClose()
        } else {
            vm.setOverlayVisible(true)
            vm.setFullscreen(false)
            schedulePipExitDecision()
        }
    }

    override fun onStop() {
        super.onStop()
        // 关掉小窗时系统往往只 stop 不 finish，不能再用 isFinishing 兜底。
        if (pipWasActive) {
            schedulePipExitDecision()
        }
        pipWasActive = false
    }

    private fun schedulePipExitDecision() {
        PlaybackForegroundGuard.schedulePipExitCheck(
            this,
            Runnable {
                // 小窗仍在时保持收起，不误弹控制层
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                    hideControllerUiForPip()
                } else {
                    pipWasActive = false
                    vm.setOverlayVisible(true)
                    vm.setFullscreen(false)
                }
            },
            Runnable { stopPlaybackForPipClose() },
        )
    }

    private fun stopPlaybackForPipClose() {
        if (pipCloseHandled) return
        pipCloseHandled = true
        pipWasActive = false
        PlaybackForegroundGuard.cancel(this)
        PlaybackService.stopForPipClose(this)
    }

    private fun replayCurrentChannelAfterTakeover() {
        val item = currentLiveChannelItem ?: return
        val videoView = kernelView ?: return
        vm.setLookBackIndex(-1)
        vm.setShiyi(false)
        vm.setTimeshiftBack(false)
        vm.setOverlayVisible(false)
        overlay.stopTimeshiftTicker()
        overlay.hideSwitchChannelSnapshot()
        videoView.setUrl(item.urlAt(vm.currentSourceIndex()), liveChannelHeader())
        videoView.start()
        overlay.showResolutionAfterChannelSwitch()
        catchupController.loadEpgAfterChannelStarted()
    }

    internal fun releasePlayerKernel() {
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) eng.releasePlayer()
        else kernelView?.release()
    }

    private val liveControlListener = object : ComposeLiveController.LiveControlListener {
        override fun onSingleTap(): Boolean {
            if (fullScreen) {
                if (vm.toggleOverlay()) overlay.scheduleOverlayHide()
            } else {
                applyFullscreen(true)
            }
            return true
        }

        override fun onLongPress() {
            if (vm.state.value.timeshift.isBackState) {
                vm.setOverlayVisible(true)
                overlay.scheduleOverlayHide()
            } else {
                openSettingsSheet()
            }
        }

        override fun onPlayStateChanged(playState: PlayState) {
            vm.onPlayStateChanged(playState)
            handleAutoSourceSwitch(playState)
        }

        override fun onHorizontalFling(direction: Int) {
            if (direction > 0) playNext() else playPrevious()
        }

        override fun onGesturePercent(isBrightness: Boolean, percent: Int) {
            overlay.showGestureHint(isBrightness, percent)
        }
    }

    private fun handleAutoSourceSwitch(state: PlayState) {
        cancelSourceSwitchCountdown()
        when (state) {
            PlayState.IDLE, PlayState.PAUSED -> {}
            PlayState.PREPARED, PlayState.BUFFERED, PlayState.PLAYING -> {
                overlay.onPlaybackStarted()
                vm.resetSourceSwitchAttempts()
            }
            PlayState.ERROR, PlayState.COMPLETED -> {
                overlay.hideSwitchChannelSnapshot()
                startSourceSwitchCountdown(CONNECT_TIMEOUT_SWITCH_DELAY)
            }
            PlayState.PREPARING, PlayState.BUFFERING -> {
                startSourceSwitchCountdown((KV.get(KVKeys.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L)
            }
            else -> AppLog.i("echo-Unexpected live_play state: $state")
        }
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        vm.setRotating(full != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
        vm.setFullscreen(full)
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            orientationPolicyValue()
        }
        if (full) {
            vm.setOverlayVisible(true)
            overlay.scheduleOverlayHide()
            super.hideSysBar()
        } else {
            vm.setOverlayVisible(false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.setRotating(false)
        syncLandscapeNow()
        applyStatusBarAppearance()
    }

    fun isFullBox(): Boolean = vm.state.value.frame.isFullBox

    private fun syncLandscapeNow() {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        vm.setLandscapeNow(landNow)
    }

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem?.sourceNum == 1)
        ) {
            return true
        }
        val groupChannels = channelSourceLoader.getLiveChannels(channelGroupIndex)
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && kernelView?.isPlaying == true
        val previousLivePlayerType = livePlayerManager.livePlayerType
        if (!changeSource) {
            vm.setPlayingGroupIndex(channelGroupIndex)
            vm.setPlayingChannelIndex(liveChannelIndex)
            vm.setPlayingChannel(channelSourceLoader.getLiveChannels(currentChannelGroupIndex)?.get(currentLiveChannelIndex))
            vm.updateSourceIndex(vm.consumePendingSourceIndex())
            KV.put(KVKeys.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
            requestChannelScroll()
        }
        vm.setEpgChannelName(currentLiveChannelItem?.channelName.orEmpty())
        onSettingsInputsChanged()
        vm.setLookBackIndex(-1)
        vm.setShiyi(false)
        vm.setTimeshiftBack(false)
        vm.setOverlayVisible(false)
        overlay.stopTimeshiftTicker()
        val item = currentLiveChannelItem ?: return false
        item.include_back = canCurrentChannelCatchup()
        vm.setCanCatchup(item.include_back)
        overlay.updateChannelInfoUi()
        val videoView = kernelView
        if (videoView != null) {
            val rebuildKernel = videoView.consumeKernelRebuildRequired()
            val reusePlayer = KernelReusePolicy.decide(videoView.mediaPlayer != null, rebuildKernel, false,
                    canReusePlayer(previousLivePlayerType)) == KernelDecision.REUSE
            val keepExoFrame = reusePlayer
            if (showPreviousFrame && !keepExoFrame) {
                overlay.showSwitchChannelSnapshot()
            } else {
                overlay.hideSwitchChannelSnapshot()
            }
            val liveUrl = item.urlAt(vm.currentSourceIndex())
            if (reusePlayer) {
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.replay(true)
            } else {
                releasePlayerKernel()
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.start()
            }
            overlay.showResolutionAfterChannelSwitch()
        }
        catchupController.loadEpgAfterChannelStarted()
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val videoView = kernelView ?: return false
        return videoView.playState != PlayState.IDLE &&
                previousLivePlayerType == livePlayerManager.livePlayerType
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val next = nextChannel(1)
        playChannel(next[0], next[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val next = nextChannel(-1)
        playChannel(next[0], next[1], false)
    }

    private fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        val item = currentLiveChannelItem ?: return
        val num = item.sourceNum
        val next = if (num > 0) (vm.currentSourceIndex() + 1) % num else vm.currentSourceIndex() + 1
        vm.updateSourceIndex(next)
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    private fun startSourceSwitchCountdown(delayMs: Long) {
        sourceSwitchJob?.cancel()
        sourceSwitchJob = lifecycleScope.launch {
            delay(delayMs)
            val attempts = vm.bumpSourceSwitchAttempts()
            if (currentLiveChannelItem?.sourceNum == attempts) {
                vm.resetSourceSwitchAttempts()
                val next = nextChannel(if (LiveSettingsState.config.channelReverse) -1 else 1)
                playChannel(next[0], next[1], false)
            } else {
                playNextSource()
            }
        }
    }

    private fun cancelSourceSwitchCountdown() {
        sourceSwitchJob?.cancel()
        sourceSwitchJob = null
    }

    private fun nextChannel(direction: Int): IntArray {
        return LiveChannelNavigator.nextPosition(
            groups = vm.channelGroups(),
            currentGroupIndex = currentChannelGroupIndex,
            currentChannelIndex = currentLiveChannelIndex,
            direction = direction,
            crossGroup = LiveSettingsState.config.crossGroup,
            channelsOf = { groupIndex -> channelSourceLoader.getLiveChannels(groupIndex) },
        )
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(this, getString(R.string.live_please_select_channel), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    internal fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
        vm.setTappedGroupIndex(groupIndex)
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, liveChannelIndex)
            return
        }
        if (liveChannelIndex > -1) {
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
        } else {
            expandChannelGroup(groupIndex)
        }
    }

    fun toggleChannelGroup(groupIndex: Int) {
        if (isChannelGroupExpanded(groupIndex)) {
            collapseChannelGroup(groupIndex)
            return
        }
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        expandChannelGroup(groupIndex)
    }

    internal fun isChannelGroupExpanded(groupIndex: Int): Boolean =
        vm.state.value.channelList.expandedGroups.contains(groupIndex)

    internal fun expandChannelGroup(groupIndex: Int) {
        if (isChannelGroupExpanded(groupIndex)) return
        vm.expandChannelGroup(groupIndex)
    }

    private fun collapseChannelGroup(groupIndex: Int) {
        vm.collapseChannelGroup(groupIndex)
    }

    private fun requestChannelScroll() {
        vm.requestChannelScroll()
    }

    fun selectChannel(groupIndex: Int, position: Int) {
        vm.setTappedGroupIndex(groupIndex)
        clickLiveChannel(position)
    }

    private fun clickLiveChannel(position: Int) {
        playChannel(selectedChannelGroupIndex, position, false)
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        vm.setTappedGroupIndex(groupIndex)
        expandChannelGroup(groupIndex)
        requestChannelScroll()
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        vm.updatePasswordDialogTarget(groupIndex to liveChannelIndex)
    }

    internal fun onPasswordConfirmed(password: String) {
        val target = vm.state.value.passwordDialogTarget ?: return
        vm.updatePasswordDialogTarget(null)
        val groupIndex = target.first
        if (password == vm.channelGroups().getOrNull(groupIndex)?.groupPassword) {
            vm.confirmPasswordGroup(groupIndex)
            loadChannelGroupDataAndPlay(groupIndex, target.second)
        } else {
            Toast.makeText(this, getString(R.string.live_wrong_password), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun isNeedInputPassword(groupIndex: Int): Boolean {
        val group = vm.channelGroups().getOrNull(groupIndex) ?: return false
        return group.groupPassword.orEmpty().isNotEmpty() && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean =
        vm.state.value.channelList.confirmedPasswordGroups.contains(groupIndex)

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? =
        channelSourceLoader.getLiveChannels(groupIndex)

    internal fun loadLiveConfigOnEnter() = channelSourceLoader.loadLiveConfigOnEnter()

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) =
        channelSourceLoader.setEmptyLiveChannelList(releasePlayer)

    internal fun initLiveSettingGroupList() {
        vm.updateSettingGroups(ApiConfig.get().liveSettingGroupList)
    }

    private fun loadCurrentSourceList() {
        vm.updateCurrentSourceItems(
            LiveSettingsRules.sourceItems(currentLiveChannelItem?.channelSourceNames)
        )
    }

    internal fun openSettingsSheet() {
        LiveSettingsState.reload()
        ApiConfig.get().refreshLiveApiHistoryItems()
        initLiveSettingGroupList()
        loadCurrentSourceList()
        vm.onSettingsOpened()
    }

    internal fun removeLiveConfigHistory(itemIndex: Int) {
        vm.onSettingRemoved(itemIndex, settingHost)
    }

    internal fun onSettingsInputsChanged() {
        LiveSettingsState.reload()
        vm.onSettingsInputsChanged()
    }

    private val settingSource = object : LiveSettingsSource {
        override fun settingGroups(): List<LiveSettingGroup> = vm.settingGroups()

        override fun playerScale(): Int = livePlayerManager.livePlayerScale

        override fun playerType(): Int = livePlayerManager.livePlayerType

        override fun connectTimeoutIndex(): Int = LiveSettingsState.config.connectTimeoutIndex

        override fun switchChecked(position: Int): Boolean = when (position) {
            0 -> LiveSettingsState.config.showTime
            1 -> LiveSettingsState.config.showNetSpeed
            2 -> LiveSettingsState.config.channelReverse
            3 -> LiveSettingsState.config.crossGroup
            else -> false
        }

        override fun liveGroupIndex(): Int = ApiConfig.liveGroupIndex

        override fun configIndex(): Int = LiveSettingsRules.currentConfigIndex(
            ApiConfig.isLiveFollowVod(),
            ApiConfig.get().liveConfigUrls(),
            KV.get(KVKeys.LIVE_API_URL, ""),
        )

        override fun isLiveApiLineMode(): Boolean = ApiConfig.get().isLiveApiLineMode()
    }

    internal fun clickSettingItem(groupIndex: Int, position: Int) {
        vm.onSettingClicked(groupIndex, position, settingHost)
    }

    private val settingHost = object : LivePlayViewModel.Host {
        override fun currentChannelItem(): LiveChannelItem? = currentLiveChannelItem

        override fun currentPlayerScale(): Int = livePlayerManager.livePlayerScale

        override fun currentPlayerType(): Int = livePlayerManager.livePlayerType

        override fun replayCurrentChannel() {
            playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
        }

        override fun applyPlayerScale(position: Int) {
            kernelView?.let { livePlayerManager.changeLivePlayerScale(it, position) }
        }

        override fun applyPlayerType(position: Int) {
            val videoView = kernelView ?: return
            releasePlayerKernel()
            livePlayerManager.changeLivePlayerType(videoView, position)
            currentLiveChannelItem?.let { videoView.setUrl(it.urlAt(vm.currentSourceIndex()), liveChannelHeader()) }
            videoView.start()
        }

        override fun releasePlayerKernel() {
            this@LivePlayActivity.releasePlayerKernel()
        }

        override fun refreshChannelListAndPlay(channelName: String?, sourceIndex: Int) {
            channelSourceLoader.refreshLiveChannelListAndPlay(channelName, sourceIndex)
        }

        override fun setEmptyChannelList(releasePlayer: Boolean) {
            setEmptyLiveChannelList(releasePlayer)
        }

        override fun removeConfigHistory(itemIndex: Int) {
            if (ApiConfig.get().isLiveApiLineMode()) {
                Toast.makeText(
                    this@LivePlayActivity,
                    getString(R.string.live_repo_entry_not_deletable),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val history = KV.get(KVKeys.LIVE_API_HISTORY, ArrayList<String>())
            if (itemIndex < 0 || itemIndex >= history.size) return
            if (history[itemIndex] == KV.get(KVKeys.LIVE_API_URL, "")) {
                Toast.makeText(
                    this@LivePlayActivity,
                    getString(R.string.live_active_config_not_deletable),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            history.removeAt(itemIndex)
            KV.put(KVKeys.LIVE_API_HISTORY, history)
            ApiConfig.get().refreshLiveApiHistoryItems()
            initLiveSettingGroupList()
            loadCurrentSourceList()
            onSettingsInputsChanged()
            Toast.makeText(
                this@LivePlayActivity,
                getString(R.string.toast_removed_from_history),
                Toast.LENGTH_SHORT,
            ).show()
        }

        override fun toast(msg: String) {
            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
        }

        override fun isFinishing(): Boolean = this@LivePlayActivity.isFinishing
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return KV.get(KVKeys.LIVE_WEB_HEADER)
    }

    internal fun liveChannelHeader(): HashMap<String, String>? {
        val item = currentLiveChannelItem ?: return liveWebHeader()
        val header = HashMap<String, String>()
        liveWebHeader()?.let { header.putAll(it) }
        item.headers?.let { header.putAll(it) }
        if (item.channelFormat.orEmpty().isNotEmpty()) {
            header[MediaSources.HEADER_FORMAT] = item.channelFormat.orEmpty()
        }
        return if (header.isEmpty()) null else header
    }

    internal fun onEpgRowClicked(position: Int): Boolean = catchupController.onEpgRowClicked(position)

    fun onTimeshiftSeek(progress: Float) {
        overlay.onTimeshiftSeek(progress)
    }

    fun onTimeshiftTogglePlay() {
        overlay.onTimeshiftTogglePlay()
    }

    internal fun canCurrentChannelCatchup(): Boolean = catchupController.canCurrentChannelCatchup()

    private fun liveActions(): LivePlayActions = LivePlayActions(
        page = LivePageActions(
            onRetryLoad = { loadLiveConfigOnEnter() },
            onPasswordConfirm = { onPasswordConfirmed(it) },
            onPasswordDismiss = {
                vm.updatePasswordDialogTarget(null)
                vm.pendingSourceIndex = 0
            },
        ),
        player = LivePlayerActions(
            videoView = { kernelView },
            onExitFullscreen = { applyFullscreen(false) },
            onEpgSheetOpen = { vm.showEpgSheet() },
            onSettingsSheetOpen = { openSettingsSheet() },
            onTimeshiftSeek = { onTimeshiftSeek(it) },
            onTimeshiftTogglePlay = { onTimeshiftTogglePlay() },
        ),
        epg = LiveEpgActions(
            onDismiss = { vm.hideEpgSheet() },
            onRowClicked = { onEpgRowClicked(it) },
        ),
        list = LiveChannelListActions(
            onToggleGroup = { toggleChannelGroup(it) },
            onSelectChannel = { groupIndex, position -> selectChannel(groupIndex, position) },
        ),
        settings = LiveSettingsActions(
            onDismiss = { vm.hideSettingsSheet() },
            onItemClick = { groupIndex, itemIndex -> clickSettingItem(groupIndex, itemIndex) },
            onItemLongClick = { removeLiveConfigHistory(it) },
        ),
    )

}
