package com.github.avbox.osc.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.Target
import coil3.toBitmap
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.PlaybackDataPorts
import com.github.avbox.osc.data.PlaybackPorts
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.player.engine.PlayerCache
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.ScreenUtils
import java.lang.ref.WeakReference

class PlaybackService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private var sessionActivity: PendingIntent? = null
    private var title: String? = "TVBox"
    private var subtitle: String? = ""
    private var artworkUrl: String = ""
    private var artwork: Bitmap? = null
    private var position: Long = 0
    private var duration: Long = 0
    private var playing: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var foregroundDenied: Boolean = false
    private var foregroundRetryLogged: Boolean = false
    private var releaseEngineOnDestroy: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    private fun text(resId: Int): String {
        val app: Context? = applicationContext
        return if (app == null) getString(resId) else LanguageManager.localized(app).getString(resId)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingStart = false
        releaseEngineOnDestroy = false
        AppLog.i(TAG + " host onCreate (engine=" + (if (engine == null) "none" else "alive") + ")")
        createNotificationChannel()
        createMediaSession()
        initPlayerCache()
    }

    private fun initPlayerCache() {
        Thread({
            FileUtils.purgeExoCacheIfPending()
            PlayerCache.warmup(this)
        }, "playback-cache-boot").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        releaseEngineOnDestroy = false
        val action = intent?.action
        if (action != null) {
            startForegroundSafely()
            handleSessionIntent(requireNotNull(intent))
        }
        if (stopWhenStarted) {
            stopWhenStarted = false
            stopPlaybackSession()
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i(TAG + " host onTaskRemoved → release engine")
        releaseEngineOnDestroy = true
        stopPlaybackSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i(TAG + " host onDestroy")
        instance = null
        stopPlaybackSession()
        if (releaseEngineOnDestroy) {
            releaseEngineOnDestroy = false
            releaseEngine()
            AppLog.i(TAG + " engine released (task removed)")
        } else {
            engine?.keepKernelAfterServiceDestroy()
        }
        super.onDestroy()
    }

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "TVBoxPlayback")
        mediaSession?.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                val host = getOwner()
                if (host != null) host.resumeFromMediaSession()
            }

            override fun onPause() {
                val host = getOwner()
                if (host != null) host.pauseFromMediaSession()
            }

            override fun onSkipToPrevious() {
                val host = getOwner()
                if (host != null) {
                    pauseForSwitch()
                    host.playPrevious()
                }
            }

            override fun onSkipToNext() {
                val host = getOwner()
                if (host != null) {
                    pauseForSwitch()
                    host.playNext(false)
                }
            }

            override fun onStop() {
                val host = getOwner()
                if (host != null) {
                    host.stopFromMediaSession()
                } else {
                    stopPlaybackSession()
                }
            }

            override fun onSeekTo(pos: Long) {
                val host = getOwner()
                if (host != null) host.seekFromMediaSession(pos)
            }
        }, Handler(Looper.getMainLooper()))
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            sessionActivity = PendingIntent.getActivity(this, 0, launchIntent, flags)
            mediaSession?.setSessionActivity(sessionActivity)
        }
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mediaSession == null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            if (foregroundDenied) {
                foregroundDenied = false
                foregroundRetryLogged = false
                AppLog.i(TAG + " startForeground recovered after denial")
            }
        } catch (th: Throwable) {
            foregroundDenied = true
            if (!foregroundRetryLogged) {
                foregroundRetryLogged = true
                AppLog.i(
                    TAG + " startForeground DENIED (service stays non-foreground, will retry on"
                        + " next session update): " + th.message
                )
            }
        }
    }

    private fun promoteIfForegroundDenied() {
        if (!foregroundDenied) return
        AppLog.i(TAG + " media action while foreground denied → retry startForeground")
        startForegroundSafely()
    }

    private fun handleSessionIntent(intent: Intent) {
        val action = intent.action
        if (ACTION_STOP == action) {
            val host = getOwner()
            if (host != null) host.stopFromMediaSession()
            stopPlaybackSession()
            return
        }
        if (ACTION_PLAY == action) {
            val host = getOwner()
            if (host != null) host.resumeFromMediaSession()
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_PAUSE == action) {
            val host = getOwner()
            if (host != null) host.pauseFromMediaSession()
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_PREVIOUS == action) {
            val host = getOwner()
            if (host != null) {
                pauseForSwitch()
                host.playPrevious()
            }
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_NEXT == action) {
            val host = getOwner()
            if (host != null) {
                pauseForSwitch()
                host.playNext(false)
            }
            promoteIfForegroundDenied()
            return
        }
        if (ACTION_SEEK == action) {
            val host = getOwner()
            if (host != null) host.seekFromMediaSession(intent.getLongExtra(EXTRA_SEEK, 0))
            return
        }
        if (ACTION_UPDATE == action) {
            if (mediaSession == null) createMediaSession()
            if (mediaSession == null) return
            acquirePlaybackLocks()
            title = intent.getStringExtra(EXTRA_TITLE)
            subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
            val newArtworkUrl = intent.getStringExtra(EXTRA_ARTWORK)
            position = intent.getLongExtra(EXTRA_POSITION, 0)
            duration = intent.getLongExtra(EXTRA_DURATION, 0)
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
            updateArtwork(newArtworkUrl)
            updateSessionState()
            startForegroundSafely()
        }
    }

    private fun updateArtwork(url: String?) {
        if (TextUtils.equals(artworkUrl, url)) return
        artworkUrl = url ?: ""
        artwork = null
        if (TextUtils.isEmpty(artworkUrl)) return
        val request = ImageRequest.Builder(this)
            .data(artworkUrl)
            .size(256, 256)
            .target(object : Target {
                override fun onSuccess(result: Image) {
                    artwork = result.toBitmap()
                    if (mediaSession == null) return
                    updateSessionState()
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification())
                }
            })
            .build()
        SingletonImageLoader.get(this).enqueue(request)
    }

    private fun updateSessionState() {
        val session = mediaSession ?: return
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, subtitle)
        if (duration > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        if (!TextUtils.isEmpty(artworkUrl)) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
        }
        val art = artwork
        if (art != null) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(metadata.build())
        val action = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(action)
                .setState(state, position, if (playing) 1f else 0f)
                .build()
        )
        session.setActive(true)
    }

    private fun pauseForSwitch() {
        playing = false
        position = 0
        updateSessionState()
        startForegroundSafely()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
        } else {
            NotificationCompat.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setDeleteIntent(actionIntent(ACTION_STOP))
            .setStyle(
                MediaStyle().setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
        val art = artwork
        if (art != null) builder.setLargeIcon(art)
        builder.addAction(
            NotificationCompat.Action(R.drawable.media_action_placeholder, "", actionIntent(ACTION_PLACEHOLDER))
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.exo_icon_previous, text(R.string.player_notification_previous), actionIntent(ACTION_PREVIOUS)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (playing) R.drawable.exo_icon_pause else R.drawable.exo_icon_play,
                text(if (playing) R.string.common_pause else R.string.common_play),
                actionIntent(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.exo_icon_next, text(R.string.player_notification_next), actionIntent(ACTION_NEXT)
            )
        )
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, text(R.string.player_notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        channel.description = text(R.string.player_notification_channel_desc)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null) manager.createNotificationChannel(channel)
    }

    private fun acquirePlaybackLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVBox:Playback")
                    wakeLock?.setReferenceCounted(false)
                }
            }
            val lock = wakeLock
            if (lock != null && !lock.isHeld) {
                lock.acquire()
                AppLog.i("echo-music wake lock acquired")
            }
        } catch (th: Throwable) {
            AppLog.i("echo-music wake lock acquire failed: " + th.message)
        }
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBox:Playback")
                    wifiLock?.setReferenceCounted(false)
                }
            }
            val lock = wifiLock
            if (lock != null && !lock.isHeld) {
                lock.acquire()
                AppLog.i("echo-music wifi lock acquired")
            }
        } catch (th: Throwable) {
            AppLog.i("echo-music wifi lock acquire failed: " + th.message)
        }
    }

    private fun releasePlaybackLocks() {
        try {
            val lock = wifiLock
            if (lock != null && lock.isHeld) {
                lock.release()
                AppLog.i("echo-music wifi lock released")
            }
        } catch (th: Throwable) {
            AppLog.i("echo-music wifi lock release failed: " + th.message)
        } finally {
            wifiLock = null
        }
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
                AppLog.i("echo-music wake lock released")
            }
        } catch (th: Throwable) {
            AppLog.i("echo-music wake lock release failed: " + th.message)
        } finally {
            wakeLock = null
        }
    }

    private fun getOwner(): PlaybackHostApi? = owner?.get()

    private fun stopPlaybackSession() {
        AppLog.i(TAG + " stopPlaybackSession (notification 1001 removed, playing=" + playing + ")")
        playing = false
        foregroundDenied = false
        foregroundRetryLogged = false
        releasePlaybackLocks()
        val session = mediaSession
        if (session != null) {
            session.setActive(false)
            session.release()
            mediaSession = null
        }
        stopForeground(true)
    }

    companion object {

        private const val TAG = "echo-p2"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE = "com.github.avbox.osc.playback.UPDATE"
        private const val ACTION_PLAY = "com.github.avbox.osc.playback.PLAY"
        private const val ACTION_PAUSE = "com.github.avbox.osc.playback.PAUSE"
        private const val ACTION_PREVIOUS = "com.github.avbox.osc.playback.PREVIOUS"
        private const val ACTION_NEXT = "com.github.avbox.osc.playback.NEXT"
        private const val ACTION_PLACEHOLDER = "com.github.avbox.osc.playback.PLACEHOLDER"
        private const val ACTION_STOP = "com.github.avbox.osc.playback.STOP"
        private const val ACTION_SEEK = "com.github.avbox.osc.playback.SEEK"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_ARTWORK = "artwork"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PLAYING = "playing"
        private const val EXTRA_SEEK = "seek"

        private var instance: PlaybackService? = null
        private var engine: PlaybackEngine? = null
        private var owner: WeakReference<PlaybackHostApi>? = null

        private val prewarmHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var pendingStart: Boolean = false

        @Volatile
        private var stopWhenStarted: Boolean = false

        @JvmStatic
        fun engine(context: Context): PlaybackEngine {
            val app = context.applicationContext
            var current = engine
            if (current == null) {
                current = PlaybackEngine(app)
                engine = current
                installDataPorts()
            }
            startHost(app, null)
            return current
        }

        @JvmStatic
        fun peek(): PlaybackEngine? = engine

        /** 画中画窗口按钮复用 MediaSession 的切集链路：owner 才是真正实现切集的 host */
        @JvmStatic
        fun host(): PlaybackHostApi? = owner?.get()

        private fun installDataPorts() {
            if (PlaybackPorts.ports == null) {
                PlaybackPorts.ports = object : PlaybackDataPorts {
                    override fun currentVod(): VodInfo? = peek()?.controller()?.vod()
                    override fun isLiveMode(): Boolean = peek()?.isLiveMode() == true
                    override fun hasAttachedPage(): Boolean = peek()?.attachedPage() != null
                    override fun discardStartedContentOf(owners: List<String>) {
                        peek()?.discardStartedContentOf(owners)
                    }
                    override fun onProgressKeysCleared(keys: List<String>) {
                        peek()?.markZeroStartKeys(keys)
                    }
                }
            }
        }

        @JvmStatic
        fun prewarm(context: Context, delayMs: Long) {
            if (!KV.get(KVKeys.KERNEL_PREWARM, KVKeys.KERNEL_PREWARM_DEFAULT)) return
            val app = context.applicationContext
            prewarmHandler.removeCallbacksAndMessages(null)
            prewarmHandler.postDelayed({ ensurePrewarmed(app) }, Math.max(0L, delayMs))
        }

        @JvmStatic
        fun onPrewarmPreferenceChanged(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            prewarmHandler.removeCallbacksAndMessages(null)
            if (!enabled) {
                val current = engine
                if (current != null) current.onPrewarmPreferenceChanged(false)
                return
            }
            ensurePrewarmed(app)
        }

        private fun ensurePrewarmed(app: Context) {
            if (!KV.get(KVKeys.KERNEL_PREWARM, KVKeys.KERNEL_PREWARM_DEFAULT)) return
            try {
                var current = engine
                if (current == null) {
                    current = PlaybackEngine(app)
                    engine = current
                    installDataPorts()
                }
                current.onPrewarmPreferenceChanged(true)
            } catch (th: Throwable) {
                AppLog.e(TAG + " prewarm failed: " + th.message)
            }
        }

        private fun startHost(app: Context, intent: Intent?) {
            if (instance != null) return
            try {
                if (intent == null) {
                    app.startService(Intent(app, PlaybackService::class.java))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (th: Throwable) {
                AppLog.e(TAG + " startService failed: " + th.message)
            }
        }

        @JvmStatic
        fun isSupported(context: Context?): Boolean {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return !ScreenUtils.isTv(context)
        }

        @JvmStatic
        fun updateSession(
            context: Context?,
            host: PlaybackHostApi,
            title: String?,
            subtitle: String?,
            artwork: String?,
            position: Long,
            duration: Long,
            playing: Boolean,
        ) {
            if (!isSupported(context)) return
            if (engine == null) return
            if (engine?.isServiceLostKept() == true) {
                AppLog.i(TAG + " session update skipped: kernel kept after service destroyed")
                return
            }
            owner = WeakReference(host)
            val ctx = context ?: return
            val intent = Intent(ctx, PlaybackService::class.java).setAction(ACTION_UPDATE)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_SUBTITLE, subtitle)
            intent.putExtra(EXTRA_ARTWORK, artwork)
            intent.putExtra(EXTRA_POSITION, position)
            intent.putExtra(EXTRA_DURATION, duration)
            intent.putExtra(EXTRA_PLAYING, playing)
            val current = instance
            if (current != null) {
                pendingStart = false
                stopWhenStarted = false
                current.handleSessionIntent(intent)
            } else {
                AppLog.i(
                    TAG + " session update with no live service → startForegroundService"
                        + " (may be denied if app is in background)"
                )
                pendingStart = true
                stopWhenStarted = false
                startHost(ctx.applicationContext, intent)
            }
        }

        @JvmStatic
        fun forceStopSession(context: Context?) {
            owner = null
            val current = instance
            if (current != null) {
                pendingStart = false
                current.stopPlaybackSession()
            } else if (pendingStart) {
                stopWhenStarted = true
            }
        }

        /**
         * 用户点掉画中画窗口的关闭按钮时收尾。
         *
         * Activity 会被系统 finish，但播放内核挂在本服务的会话上；服务是
         * startService 起的，不会随 Activity 销毁而停止，只有 onTaskRemoved
         * 才 stopSelf。所以不显式收尾，后台会一直出声。
         */
        @JvmStatic
        fun stopForPipClose(context: Context?) {
            // 点播 / 直播 / 音乐三种模式共用同一个 engine，不依赖 owner 是否还在，
            // 直接把内核停掉；否则 owner 被提前清掉时只移除通知，声音仍在后台。
            val current = engine
            if (current != null) {
                try {
                    val view = current.player()
                    view.pause()
                    view.stopPlaybackKeepPlayer()
                } catch (th: Throwable) {
                    AppLog.e(TAG, th)
                }
            }
            val host = owner?.get()
            if (host != null) {
                try {
                    host.stopFromMediaSession()
                } catch (th: Throwable) {
                    AppLog.e(TAG, th)
                }
            }
            forceStopSession(context)
            instance?.stopSelf()
            // 服务 onDestroy 默认只 keepKernel，不释放；这里同步释放才彻底无声
            releaseEngine()
        }

        @JvmStatic
        fun stopSession(context: Context?, host: PlaybackHostApi?) {
            val current = owner?.get()
            if (host != null && current != null && current !== host) return
            AppLog.i(
                TAG + " stopSession: ownerMatch=" + (host != null && current === host)
                    + " ownerAlive=" + (current != null) + " host=" + host
            )
            owner = null
            val service = instance
            if (service != null) {
                pendingStart = false
                service.stopPlaybackSession()
            } else if (pendingStart) {
                stopWhenStarted = true
            }
        }

        private fun releaseEngine() {
            val current = engine
            engine = null
            current?.release()
        }

        @JvmStatic
        fun onEngineReleased(released: PlaybackEngine) {
            if (engine === released) engine = null
            owner = null
            AppLog.i(TAG + " engine self-released (idle)")
            val current = instance
            if (current != null) {
                current.stopPlaybackSession()
            }
        }
    }
}
