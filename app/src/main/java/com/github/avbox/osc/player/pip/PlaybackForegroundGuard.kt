package com.github.avbox.osc.player.pip

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.github.avbox.osc.ui.DetailActivity
import com.github.avbox.osc.ui.LivePlayActivity
import com.github.avbox.osc.ui.MusicPlayerActivity
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.AppLog

/**
 * 播放页「是否还在前端」的宿主级判定。
 *
 * 点播 / 直播 / 音乐三种播放页共用同一个 PlaybackService + PlaybackEngine，
 * 关闭画中画窗口时系统通常只把承载 Activity stop 掉、并不 finish，
 * 单靠 isFinishing 判断会漏掉收尾，后台就会继续出声。
 * 这里统一看三种播放页是否都不在前端，由宿主决定停不停后台播放进程。
 */
object PlaybackForegroundGuard {

    private const val TAG = "echo-pip-guard"
    private const val SETTLE_DELAY_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Runnable>()

    @JvmStatic
    fun hasFrontPlaybackPage(): Boolean {
        for (activity in ActivityStack.getInstance().activities()) {
            if (!isPlaybackPage(activity)) continue
            if (activity.isFinishing || activity.isDestroyed) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) {
                return true
            }
            // 三种播放页互相切换时会出现"旧页已 onStop、新页还没 onResume"的中间态，
            // 只认 RESUMED 会把这一帧误判成"没有前端播放页"而停播。
            if (activity.isChangingConfigurations) return true
            val owner = activity as? LifecycleOwner ?: continue
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return true
        }
        return false
    }

    private fun isPlaybackPage(activity: Activity): Boolean =
        activity is DetailActivity ||
            activity is LivePlayActivity ||
            activity is MusicPlayerActivity

    /**
     * 退出小窗后延迟一帧再判定：等 onStop / 配置变化 / 返回全屏的生命周期稳定。
     * 同一 Activity 只保留最后一次判定，避免 onPictureInPictureModeChanged 与 onStop 重复收尾。
     */
    @JvmStatic
    fun schedulePipExitCheck(activity: Activity, onFront: Runnable, onGone: Runnable) {
        val key = System.identityHashCode(activity)
        pending[key]?.let { main.removeCallbacks(it) }
        val task = Runnable {
            pending.remove(key)
            try {
                if (hasFrontPlaybackPage()) {
                    AppLog.i(TAG + " front playback page alive, restore ui")
                    onFront.run()
                } else {
                    AppLog.i(TAG + " no front playback page, stop background playback")
                    onGone.run()
                }
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
        pending[key] = task
        main.postDelayed(task, SETTLE_DELAY_MS)
    }

    @JvmStatic
    fun cancel(activity: Activity) {
        val key = System.identityHashCode(activity)
        pending.remove(key)?.let { main.removeCallbacks(it) }
    }
}
