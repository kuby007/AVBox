package com.github.avbox.osc.ui.player

import com.github.avbox.osc.player.state.LockVisibility
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.CastVideo
import com.github.avbox.osc.player.ExoPlayer
import com.github.avbox.osc.player.PlayerKernelView
import com.github.avbox.osc.player.PageHost
import com.github.avbox.osc.player.PlaybackController
import com.github.avbox.osc.player.PlaybackEngine
import com.github.avbox.osc.player.PlaybackHostApi
import com.github.avbox.osc.player.PlaybackPage
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.PlaybackSession
import com.github.avbox.osc.player.PlaybackViewBridge
import com.github.avbox.osc.player.PreloadCoordinator
import com.github.avbox.osc.player.VideoOrientation
import com.github.avbox.osc.player.controller.VideoPlayerController
import com.github.avbox.osc.player.core.DecodeDecision
import com.github.avbox.osc.player.engine.MediaSources
import com.github.avbox.osc.player.controller.PlayerControlApi
import com.github.avbox.osc.player.danmu.DanmuLoadController
import com.github.avbox.osc.player.state.CastSheetState
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.AppLog
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CAST_URL_WAIT_MS = 5_000L

class PlayContainer(activity: Activity) : FrameLayout(activity), CustomAdapt, PlaybackHostApi, PlaybackPage {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var castWaitJob: Job? = null

    private val trackSelector: TrackSelectorDelegate = TrackSelectorDelegate(object : TrackSelectorDelegate.Host {
        override fun player(): PlayerKernelView? = kernelView

        override fun context(): Context = hostContext

        override fun uiState(): PlayerUiState = playerController.getUiState()
    })

    lateinit var scheduler: PlaybackController
    private lateinit var surfaceSlot: FrameLayout
    internal var engine: PlaybackEngine? = null
    var pageHostRef: PageHost? = null
    var attachedActivity: Activity? = activity

    private val hostContext: Context = activity

    private val viewBridge: PlaybackViewBridge = PlayContainerViewBridge(this)

    private val controlListener: PlayContainerControlListener = PlayContainerControlListener(this)

    private val overlays: PlayContainerOverlays = PlayContainerOverlays(this)

    private val subtitles: PlayContainerSubtitles = PlayContainerSubtitles(this)

    var qualitySelectedListener: OnQualitySelectedListener? = null

    var videoSizeReadyListener: ((Boolean) -> Unit)? = null

    var touchBlocked = false

    private var ownedPlaybackKey: String? = null
    private var handedOver: Boolean = false
    private var hostSuspended: Boolean = false

    var kernelView: PlayerKernelView? = null
    lateinit var playerController: PlayerControlApi
    internal var handler: Handler? = null
    var previewExiting: Boolean = false
    var danmuLoadController: DanmuLoadController? = null
    private val videoDuration: Long = -1

    init {
        val e = PlaybackService.engine(activity)
        engine = e
        scheduler = e.controller()
        AutoSize.autoConvertDensity(activity, getSizeInDp(), isBaseOnWidth())
        LayoutInflater.from(activity).inflate(R.layout.view_play_container, this, true)
        init()
        scheduler.attachViewBridge(viewBridge)
        engine?.attach(this)
    }

    override fun viewBridge(): PlaybackViewBridge {
        return viewBridge
    }

    override fun renderSlot(): ViewGroup {
        return surfaceSlot
    }

    override fun onServiceStopped() {
        kernelView = null
        engine = null
    }

    fun isAttached(): Boolean {
        val host = pageHostRef
        if (host != null) return host.isPageAlive()
        return attachedActivity?.isFinishing == false
    }

    fun setEpisodeSheetOpen(open: Boolean) {
        if (playerController != null) playerController.getUiState().episodeSheetOpen = open
    }

    internal fun uiState(): PlayerUiState = playerController.getUiState()

    fun interface OnQualitySelectedListener {
        fun onQualitySelected(position: Int)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        touchBlocked || super.onInterceptTouchEvent(ev)

    private fun notifyVideoSizeReady(portraitVideo: Boolean) {
        val deliver = Runnable {
            val listener = videoSizeReadyListener
            videoSizeReadyListener = null
            listener?.invoke(portraitVideo)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else post(deliver)
    }

    override fun hostResume() {
        previewExiting = false
        val resumeFromLifecycle = playerController != null && playerController.getUiState().lifecyclePaused
        if (playerController != null) playerController.setLifecyclePaused(false)
        AppLog.i(
            "echo-p2 hostResume claimed=" + hasClaimedPlayback()
                + " owns=" + ownsEngineContent()
                + " playing=" + (kernelView?.isPlaying ?: false)
                + " lifecyclePaused=" + resumeFromLifecycle
                + " suspended=" + hostSuspended
                + " handedOver=" + handedOver,
        )
        val resumedFromBackground = hostSuspended && !handedOver
        hostSuspended = false
        reattachIfOwnedByOther()
        if (resumedFromBackground) rebuildRenderViewAfterBackground()
        engine?.consumeServiceLostKeep(ownsEngineContent())
        val view = kernelView
        if (view != null && resumeFromLifecycle) {
            if (ownsEngineContent()) {
                view.resume()
            }
        }
    }

    private fun rebuildRenderViewAfterBackground() {
        val view = kernelView ?: return
        if (view.mediaPlayer == null) return
        if (engine?.attachedPage() !== this) return
        if (TextUtils.isEmpty(scheduler.startedPlaybackKey())) return
        val cfg = scheduler.playerCfg() ?: return
        if (cfg.optInt("pr", 1) != 1) return
        view.rebuildRenderView(1)
        AppLog.i("echo-p2 rebuild render view after background")
    }

    private fun reattachIfOwnedByOther() {
        handedOver = false
        if (surfaceSlot == null) return
        val e = engine ?: return
        if (e.attachedPage() === this) return
        if (e.isReleased()) return
        if (e.isLiveMode()) e.exitLive()
        e.attach(this)
        val view = kernelView
        if (!ownsEngineContent() && view != null) {
            view.saveCurrentProgress()
        }
        if (view != null && playerController != null) {
            playerController.setKernelProvider(view)
            val state = view.playState
            if (view.mediaPlayer != null
                && state != PlayState.IDLE && state != PlayState.ERROR
                && ownsEngineContent()
            ) {
                rebindPlaybackOverlay()
            }
        }
        if (ownsEngineContent()) {
            syncSessionVod()
        }
        AppLog.i("echo-p4 re-attach after live/other page")
    }

    override fun hostPause() {
        AppLog.i(
            "echo-p2 hostPause playing=" + (kernelView?.isPlaying ?: false)
                + " exiting=" + previewExiting,
        )
        hostSuspended = true
        val view = kernelView
        if (view != null && !previewExiting && !scheduler.isConfirmedAudioOnly()) {
            if (playerController != null) playerController.setLifecyclePaused(view.isPlaying)
            view.pause()
        }
    }

    fun ensurePlaybackActive() {
        clearCastAbort()
        val view = kernelView ?: return
        if (view.isPlaying) return
        when (view.playState) {
            PlayState.IDLE, PlayState.COMPLETED, PlayState.ERROR -> play(true)
            PlayState.PREPARING, PlayState.START_ABORT -> Unit
            else -> view.resume()
        }
    }

    fun handOverToNextPage() {
        val e = engine ?: return
        handedOver = true
        e.detachForHandover(this)
    }

    override fun hostDestroy() {
        AppLog.i("echo-music destroy: hostDestroy enter")
        endCastPrepare()
        qualitySelectedListener = null
        videoSizeReadyListener = null
        if (!handedOver) engine?.detach(this)
        overlays.cancelPreloadToast()
        danmuLoadController?.destroy()
        danmuLoadController = null
        kernelView = null
        if (playerController != null) {
            playerController.getUiState().clearOverlays()
            playerController.stopOther()
        }
        attachedActivity = null
        AppLog.i("echo-music destroy: hostDestroy done")
    }

    override fun getSizeInDp(): Float {
        return if (attachedActivity is CustomAdapt) (attachedActivity as CustomAdapt).getSizeInDp() else 0f
    }

    override fun isBaseOnWidth(): Boolean {
        return attachedActivity !is CustomAdapt || (attachedActivity as CustomAdapt).isBaseOnWidth()
    }

    private fun init() {
        initView()
        overlays.initDanmuView()
    }

    fun applyDanmuSettings(reload: Boolean) = overlays.applyDanmuSettings(reload)

    fun checkDanmu(danmu: String?, callback: DanmuLoadController.LoadCallback?) =
        overlays.checkDanmu(danmu, callback)

    fun startDanmuIfReady() = overlays.startDanmuIfReady()

    fun resetDanmuState() = overlays.resetDanmuState()

    fun reloadDanmuForPlayback() = overlays.reloadDanmuForPlayback()

    private fun initView() {
        handler = Handler { msg ->
            when (msg.what) {
                MSG_PARSE_TIMEOUT -> {
                    scheduler.stopParse()
                    overlays.errorWithRetry(hostContext.getString(R.string.player_error_sniff), false)
                }
            }
            false
        }
        surfaceSlot = findViewById(R.id.surfaceSlot)
        val activity = attachedActivity ?: return
        val controller = VideoPlayerController(activity)
        controller.setPlayerContent { PlayerSurfaceHost(controller) }
        playerController = controller
        controller.onVideoSizeReady = { width, height ->
            if (VideoOrientation.isUsableSize(width, height)) {
                notifyVideoSizeReady(VideoOrientation.isPortrait(width, height))
            }
        }

        playerController.getLyricView().setTextSize(if (playerController.getUiState().previewMode) 16f else 24f)
        playerController.setCanChangePosition(true)
        playerController.setEnableInNormal(true)
        playerController.setGestureEnabled(true)
        kernelView = engine?.player()
        engine?.let { (playerController as? VideoPlayerController)?.bindVideoCodecFlow(it.videoCodec) }
        playerController.setListener(controlListener)
        if (kernelView != null) playerController.setKernelProvider(kernelView)
    }

    override fun showCast() {
        showCastDialog()
    }

    fun showCastDialog() {
        if (MediaSources.isoHeaders(scheduler.webHeaderMap())) {
            AppLog.i("echo-iso-cast-blocked")
            Toast.makeText(hostContext, hostContext.getString(R.string.player_iso_cast_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        val webUrl = scheduler.webPlayUrl()
        if (TextUtils.isEmpty(webUrl)) {
            Toast.makeText(hostContext, hostContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAttached()) return
        val castUrl = scheduler.getCastUrl(webUrl)
        if (castUrl.isNullOrEmpty()) {
            Toast.makeText(hostContext, hostContext.getString(R.string.toast_no_cast_url), Toast.LENGTH_SHORT).show()
            return
        }
        val headers: HashMap<String, String>? = scheduler.webHeaderMap()?.let { HashMap(it) }
        val title = castTitle()
        scope.launch {
            val video = CastVideo(castUrl, title, headers, castPosition())
            val uiState = playerController.getUiState()
            uiState.openCastSheet(CastSheetState(video) {
                kernelView?.pause()
            })
        }
    }

    fun openDanmuSearchSheet() = overlays.openDanmuSearchSheet()

    fun beginCastPrepare() {
        scheduler.beginCastPrepare()
    }

    fun beginCastWait() {
        cancelCastWait()
        castWaitJob = scope.launch {
            withTimeoutOrNull(CAST_URL_WAIT_MS) {
                scheduler.webPlayUrlFlow.first { !it.isNullOrEmpty() }
            }
            endCastPrepare()
            showCast()
        }
    }

    fun cancelCastWait() {
        castWaitJob?.cancel()
        castWaitJob = null
    }

    fun clearCastAbort() {
        scheduler.clearCastAbort()
    }

    fun endCastPrepare() {
        if (!scheduler.isCastPrepareOnly()) return
        scheduler.closeCastPrepare()
        scheduler.stopParse()
        scheduler.cancelPlayTimeout()
        AppLog.i("echo-cast prepare end")
    }

    internal fun syncSessionVod() {
        if (playerController == null || scheduler == null) return
        playerController.getUiState().updateSessionVod(scheduler.vod())
    }

    private fun castTitle(): String {
        val vod = scheduler.vod() ?: return "TVBox"
        val series = vod.seriesMap?.get(vod.playFlag)?.getOrNull(vod.playIndex)
        if (series != null) return vod.name + " " + series.name
        val name = vod.name
        return if (name.isNullOrEmpty()) "TVBox" else name
    }

    private suspend fun castPosition(): Long {
        val live = try {
            kernelView?.currentPosition ?: 0L
        } catch (e: Exception) {
            0L
        }
        if (live > 0) return live
        return try {
            scheduler.getSavedProgress(scheduler.progressKey())
        } catch (e: Exception) {
            0L
        }
    }

    fun setSubtitle(path: String?) = subtitles.setSubtitle(path)

    fun selectMySubtitle() = subtitles.selectMySubtitle()

    fun setSubtitleViewTextStyle(style: Int) = subtitles.setSubtitleViewTextStyle(style)

    fun selectMyInternalSubtitle() = subtitles.selectMyInternalSubtitle()

    override fun onLocalSubtitlePicked(uri: Uri) = subtitles.onLocalSubtitlePicked(uri)

    fun selectMyAudioTrack() {
        trackSelector.selectAudioTrack()
    }

    fun selectMyVideoTrack() {
        trackSelector.selectVideoTrack()
    }

    fun setTip(msg: String, loading: Boolean, err: Boolean) = overlays.setTip(msg, loading, err)

    fun hideTip() = overlays.hideTip()

    fun hideTipOnUiThread() = overlays.hideTipOnUiThread()

    fun hideControllerUiForPip() {
        val state = playerController.getUiState()
        state.clearOverlays()
        state.updateLockState(LockVisibility.GONE)
    }

    fun showPreloadReady() = overlays.showPreloadReady()

    fun hidePreloadReady() = overlays.hidePreloadReady()

    fun errorWithRetry(err: String, finish: Boolean, playbackError: Boolean = false) =
        overlays.errorWithRetry(err, finish, playbackError)

    fun initSubtitleView() = subtitles.initSubtitleView()

    fun closeSubtitles() = subtitles.closeSubtitles()

    fun trackMemoryKey(): String = subtitles.trackMemoryKey()

    private fun rebindPlaybackOverlay() {
        subtitles.initSubtitleView()
        overlays.checkDanmu(scheduler.playDanmu())
    }

    fun clearLyricView() = subtitles.clearLyricView()

    fun releasePlayerKernel() {
        val e = engine
        val view = kernelView
        if (e != null) {
            e.releasePlayer()
        } else if (view != null) {
            view.release()
        }
    }

    fun reviveEngineIfReleased(): Boolean {
        if (engine?.isReleased() == false) return false
        val activity = attachedActivity
        if (activity == null || surfaceSlot == null) return false
        if (scheduler != null) scheduler.stopPlaybackForPageExit()
        val revived = PlaybackService.engine(activity)
        engine = revived
        (playerController as? VideoPlayerController)?.bindVideoCodecFlow(revived.videoCodec)
        scheduler = revived.controller()
        kernelView = revived.player()
        revived.attach(this)
        handedOver = false
        if (kernelView != null) {
            playerController.setKernelProvider(kernelView)
            danmuLoadController?.setVideoView(kernelView)
        }
        AppLog.i("echo-p2 revive engine after release")
        return true
    }

    override fun play(reset: Boolean) {
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    override fun selectQuality(position: Int): Boolean {
        val accepted = scheduler != null && scheduler.selectQuality(position)
        if (accepted) qualitySelectedListener?.onQualitySelected(position)
        return accepted
    }

    override fun setData(session: PlaybackSession) {
        val current = engine
        if (current == null || current.isReleased()) {
            if (!reviveEngineIfReleased()) {
                AppLog.i("echo-p5 setData skipped: engine released")
                return
            }
        }
        val engineRef = engine ?: return
        AppLog.i(
            "echo-p2 setData: key=" + session.playbackKey()
                + " sameOwned=" + (!scheduler.isCastPrepareOnly() && isSamePlaybackOwned(session))
                + " mediaPlayer=" + (kernelView?.mediaPlayer != null)
                + " state=" + kernelView?.playState,
        )
        if (!scheduler.isCastPrepareOnly() && isSamePlaybackOwned(session)) {
            AppLog.i("echo-p3 take over same playback: " + session.playbackKey())
            engineRef.setData(session)
            syncSessionVod()
            playerController.setPlayerConfig(requireNotNull(scheduler.playerCfg()) { "player cfg missing on takeover" })
            scheduler.onSessionTakenOver(session.userPickedLine())
            scheduler.publishTitle()
            scheduler.clearTriedLines()
            rebindPlaybackOverlay()
            ownedPlaybackKey = session.playbackKey()
            if (playerController != null) playerController.adoptCurrentContent()
            if (alignInstanceConfigOnTakeover()) return
            val view = kernelView
            if (view != null && !view.isPlaying) view.start()
            return
        }
        val sameVodSwitch = !scheduler.isCastPrepareOnly() && isSameVodEpisodeSwitch(session)
        kernelView?.forgetVideoSize()
        engineRef.setData(session)
        syncSessionVod()
        playerController.setPlayerConfig(requireNotNull(scheduler.playerCfg()) { "player cfg missing on setData" })
        scheduler.clearTriedLines()
        ownedPlaybackKey = if (scheduler.isCastPrepareOnly()) null else session.playbackKey()
        scheduler.onNewPlayArmed(session.userPickedLine(), sameVodSwitch)
        playViaScheduler(false)
    }

    private fun isSameVodEpisodeSwitch(session: PlaybackSession): Boolean {
        val view = kernelView
        if (scheduler == null || view == null || view.mediaPlayer == null) return false
        val started = scheduler.startedPlaybackKey() ?: return false
        return started.startsWith(session.vodKey())
    }

    fun playViaScheduler(reset: Boolean) {
        reviveEngineIfReleased()
        scheduler.play(reset)
    }

    fun replayCurrentAddress() {
        clearCastAbort()
        overlays.reloadDanmuForPlayback()
        val url = scheduler.webPlayUrl()
        if (url != null && !url.isEmpty()) {
            scheduler.stopParse()
            scheduler.initParseLoadFound()
            kernelView?.saveCurrentProgress()
            if (!scheduler.isCrossContentReuseAllowed()) releasePlayerKernel()
            scheduler.goPlayUrl(url, scheduler.webHeaderMap())
        } else {
            playViaScheduler(false)
        }
    }

    private fun alignInstanceConfigOnTakeover(): Boolean {
        val view = kernelView
        if (view == null || scheduler == null) return false
        val cfg = scheduler.playerCfg() ?: return false
        view.setScreenScaleType(cfg.optInt("sc", 0))
        if (cfg.optInt("pl", 2) >= 10) return false
        // 基准必须用「配置里期望的渲染类型」，不能用渲染视图工厂：音乐页的
        // switchRenderToTexture 会把工厂改成 Texture，退出音乐页时工厂与配置不一致，
        // 会据此误判成渲染类型变更而白白重建内核。
        val renderChanged = !scheduler.isConfirmedAudioOnly()
            && view.isSurfaceRenderMismatch(cfg.optInt("pr", 1))
        val decodeChanged = !DecodeDecision.isExoDecodeApplied(cfg)
        if (!renderChanged && !decodeChanged) return false
        AppLog.i(
            if (renderChanged) "echo-render-changed: rebuild kernel on takeover"
            else "echo-exo-decode-changed: rebuild kernel on takeover",
        )
        scheduler.beginNewPlay()
        controlListener.replay(false)
        return true
    }

    fun hasClaimedPlayback(): Boolean {
        return !TextUtils.isEmpty(ownedPlaybackKey)
    }

    fun ownsEngineContent(): Boolean {
        if (!hasClaimedPlayback()) return false
        return TextUtils.equals(ownedPlaybackKey, scheduler?.startedPlaybackKey())
    }

    fun isPlaybackActive(): Boolean {
        val view = kernelView ?: return false
        return when (view.playState) {
            PlayState.PREPARING,
            PlayState.PREPARED,
            PlayState.PLAYING,
            PlayState.BUFFERING,
            PlayState.BUFFERED -> true

            else -> false
        }
    }

    private fun isSamePlaybackOwned(session: PlaybackSession): Boolean {
        val view = kernelView
        if (HistoryHelper.isIncognito() && (view == null || !view.isPlaying)) return false
        if (!TextUtils.equals(scheduler.startedPlaybackKey(), session.playbackKey())) return false
        val e = engine ?: return false
        if (e.isLiveMode()) return false
        if (view == null || view.mediaPlayer == null) return false
        val state = view.playState
        return state != PlayState.ERROR && state != PlayState.IDLE
    }

    override fun onBackPressed(): Boolean {
        return playerController.onBackPressed()
    }

    fun isPortraitVideo(): Boolean {
        return kernelView?.isPortraitVideo() == true
    }

    fun hasVideoSize(): Boolean {
        val size = kernelView?.videoSize ?: return false
        return size.size >= 2 && size[0] > 0 && size[1] > 0
    }

    override fun setExitingPreview(exitingPreview: Boolean) {
        previewExiting = exitingPreview
    }

    override fun resumeFromMediaSession() {
        val view = kernelView ?: return
        view.start()
        scheduler.updateMusicSession()
    }

    override fun pauseFromMediaSession() {
        val view = kernelView ?: return
        view.pause()
        scheduler.updateMusicSession()
    }

    override fun stopFromMediaSession() {
        kernelView?.pause()
        scheduler.stopMusicSession()
    }

    override fun seekFromMediaSession(position: Long) {
        val view = kernelView ?: return
        view.seekTo(position)
        scheduler.updateMusicSession()
    }

    override fun playNext(isProgress: Boolean) {
        reviveEngineIfReleased()
        scheduler.playAdjacentEpisode(true)
    }

    override fun playPrevious() {
        reviveEngineIfReleased()
        scheduler.playAdjacentEpisode(false)
    }

    override fun setPlayTitle(show: Boolean) {
        if (!show) {
            playerController.setTitle("")
            return
        }
        val vod = scheduler.vod()
        val vs = if (vod == null) null else scheduler.currentSeries(vod.playFlag, vod.playIndex)
        playerController.setTitle(if (vod == null) "" else if (vs == null) vod.name ?: "" else vod.name + " " + vs.name)
    }

    fun buildPreloadSnapshot(): PreloadCoordinator.Snapshot? {
        try {
            val vod = scheduler.vod()
            val seriesMap = vod?.seriesMap
            if (vod == null || seriesMap == null) return null
            val episodes = seriesMap[vod.playFlag]
            if (episodes == null || vod.playIndex < 0 || vod.playIndex + 1 >= episodes.size) return null
            val next = episodes[vod.playIndex + 1]
            if (next == null || TextUtils.isEmpty(next.url)) return null
            val nextIndex = vod.playIndex + 1
            val nextKey = vod.sourceKey + vod.id + vod.playFlag + nextIndex + next.name
            val nextSubtKey = vod.sourceKey + "-" + vod.id + "-" + vod.playFlag + "-" + nextIndex + "-" + next.name + "-subt"
            val cfg = scheduler.playerCfg()
            val startSkipMs = if (cfg == null) 0L else cfg.optInt("st", 0) * 1000L
            val mediaPlayer = kernelView?.mediaPlayer
            val exoKernel = mediaPlayer is ExoPlayer
            return PreloadCoordinator.Snapshot(
                hostContext,
                scheduler.sourceKey(),
                vod.playFlag,
                scheduler.progressKey(),
                nextKey,
                next.url,
                nextSubtKey,
                startSkipMs,
                exoKernel,
            )
        } catch (th: Throwable) {
            AppLog.i("echo-preload-skip: snapshot error " + th)
            return null
        }
    }

    override fun setPreviewMode(previewMode: Boolean) {
        if (playerController != null) {
            playerController.setPreviewMode(previewMode)
            playerController.getLyricView().setTextSize(if (previewMode) 16f else 24f)
            subtitles.applySubtitleTextSize()
        }
    }

    override fun toggleControllerControls() {
        if (playerController != null) {
            playerController.toggleControlBar()
        }
    }

    override fun stopForSourceSwitch(tip: String) {
        endCastPrepare()
        val view = kernelView ?: return
        val position = view.currentPosition
        scheduler.beginSourceSwitch(position)
        view.pause()
        if (scheduler.isCrossContentReuseAllowed()) {
            view.saveCurrentProgress()
            AppLog.i("echo-switchSource keep player kernel for reuse")
        } else {
            releasePlayerKernel()
        }
        if (playerController != null) playerController.stopOther()
        overlays.resetDanmuState()
        AppLog.i("echo-switchSource stop at " + position + "ms, key=" + scheduler.progressKey())
        if (!TextUtils.isEmpty(tip)) setTip(tip, true, false)
    }

    override fun clearSourceSwitchTip() {
        if (!scheduler.isSwitchStopPending()) return
        hideTipOnUiThread()
    }

    fun stopForContentSwitch() {
        endCastPrepare()
        val view = kernelView ?: return
        if (!ownsEngineContent()) return
        scheduler.cancelInFlight()
        view.pause()
        view.saveCurrentProgress()
        view.stopPlaybackKeepPlayer()
    }

    fun stopForExitFullscreen() {
        val view = kernelView ?: return
        if (!hasClaimedPlayback()) return
        scheduler.cancelInFlight()
        if (playerController != null) playerController.setExitPaused(true)
        view.pause()
        view.saveCurrentProgress()
        view.stopPlaybackKeepPlayer()
        scheduler.stopMusicSession()
        ownedPlaybackKey = null
    }

    inner class MyWebView(context: Context) : WebView(context) {

        override fun setOverScrollMode(mode: Int) {
            super.setOverScrollMode(mode)
            if (hostContext is Activity) {
                AutoSize.autoConvertDensityOfCustomAdapt(hostContext as Activity, this@PlayContainer)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return false
        }
    }

    companion object {

        private const val MSG_PARSE_TIMEOUT = 100
    }
}
