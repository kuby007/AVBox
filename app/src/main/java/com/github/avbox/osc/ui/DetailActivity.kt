package com.github.avbox.osc.ui

import com.github.avbox.osc.player.pip.PipLauncher
import com.github.avbox.osc.player.pip.PlaybackForegroundGuard
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.player.PageHost
import com.github.avbox.osc.player.PlaybackController
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.ui.player.PlayContainer
import com.github.avbox.osc.ui.settings.UiSettingsState
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.PermissionHelper
import com.github.avbox.osc.util.SpiderStorageNotice
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import com.github.avbox.osc.ui.WindowSize

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? by mutableStateOf<PlayContainer?>(null)
        private set
    private var fullScreen = false
    private var portraitLockPending = false
    private var appliedFullscreen = false
    private var pendingEpisodeSync = false
    private var videoSizeWatchArmed = false

    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
        try {
            SpiderStorageNotice.suppress()
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_file_picker_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        if (vm.fullScreen.value) super.hideSysBar()
    }

    override fun keepStatusBarHidden(): Boolean = vm.fullScreen.value

    private var statusBarIconsLight = false

    fun setStatusBarIconsLight(light: Boolean) {
        if (statusBarIconsLight == light) return
        statusBarIconsLight = light
        applyStatusBarAppearance()
    }

    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = statusBarIconsLight
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (vm.enteringFullscreen.value) {
                    vm.cancelEntrySlide()
                    container?.stopForExitFullscreen()
                    return
                }
                if (vm.exitingFullscreen.value) return
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    // 横屏播放按返回：先让播放器消费（关闭弹层等）；
                    // 否则直接退出全屏回到详情页，并保持当前横屏，不做任何方向强制
                    if (container != null && container.onBackPressed()) return
                }
                if (vm.fullScreen.value) {
                    if (container != null && container.onBackPressed()) return
                    if (DetailPlaybackPolicy.plan(DetailPlaybackEntry.ExitFullscreen).stopPlayback) {
                        container?.stopForExitFullscreen()
                    }
                    vm.onFullScreenToggleRequested(false, playbackFacts())
                    container?.hideTipOnUiThread()
                } else {
                    if (vm.backToPreviousTarget()) {
                        pendingEpisodeSync = false
                        return
                    }
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        // 「详情页播放」开关由宿主统一读取，覆盖宫格 / 历史 / 收藏 / 第三方脚本等所有入口；
        // 必须在 initFromIntent 之前写入，避免详情数据回来得太快而漏触发
        vm.setEmbeddedOnOpen(KV.get(KVKeys.DETAIL_PAGE_PLAY, KVKeys.DETAIL_PAGE_PLAY_DEFAULT))
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                SheetHostScaffold {
                    DetailScreen(activity = this, vm = vm)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 小窗形状按视频本身的宽高比决定，和当前是横屏还是竖屏播放无关
        PipLauncher.enter(
            this,
            DetailActivity::class.java,
            playContainer?.kernelView,
            isPortraitVideoForPip(),
            false,
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
            playContainer?.uiState()?.clearOverlays()
            playContainer?.hideControllerUiForPip()
        } else if (isFinishing) {
            // 用户关掉小窗：Activity 被系统收掉，播放却在服务里，必须显式停
            stopPlaybackForPipClose()
        } else {
            // 回到全屏：先恢复控制栏，否则旋转 / 画中画 / 锁定三颗侧边按钮全部消失
            playContainer?.restoreControllerUiAfterPip()
            schedulePipExitDecision()
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入小窗不会 onStop；退出 / 关掉小窗才会走到这里。
        // 多数 ROM 关小窗只 stop 不 finish（isFinishing = false），
        // 所以不再依赖它，统一交给宿主判定三种播放页是否都不在前端。
        if (pipWasActive) {
            schedulePipExitDecision()
        }
        pipWasActive = false
    }

    private fun schedulePipExitDecision() {
        PlaybackForegroundGuard.schedulePipExitCheck(
            this,
            Runnable {
                // 小窗仍在（按 Home 自动进入）时不要恢复控制栏
                if (isInPipMode()) {
                    playContainer?.hideControllerUiForPip()
                } else {
                    pipWasActive = false
                    playContainer?.restoreControllerUiAfterPip()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEpisodeSync = false
        vm.onFullScreenToggleRequested(false, playbackFacts())
        vm.dismissEpisodeSheet()
        playContainer?.cancelCastWait()
        playContainer?.endCastPrepare()
        playContainer?.uiState()?.clearOverlays()
        playContainer?.hideTipOnUiThread()
        vm.setEmbeddedOnOpen(KV.get(KVKeys.DETAIL_PAGE_PLAY, KVKeys.DETAIL_PAGE_PLAY_DEFAULT))
        vm.pushTargetFromIntent(intent)
    }

    /** 播放器同一时刻只能挂在一个父容器里：内嵌窗与全屏浮层互相切换前先摘除。 */
    fun detachPlayContainerFromParent() {
        val container = playContainer ?: return
        (container.parent as? android.view.ViewGroup)?.removeView(container)
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.pageHostRef = this
                it.setPreviewMode(true)
                it.qualitySelectedListener = PlayContainer.OnQualitySelectedListener { position -> vm.onQualitySelectionAccepted(position) }
            }
        }
        return requireNotNull(playContainer) { "play container not created" }
    }

    fun preheatWebView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { preheatWebView() }
            return
        }
        if (!webViewPreheated.compareAndSet(false, true)) return
        val startedAt = System.nanoTime()
        try {
            WebView(this).destroy()
            AppLog.i("echo-webview-prewarm: " + (System.nanoTime() - startedAt) / 1_000_000L + "ms")
        } catch (th: Throwable) {
            AppLog.e("DetailActivity", th)
        }
    }

    fun playbackFacts(): DetailPlaybackFacts = DetailPlaybackFacts(
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    override fun showEpisodeSheet() {
        if (vm.vodInfo != null) vm.showEpisodeSheet()
    }

    fun playCurrent() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.endCastPrepare()
        container.clearCastAbort()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
    }

    fun ensurePlaying(): PlayContainer {
        val container = ensurePlayContainer()
        if (!engineOwnsDetailContent()) {
            playCurrent()
        } else {
            container.ensurePlaybackActive()
        }
        return container
    }

    /**
     * 引擎里已经装着内容、但装的是别的影片时（典型场景：在影片 A 的音乐页返回首页，
     * 再打开全新影片 B），不能直接复用，否则音乐页拿到的是 A 的海报与内容。
     */
    private fun engineOwnsDetailContent(): Boolean {
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return false
        val info = vm.vodInfo ?: return false
        return contentMatches(playing, info)
    }

    private fun contentMatches(playing: VodInfo, info: VodInfo): Boolean {
        if (playing.id != info.id) return false
        // 首页复用同一播放器实例时 controller 的 sourceKey 可能取自上次会话，
        // 因此只在本页确实用过该源时才要求源一致，避免把同一影片误判成新内容。
        val expectedSource = vm.firstsourceKey
        return expectedSource.isEmpty()
            || playing.sourceKey == expectedSource
            || vm.sourceKey == expectedSource
    }

    fun openCast() {
        val container = ensurePlayContainer()
        container.cancelCastWait()
        container.beginCastPrepare()
        val session = vm.preparePlaySession()
        if (session == null) {
            container.endCastPrepare()
            Toast.makeText(this, getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        container.setData(session)
        container.beginCastWait()
    }

    fun musicPlaybackDetected(): Boolean {
        if (!UiSettingsState.config.musicAutoOpenPage) return false
        val container = playContainer ?: return false
        val engine = PlaybackService.peek() ?: return false
        if (engine.isReleased() || engine.attachedPage() !== container) return false
        val state = engine.player().playState
        val accepted = state == PlayState.PREPARING ||
            state == PlayState.PREPARED ||
            state == PlayState.BUFFERING ||
            state == PlayState.BUFFERED ||
            state == PlayState.PLAYING
        return accepted && isAudioContent()
    }

    fun isAudioContent(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        val url = controller.webPlayUrl() ?: return false
        return PlaybackController.looksLikeAudioUrl(url) || controller.isAudioOnlyContent()
    }

    fun openMusicPlayer() {
        if (vm.vodInfo == null) {
            Toast.makeText(this, getString(R.string.detail_content_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (isIsoPlayback()) {
            Toast.makeText(this, getString(R.string.player_iso_music_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        // 先按纯音频启动，避免详情页这一帧就把视频解码器建起来（交接后无法回收）。
        prepareAudioOnlyForMusicPage()
        ensurePlaying()
        if (!handOffToMusicPlayer()) {
            Toast.makeText(this, getString(R.string.detail_no_playable_content), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIsoPlayback(): Boolean {
        val controller = PlaybackService.peek()?.controller() ?: return false
        if (!engineOwnsDetailContent()) return false
        return MediaSources.isoHeaders(controller.webHeaderMap()) || controller.isIsoEscalated()
    }

    private fun prepareAudioOnlyForMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(true)
    }

    private fun restoreVideoAfterMusicPage() {
        PlaybackService.peek()?.controller()?.setMusicAudioOnly(false)
    }

    fun handOffToMusicPlayer(): Boolean {
        val container = playContainer ?: run {
            restoreVideoAfterMusicPage()
            return false
        }
        val playing = PlaybackService.peek()?.controller()?.vod()
        if (playing == null) {
            restoreVideoAfterMusicPage()
            return false
        }
        // 引擎里若还装着别的影片，先切到本页内容，避免音乐页显示上一部影片。
        val info = vm.vodInfo
        if (info != null && !contentMatches(playing, info)) {
            AppLog.i("echo-music handoff switch content: " + playing.id + " -> " + info.id)
            restoreVideoAfterMusicPage()
            playCurrent()
        }
        prepareAudioOnlyForMusicPage()
        val keepDetailPage = !isAudioContent()
        container.setExitingPreview(true)
        container.handOverToNextPage()
        MusicPlayerActivity.start(this, vm.firstsourceKey)
        if (keepDetailPage) pendingEpisodeSync = true else finish()
        return true
    }

    private fun syncEpisodeAfterMusicPage() {
        if (!pendingEpisodeSync) return
        pendingEpisodeSync = false
        vm.syncEpisodeAfterMusicReturn()
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        fullScreen = full
        if (full) {
            portraitLockPending = false
            requestedOrientation = orientationPolicyValue()
        } else {
            // 退出全屏不做任何方向强制：横屏播完回详情页仍是横屏，竖屏播完仍是竖屏
            portraitLockPending = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (full) {
            armVideoSizeWatch()
            hideSysBar()
        } else {
            videoSizeWatchArmed = false
            playContainer?.videoSizeReadyListener = null
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            applyHideStatusBarPref()
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    private fun armVideoSizeWatch() {
        val container = playContainer ?: return
        if (container.hasVideoSize()) {
            registerPipParams()
            return
        }
        if (videoSizeWatchArmed) return
        videoSizeWatchArmed = true
        container.videoSizeReadyListener = { onVideoSizeReady() }
    }

    private fun onVideoSizeReady() {
        if (!videoSizeWatchArmed) return
        videoSizeWatchArmed = false
        playContainer?.videoSizeReadyListener = null
        registerPipParams()
    }

    /** 视频宽高已知后向系统登记小窗参数，Android 12+ 才能自动进小窗 */
    private fun registerPipParams() {
        PipLauncher.registerAutoEnter(
            this,
            DetailActivity::class.java,
            playContainer?.kernelView,
            isPortraitVideoForPip(),
            false,
        )
    }

    private fun isPortraitVideoForPip(): Boolean {
        val container = playContainer
        if (container != null && container.hasVideoSize()) return container.isPortraitVideo()
        return resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    override fun orientationPolicyValue(): Int {
        if (portraitLockPending) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        return super.orientationPolicyValue()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (portraitLockPending &&
            newConfig.orientation != Configuration.ORIENTATION_PORTRAIT
        ) {
            // 转竖屏完成前不要松锁，否则会被传感器重新带回横屏
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        settleRotationAfterExit()
    }

    private fun lockPortrait() {
        portraitLockPending = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    /**
     * 手动点播放器旋转按钮时解除竖屏锁定，
     * 否则 onConfigurationChanged 会把刚转过去的横屏又拉回竖屏。
     */
    fun clearPortraitLock() {
        portraitLockPending = false
    }

    fun settleRotationAfterExit() {
        if (!vm.rotating.value) return
        vm.settleRotation()
        syncFullBoxSideEffects()
    }

    fun setPlayerTouchBlocked(blocked: Boolean) {
        playContainer?.touchBlocked = blocked
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return DetailFullScreenFrame.playerFullScreen(vm.rotating.value, vm.fullScreen.value, landNow)
    }

    private fun syncFullBoxSideEffects() {
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
        playContainer?.hostResume()
        syncEpisodeAfterMusicPage()
        val container = playContainer ?: return
        vm.applyPlaybackEntry(
            DetailPlaybackEntry.MusicReturn,
            resumable = container.hasClaimedPlayback() && !container.ownsEngineContent() && container.isPlaybackActive(),
        )
    }

    override fun onPause() {
        // 进入画中画时系统同样会回调 onPause，此时绝不能暂停播放，
        // 否则小窗里是一张静止画面。只有真正切到后台/锁屏才暂停。
        if (!isInPipMode()) playContainer?.hostPause()
        super.onPause()
    }

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onDestroy() {
        PlaybackForegroundGuard.cancel(this)
        playContainer?.cancelCastWait()
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }

    companion object {
        private val webViewPreheated = AtomicBoolean(false)
    }
}
