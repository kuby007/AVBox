package com.github.avbox.osc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.AppLog

/**
 * 画中画窗口上的系统按钮走 PendingIntent 广播，
 * 因为 Activity 进入画中画后不获输入焦点，普通点击收不到。
 */
class PipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null || !action.startsWith(ACTION_PREFIX)) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).post {
            try {
                handle(action)
            } catch (th: Throwable) {
                AppLog.e("PipActionReceiver", th)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(action: String) {
        when (action) {
            ACTION_PLAY_PAUSE -> PlaybackService.peek()?.player()?.togglePlay()
            ACTION_NEXT -> PlaybackService.peek()?.playNext(false)
            ACTION_PREVIOUS -> PlaybackService.peek()?.playPrevious()
            ACTION_LIVE_NEXT ->
                (ActivityStack.getInstance().currentActivity() as? PipLiveSwitch)?.pipSwitchChannel(1)
            ACTION_LIVE_PREVIOUS ->
                (ActivityStack.getInstance().currentActivity() as? PipLiveSwitch)?.pipSwitchChannel(-1)
        }
    }

    interface PipLiveSwitch {
        fun pipSwitchChannel(direction: Int)
    }

    companion object {
        private const val ACTION_PREFIX = "com.github.xbox.osc.pip."
        const val ACTION_PLAY_PAUSE = ACTION_PREFIX + "PLAY_PAUSE"
        const val ACTION_NEXT = ACTION_PREFIX + "NEXT"
        const val ACTION_PREVIOUS = ACTION_PREFIX + "PREVIOUS"
        const val ACTION_LIVE_NEXT = ACTION_PREFIX + "LIVE_NEXT"
        const val ACTION_LIVE_PREVIOUS = ACTION_PREFIX + "LIVE_PREVIOUS"
    }
}
