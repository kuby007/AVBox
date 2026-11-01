package com.github.avbox.osc.player

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.request.Disposable
import com.github.avbox.osc.player.host.EngineSurfaceRenderViewFactory
import com.github.avbox.osc.player.host.EngineTextureRenderViewFactory
import com.github.avbox.osc.player.host.TextureRenderHost
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.util.ImgUtil
import com.github.avbox.osc.util.AppLog
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.ui.widget.DanmakuView
import kotlin.math.abs

private const val DANMU_SMALL_SEEK_MS = 5000L
private const val DANMU_SEEK_FADE_IN_DELAY_MS = 80L
private const val DANMU_SEEK_FADE_IN_MS = 250L
private const val DANMU_CATCH_UP_MIN_STEP_MS = 32L
private const val DANMU_CATCH_UP_FRAMES = 20
private const val DANMU_CATCH_UP_DONE_MS = 50L
private const val DANMU_CLOCK_DRIFT_HARD_MS = 2000.0
private const val DANMU_CLOCK_TRACK_GAIN_PER_SEC = 0.3

class PlayerKernelView : BasePlayerView, DrawHandler.Callback {

    private var danmuView: DanmakuView? = null

    @Volatile
    private var danmuClockMs: Long = 0L

    @Volatile
    private var danmuClockRunning: Boolean = false

    @Volatile
    private var danmuCatchUp: Boolean = false

    private var danmuFadeInRunnable: Runnable? = null

    private var danmuClockBasePositionMs: Long = 0L

    private var danmuClockBaseNanos: Long = 0L

    private var danmuClockBaseSpeed: Float = 1f

    private var danmuClockBaseValid: Boolean = false

    private var danmuClockAdvancing: Boolean = false

    private var danmuClockFrozenMs: Long = 0L

    private var danmuClockCorrectionMs: Double = 0.0

    private var danmuClockLastFrameNanos: Long = 0L

    private val danmuClockHandler = Handler(Looper.getMainLooper())

    private val danmuClockChoreographer: Choreographer
        get() = Choreographer.getInstance()

    private val danmuClockTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            danmuClockMs = sampleDanmuClock(frameTimeNanos)
            if (danmuClockRunning) danmuClockChoreographer.postFrameCallback(this)
        }
    }

    private var artworkView: ImageView? = null

    private var artworkDisposable: Disposable? = null

    private var frameCover: View? = null

    private var exoDiskCacheEnabled: Boolean = false

    private var kernelRebuildRequired: Boolean = false

    private var trackMemoryKey: String = ""

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        super(context, attrs, defStyleAttr)

    val exoPlayer: ExoPlayer?
        get() = kernelPlayer as? ExoPlayer

    fun setExoDiskCacheEnabled(enabled: Boolean) {
        exoDiskCacheEnabled = enabled
        applyExoDiskCacheFlag()
    }

    fun setTrackMemoryKey(key: String?) {
        trackMemoryKey = key ?: ""
        applyTrackMemoryKey()
    }

    override fun initPlayer() {
        super.initPlayer()
        applyExoDiskCacheFlag()
        applyTrackMemoryKey()
    }

    private fun applyTrackMemoryKey() {
        exoPlayer?.setContentKey(trackMemoryKey)
    }

    private fun applyExoDiskCacheFlag() {
        exoPlayer?.setUseDiskCache(exoDiskCacheEnabled)
    }

    val playState: PlayState
        get() = kernelPlayer?.playState ?: PlayState.IDLE

    fun isKernelErrored(): Boolean = kernelPlayer != null && playState == PlayState.ERROR

    fun requireKernelRebuild() {
        kernelRebuildRequired = true
    }

    fun consumeKernelRebuildRequired(): Boolean {
        val required = kernelRebuildRequired
        kernelRebuildRequired = false
        return required
    }

    override val renderIsSurface: Boolean
        get() = renderView()?.getView() is SurfaceView

    override fun factoryRenderType(): Int =
        if (renderViewFactory() is EngineTextureRenderViewFactory) 0 else 1

    /**
     * 当前渲染视图与「配置期望的渲染类型」是否不符。
     * 与 [needsRenderRebuild] 的区别：基准是配置值而非渲染视图工厂——音乐页会把工厂改成
     * Texture，用工厂作基准会在退出音乐页时误判为渲染类型变更。
     */
    fun isSurfaceRenderMismatch(configRenderType: Int): Boolean {
        val render = renderView() ?: return false
        return (configRenderType == 1) != (render.getView() is SurfaceView)
    }

    /**
     * 把渲染视图还原成「配置期望的渲染类型」。
     *
     * 与 [ensureRenderViewMatchesConfig] 的区别：[ensureRenderViewMatchesConfig] 以渲染视图工厂为
     * 基准，而音乐页的 [switchRenderToTexture] 会把工厂改成 Texture，退出音乐页时按工厂还原等于
     * 又建一个 TextureView，随后 `alignInstanceConfigOnTakeover` 会据此误判成渲染类型变更并重建内核。
     */
    fun alignRenderViewToConfig(configRenderType: Int) {
        val mismatch = isSurfaceRenderMismatch(configRenderType)
        AppLog.i(
            "echo-render-align: config=$configRenderType"
                + " view=" + (if (renderView() == null) "null" else if (renderIsSurface) "surface" else "texture")
                + " mismatch=$mismatch kernel=" + (kernelPlayer != null),
        )
        if (renderView() == null || kernelPlayer == null) {
            addDisplay()
            return
        }
        if (!mismatch) return
        setRenderViewFactory(
            if (configRenderType == 1) {
                EngineSurfaceRenderViewFactory.create()
            } else {
                EngineTextureRenderViewFactory.create()
            },
        )
        addDisplay()
    }

    fun switchRenderToTexture() {
        setRenderViewFactory(EngineTextureRenderViewFactory.create())
        addDisplay()
    }

    fun rebuildRenderView(renderType: Int) {
        AppLog.i("echo-render-rebuild: type=$renderType")
        setRenderViewFactory(
            if (renderType == 1) {
                EngineSurfaceRenderViewFactory.create()
            } else {
                EngineTextureRenderViewFactory.create()
            },
        )
        addDisplay()
        renderView()?.let { render ->
            render.setScaleType(currentScaleType)
            if (videoSizePx[0] > 0 && videoSizePx[1] > 0) {
                render.setVideoSize(videoSizePx[0], videoSizePx[1])
            }
        }
    }

    fun ensureRenderViewMatchesConfig() {
        // 内核已释放时渲染视图为 null，此时正是需要按配置重建视图的时机，不能因为没内核就跳过。
        if (renderView() == null || kernelPlayer == null) {
            addDisplay()
            return
        }
        val expectedSurface = renderViewFactory() !is EngineTextureRenderViewFactory
        if (expectedSurface == renderIsSurface) return
        addDisplay()
    }

    override fun addDisplay() {
        super.addDisplay()
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOnSurfaceReadyListener { pushRenderOutputResolution() }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pushRenderOutputResolution()
    }

    override fun onVideoSizeReported(videoWidth: Int, videoHeight: Int) {
        pushRenderOutputResolution()
    }

    private fun pushRenderOutputResolution() {
        val player = exoPlayer ?: return
        if (renderView() == null || renderIsSurface) return
        val width = videoSizePx[0]
        val height = videoSizePx[1]
        if (width <= 0 || height <= 0) return
        val render = renderView()
        if (render is TextureRenderHost) {
            render.setOutputSize(width, height)
        }
        player.notifyVideoOutputResolution(width, height)
    }

    fun setArtwork(url: String?) {
        if (url.isNullOrEmpty()) {
            clearArtwork()
            return
        }
        var view = artworkView
        if (view == null) {
            view = ImageView(context)
            view.setBackgroundColor(Color.BLACK)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.isClickable = false
            view.isFocusable = false
            val index = if (renderView() == null) 0 else Math.min(1, playerFrame.childCount)
            playerFrame.addView(
                view,
                index,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            artworkView = view
        }
        view.visibility = VISIBLE
        cancelArtworkRequest()
        artworkDisposable = ImgUtil.loadPlayerArtwork(url, view)
    }

    fun clearArtwork() {
        cancelArtworkRequest()
        artworkView?.visibility = GONE
        artworkView?.setImageDrawable(null)
    }

    private fun cancelArtworkRequest() {
        artworkDisposable?.dispose()
        artworkDisposable = null
    }

    fun isPortraitVideo(): Boolean = VideoOrientation.isPortrait(videoSizePx[0], videoSizePx[1])

    /** 视频宽高是否已就绪；未就绪时 isPortraitVideo() 的结果不可信 */
    fun hasVideoSizeForPip(): Boolean = videoSizePx[0] > 0 && videoSizePx[1] > 0

    fun clearVideoFrame() {
        val player = kernelPlayer
        if (player is ExoPlayer) player.stopForFrameClear() else player?.stop()
        showFrameCover()
    }

    fun coverVideoFrame() {
        showFrameCover()
    }

    private fun showFrameCover() {
        var cover = frameCover
        if (cover == null) {
            cover = View(context)
            cover.setBackgroundColor(Color.BLACK)
            playerFrame.addView(
                cover,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            frameCover = cover
        }
        cover.visibility = VISIBLE
        bringControllerToFront()
    }

    fun showVideoFrame() {
        hideVideoFrameCover()
        clearArtwork()
    }

    fun hideVideoFrameCover() {
        frameCover?.visibility = GONE
    }

    fun isVideoFrameCleared(): Boolean = frameCover?.visibility == VISIBLE

    override fun seekTo(pos: Long) {
        super.seekTo(pos)
        danmuClockBaseValid = false
        if (!haveDanmu() || danmuClockMs <= 0) return
        val delta = pos - danmuClockMs
        if (delta in 0..DANMU_SMALL_SEEK_MS) {
            startDanmuCatchUp()
        } else {
            hideAndSeekDanmu(pos)
        }
    }

    private fun startDanmuCatchUp() {
        danmuCatchUp = true
    }

    private fun hideAndSeekDanmu(pos: Long) {
        val view = danmuView ?: return
        danmuCatchUp = false
        view.animate().cancel()
        view.alpha = 0f
        view.seekTo(pos)
        danmuFadeInRunnable?.let { danmuClockHandler.removeCallbacks(it) }
        val fadeIn = Runnable {
            view.animate().alpha(1f).setDuration(DANMU_SEEK_FADE_IN_MS)
                .withEndAction { view.alpha = 1f }
                .start()
        }
        danmuFadeInRunnable = fadeIn
        danmuClockHandler.postDelayed(fadeIn, DANMU_SEEK_FADE_IN_DELAY_MS)
    }

    override fun resume() {
        super.resume()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun start() {
        super.start()
        if (haveDanmu()) danmuView?.resume()
    }

    override fun pause() {
        super.pause()
        if (haveDanmu()) danmuView?.pause()
    }

    override fun release() {
        super.release()
        trackMemoryKey = ""
        stopDanmuClock()
        if (haveDanmu()) danmuView?.release()
    }

    private fun haveDanmu(): Boolean = danmuView?.isPrepared == true

    fun setDanmuView(view: DanmakuView?) {
        removeStaleDanmuViews(view)
        danmuView = view
        danmuView?.setCallback(this)
        if (view == null) {
            stopDanmuClock()
        } else {
            attachDanmuViewIntoContainer(view)
            startDanmuClock()
        }
    }

    private fun removeStaleDanmuViews(keep: DanmakuView?) {
        for (i in playerFrame.childCount - 1 downTo 0) {
            val child = playerFrame.getChildAt(i)
            if (child is DanmakuView && child !== keep) {
                playerFrame.removeViewAt(i)
            }
        }
    }

    private fun attachDanmuViewIntoContainer(view: DanmakuView) {
        val host = playController?.controllerView()
        val hostIndex = if (host != null && host.parent === playerFrame) {
            playerFrame.indexOfChild(host)
        } else {
            -1
        }
        if (view.parent === playerFrame) {
            val currentIndex = playerFrame.indexOfChild(view)
            if (hostIndex < 0 || currentIndex < hostIndex) return
            playerFrame.removeView(view)
            playerFrame.addView(
                view,
                hostIndex,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        val index = if (hostIndex >= 0) hostIndex else playerFrame.childCount
        playerFrame.addView(
            view,
            index,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun startDanmuClock() {
        danmuClockBaseValid = false
        danmuClockLastFrameNanos = 0L
        if (danmuClockRunning) return
        danmuClockRunning = true
        AppLog.i("echo-danmu-clock start prepared=${danmuView?.isPrepared}")
        danmuClockChoreographer.removeFrameCallback(danmuClockTick)
        danmuClockChoreographer.postFrameCallback(danmuClockTick)
    }

    fun stopDanmuClock() {
        if (danmuClockRunning) AppLog.i("echo-danmu-clock stop")
        danmuClockRunning = false
        danmuCatchUp = false
        danmuClockMs = 0L
        danmuClockBaseValid = false
        danmuClockAdvancing = false
        danmuClockFrozenMs = 0L
        danmuClockCorrectionMs = 0.0
        danmuClockLastFrameNanos = 0L
        danmuClockChoreographer.removeFrameCallback(danmuClockTick)
        danmuFadeInRunnable?.let { danmuClockHandler.removeCallbacks(it) }
    }

    private fun sampleDanmuClock(frameTimeNanos: Long): Long {
        if (!danmuClockRunning) return danmuClockFrozenMs
        val speed = kernelPlayer?.speed ?: 1f
        if (!isDanmuAdvancing()) {
            if (danmuClockAdvancing) {
                danmuClockFrozenMs = danmuClockExtrapolatedAt(frameTimeNanos) + danmuClockCorrectionMs.toLong()
            }
            danmuClockAdvancing = false
            danmuClockBaseValid = false
            danmuClockCorrectionMs = 0.0
            danmuClockLastFrameNanos = frameTimeNanos
            return danmuClockFrozenMs
        }
        if (!danmuClockAdvancing || !danmuClockBaseValid) {
            rebaseDanmuClock(frameTimeNanos, speed)
        } else if (speed != danmuClockBaseSpeed) {
            retuneDanmuClock(frameTimeNanos, speed)
        }
        danmuClockAdvancing = true
        val extrapolated = danmuClockExtrapolatedAt(frameTimeNanos)
        trackDanmuClockDrift(frameTimeNanos, extrapolated)
        danmuClockLastFrameNanos = frameTimeNanos
        return (extrapolated + danmuClockCorrectionMs).toLong().coerceAtLeast(0L)
    }

    private fun danmuClockExtrapolatedAt(frameTimeNanos: Long): Long {
        if (!danmuClockBaseValid) return danmuClockFrozenMs
        val elapsedMs = (frameTimeNanos - danmuClockBaseNanos) / 1_000_000.0
        return (danmuClockBasePositionMs + elapsedMs * danmuClockBaseSpeed).toLong()
    }

    private fun rebaseDanmuClock(frameTimeNanos: Long, speed: Float) {
        danmuClockBasePositionMs = currentPosition
        danmuClockBaseNanos = frameTimeNanos
        danmuClockBaseSpeed = speed
        danmuClockBaseValid = true
        danmuClockLastFrameNanos = frameTimeNanos
        danmuClockCorrectionMs = 0.0
    }

    private fun retuneDanmuClock(frameTimeNanos: Long, speed: Float) {
        danmuClockBasePositionMs = danmuClockExtrapolatedAt(frameTimeNanos)
        danmuClockBaseNanos = frameTimeNanos
        danmuClockBaseSpeed = speed
        danmuClockBaseValid = true
        danmuClockLastFrameNanos = frameTimeNanos
    }

    private fun trackDanmuClockDrift(frameTimeNanos: Long, extrapolated: Long) {
        if (danmuClockLastFrameNanos == 0L) return
        val actual = currentPosition
        if (actual <= 0) return
        val drift = actual - (extrapolated + danmuClockCorrectionMs)
        if (abs(drift) > DANMU_CLOCK_DRIFT_HARD_MS) {
            AppLog.i("echo-danmu-clock resync drift=$drift pos=$actual")
            rebaseDanmuClock(frameTimeNanos, danmuClockBaseSpeed)
            return
        }
        val deltaSeconds = ((frameTimeNanos - danmuClockLastFrameNanos) / 1_000_000_000.0).coerceAtMost(0.1)
        danmuClockCorrectionMs += drift * DANMU_CLOCK_TRACK_GAIN_PER_SEC * deltaSeconds
    }

    private fun isDanmuAdvancing(): Boolean {
        val player = kernelPlayer ?: return false
        if (!player.isPlaying) return false
        return player.playState != PlayState.BUFFERING
    }

    override fun prepared() {
        danmuClockHandler.post {
            val view = danmuView ?: return@post
            if (isPlaying && view.isPrepared) {
                startDanmuClock()
                view.start(currentPosition)
            }
        }
    }

    override fun updateTimer(timer: DanmakuTimer?) {
        val t = timer ?: return
        if (!danmuClockRunning) return
        val position = danmuClockMs
        if (position <= 0) return
        if (!danmuCatchUp) {
            t.update(position)
            return
        }
        val current = t.currMillisecond
        val diff = position - current
        if (abs(diff) <= DANMU_CATCH_UP_DONE_MS) {
            danmuCatchUp = false
            t.update(position)
            return
        }
        val step = maxOf(DANMU_CATCH_UP_MIN_STEP_MS, abs(diff) / DANMU_CATCH_UP_FRAMES)
        t.update(if (diff > 0) current + step else current - step)
    }

    override fun danmakuShown(danmaku: BaseDanmaku?) {
    }

    override fun drawingFinished() {
    }
}
