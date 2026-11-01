package com.github.avbox.osc.player.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.annotation.RequiresApi
import com.github.avbox.osc.R
import com.github.avbox.osc.receiver.PipActionReceiver
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

object PipLauncher {

    private const val MIN_ASPECT = 0.4184f
    private const val MAX_ASPECT = 2.39f
    private const val DEFAULT_ASPECT = 16f / 9f
    private const val PORTRAIT_ASPECT = 9f / 16f

    @JvmStatic
    fun enabled(): Boolean =
        KV.get(KVKeys.PLAY_PIP_ENABLED, KVKeys.PLAY_PIP_ENABLED_DEFAULT)

    @JvmStatic
    fun isSupported(context: Context?): Boolean {
        if (context == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } catch (th: Throwable) {
            false
        }
    }

    @JvmStatic
    fun enter(
        activity: Activity,
        targetClass: Class<out Activity>,
        videoView: View?,
        portraitVideo: Boolean,
        liveMode: Boolean,
    ): Boolean {
        if (!enabled()) return false
        if (!isSupported(activity)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            activity.enterPictureInPictureMode(buildParams(activity, targetClass, videoView, portraitVideo, liveMode))
            true
        } catch (th: Throwable) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildParams(
        activity: Activity,
        targetClass: Class<out Activity>,
        videoView: View?,
        portraitVideo: Boolean,
        liveMode: Boolean,
    ): PictureInPictureParams {
        val ratio = if (portraitVideo) PORTRAIT_ASPECT else DEFAULT_ASPECT
        val aspect = Rational(
            (ratio * 10000).toInt().coerceIn(1, Int.MAX_VALUE),
            10000,
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(clampRational(aspect))
        val rect = sourceRect(videoView)
        if (rect != null) builder.setSourceRectHint(rect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        builder.setActions(buildActions(activity, targetClass, liveMode))
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun clampRational(value: Rational): Rational {
        val r = value.toFloat()
        return when {
            r <= 0f -> Rational(16, 9)
            r < MIN_ASPECT -> Rational(4184, 10000)
            r > MAX_ASPECT -> Rational(239, 100)
            else -> value
        }
    }

    private fun sourceRect(videoView: View?): Rect? {
        val view = videoView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect)) return null
        return rect
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildActions(
        activity: Activity,
        targetClass: Class<out Activity>,
        liveMode: Boolean,
    ): List<RemoteAction> {
        val actions = ArrayList<RemoteAction>()
        val playPause = RemoteAction(
            Icon.createWithResource(activity, android.R.drawable.ic_media_play),
            activity.getString(R.string.pip_action_play_pause),
            activity.getString(R.string.pip_action_play_pause),
            broadcast(activity, PipActionReceiver.ACTION_PLAY_PAUSE, 1),
        )
        actions.add(playPause)
        val prevAction = if (liveMode) PipActionReceiver.ACTION_LIVE_PREVIOUS else PipActionReceiver.ACTION_PREVIOUS
        val nextAction = if (liveMode) PipActionReceiver.ACTION_LIVE_NEXT else PipActionReceiver.ACTION_NEXT
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_previous),
                activity.getString(R.string.pip_action_previous),
                activity.getString(R.string.pip_action_previous),
                broadcast(activity, prevAction, 2),
            )
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(activity, android.R.drawable.ic_media_next),
                activity.getString(R.string.pip_action_next),
                activity.getString(R.string.pip_action_next),
                broadcast(activity, nextAction, 3),
            )
        )
        val max = try {
            activity.maxNumPictureInPictureActions
        } catch (th: Throwable) {
            3
        }
        if (max > actions.size) {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(activity, android.R.drawable.ic_menu_view),
                    activity.getString(R.string.pip_back_to_app),
                    activity.getString(R.string.pip_back_to_app),
                    backToApp(activity, targetClass, 4),
                )
            )
        }
        return actions
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PipActionReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun backToApp(
        context: Context,
        targetClass: Class<out Activity>,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, targetClass).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
