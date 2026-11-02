package com.github.avbox.osc.player

import android.text.TextUtils
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.player.core.TrackInfo
import com.github.avbox.osc.player.state.AudioOnlyRules
import com.github.avbox.osc.player.state.AudioOnlyState
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap

class MusicSessionDelegate(private val host: Host) {

    interface Host {
        fun view(): PlaybackViewBridge?

        fun attemptState(): PlaybackAttemptState

        fun updateAttemptState(block: (PlaybackAttemptState) -> PlaybackAttemptState)

        fun timeouts(): PlaybackTimeouts

        fun vod(): VodInfo?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun quality(): JSONObject?

        fun isStartedPlayState(state: PlayState): Boolean

        fun retryAfterStartedError(): Boolean

        fun initParse(flag: String?, useParse: Boolean, playUrl: String, url: String)

        fun playUrl(url: String, headers: HashMap<String, String>?)
    }

    private var audioOnlyIntent = false

    private var audioOnlyConfirmed = false

    private var audioSessionActive = false

    private val trackAudioOnly: () -> Boolean? = { isAudioOnlyPlayback() }

    private var playArtwork: String? = null

    private var playDanmu: String? = null

    var currentArtwork: String? = null

    fun clearArtworks() {
        playArtwork = null
        currentArtwork = null
    }

    fun beginSwitchPlayback() {
        host.updateAttemptState { it.copy(switchingPlayback = true) }
        host.timeouts().cancelPendingCompletionDrop()
    }

    fun playArtwork(): String? = playArtwork

    fun currentArtwork(): String? = currentArtwork


    fun playDanmu(): String? = playDanmu

    fun setPlayDanmu(danmu: String?) {
        playDanmu = danmu ?: ""
    }

    fun stopMusicSessionForFailedPlayback() {
        host.updateAttemptState { it.clearSessionFlags() }
        resetAudioSession()
        host.timeouts().cancelPendingCompletionDrop()
        stopMusicSession()
    }

    fun onNewContent() {
        audioOnlyConfirmed = false
    }

    fun onNewPlayStarted() {
        audioOnlyConfirmed = false
        audioSessionActive = false
    }

    fun resetAudioSession() {
        audioSessionActive = false
    }

    fun audioOnlyState(): AudioOnlyState =
        AudioOnlyRules.state(audioOnlyIntent, audioOnlyConfirmed, trackAudioOnly)

    fun stopMusicSession() {
        val view = host.view() ?: return
        PlaybackService.stopSession(view.context(), view.playbackHost())
    }

    fun isConfirmedAudioOnly(): Boolean = audioOnlyState() == AudioOnlyState.CONFIRMED

    fun isAudioOnlyContent(): Boolean = java.lang.Boolean.TRUE == isAudioOnlyPlayback()

    fun setMusicAudioOnly(enabled: Boolean) {
        if (audioOnlyIntent == enabled) return
        audioOnlyIntent = enabled
        AppLog.i("echo-music audio-only forced=$enabled")
    }

    fun handlePlayStateForMusicSession(playState: PlayState): Boolean {
        if (host.attemptState().switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                AppLog.i("echo-music keep session while resolving next episode")
                return true
            } else if (playState == PlayState.ERROR) {
                host.updateAttemptState { it.copy(switchingPlayback = false) }
            } else if (host.isStartedPlayState(playState)) {
                if (hasPlayableAudio() || audioSessionActive) {
                    host.updateAttemptState { it.copy(switchingPlayback = false) }
                    audioSessionActive = true
                }
            }
        }
        if (!host.attemptState().switchingPlayback) {
            if (playState == PlayState.COMPLETED) {
                host.timeouts().cancelPendingCompletionDrop()
                host.timeouts().armPendingCompletionDrop()
                return false
            }
            updateMusicSession()
        }
        return false
    }

    fun handlePendingCompletionDrop() {
        val st = host.attemptState()
        if (st.switchingPlayback) {
            AppLog.i("echo-music completion drop skipped: page registered switching")
            return
        }
        val view = host.view() ?: return
        if (!PlaybackService.isSupported(view.context())) return
        val state = view.playState()
        if (host.isStartedPlayState(state)) {
            AppLog.i("echo-music completion drop skipped: kernel already started, state=$state")
            return
        }
        if (!view.isPageAlive()) {
            AppLog.i("echo-music completion drop skipped: page not alive, state=$state")
            return
        }
        if (state != PlayState.COMPLETED) {
            AppLog.i("echo-music completion drop skipped: state moved on, state=$state")
            return
        }
        AppLog.i("echo-music session drop after completed (deferred): no next episode registered")
        PlaybackService.stopSession(view.context(), view.playbackHost())
        audioSessionActive = false
    }

    private fun hasPlayableAudio(): Boolean {
        val trackInfo = currentTrackInfo()
        return trackInfo != null && trackInfo.audio.isNotEmpty()
    }

    private fun isAudioOnlyPlayback(): Boolean? {
        val trackInfo = currentTrackInfo()
        if (trackInfo == null || trackInfo.audio.isEmpty()) return null
        return trackInfo.video.isEmpty()
    }

    private fun currentTrackInfo(): TrackInfo? {
        val view = host.view() ?: return null
        try {
            val mediaPlayer = view.mediaPlayer()
            if (mediaPlayer is ExoPlayer) {
                return mediaPlayer.getTrackInfo()
            }
        } catch (ignored: Throwable) {
            AppLog.d("PlaybackController", "track info unavailable")
        }
        return null
    }

    fun ensureAudioOnlyRender() {
        val view = host.view() ?: return
        val audioOnly = isAudioOnlyPlayback()
        if (java.lang.Boolean.TRUE == audioOnly) {
            view.switchRenderToTexture()
        } else if (java.lang.Boolean.FALSE == audioOnly) {
            view.ensureRenderViewMatchesConfig()
        }
    }

    fun updateMusicSession() {
        val view = host.view() ?: return
        if (!view.isPageAlive()) return
        val context = view.context()
        if (!PlaybackService.isSupported(context)) return
        val st = host.attemptState()
        if (st.switchingPlayback) return
        val vod = host.vod()
        val trackInfo = currentTrackInfo()
        val hasAudio: Boolean? = trackInfo != null && trackInfo.audio.isNotEmpty()
        val audioOnly: Boolean? = if (audioOnlyIntent && java.lang.Boolean.TRUE == hasAudio) {
            true
        } else if (trackInfo == null || trackInfo.audio.isEmpty()) {
            null
        } else {
            trackInfo.video.isEmpty()
        }
        if (java.lang.Boolean.TRUE == hasAudio) {
            audioSessionActive = true
            if (java.lang.Boolean.TRUE == audioOnly) audioOnlyConfirmed = true
        }
        val state = view.playState()
        AppLog.i(
            "echo-music session gate: state=" + state + " playing=" + view.isPlaying()
                + " hasAudio=" + hasAudio + " audioOnly=" + audioOnly + " audioPlayback=" + audioSessionActive
                + " audioOnlyConfirmed=" + audioOnlyConfirmed + " audioOnlyState=" + audioOnlyState()
                + " switching=" + st.switchingPlayback
                + " pos=" + view.currentPosition()
        )
        if (audioSessionActive && java.lang.Boolean.TRUE == audioOnly
            && !host.isStartedPlayState(state)
            && TextUtils.isEmpty(playArtwork) && vod != null && !TextUtils.isEmpty(vod.pic)
        ) {
            val artwork = requireNotNull(vod.pic)
            playArtwork = artwork
            view.setArtwork(artwork)
        }
        if ((state == PlayState.ERROR && audioOnlyConfirmed) && host.retryAfterStartedError()) {
            AppLog.i("echo-music session keep: auto retry after started error (audio-only)")
            return
        }
        if (vod == null || !audioSessionActive
            || state == PlayState.ERROR
            || state == PlayState.COMPLETED
        ) {
            AppLog.i(
                "echo-music session drop: vod=" + (vod != null) + " audioPlayback=" + audioSessionActive
                    + " state=" + state + " (ERROR=" + PlayState.ERROR
                    + " COMPLETED=" + PlayState.COMPLETED + ")"
            )
            PlaybackService.stopSession(context, view.playbackHost())
            audioSessionActive = false
            return
        }
        view.requestNotificationPermission()
        val currentSeries = host.currentSeries(vod.playFlag, vod.playIndex)
        val episode = if (currentSeries == null || TextUtils.isEmpty(currentSeries.name)) "" else currentSeries.name
        PlaybackService.updateSession(
            context, view.playbackHost(),
            if (TextUtils.isEmpty(vod.name)) "TVBox" else vod.name,
            episode, vod.pic, view.currentPosition(), view.duration(), view.isPlaying()
        )
    }

    fun selectQuality(position: Int): Boolean {
        val quality = host.quality() ?: return false
        return try {
            val urls = JSONArray(quality.optString("url"))
            val url = urls.optString(position * 2 + 1)
            if (TextUtils.isEmpty(url)) return false
            val playUrl = quality.optString("playUrl", "")
            val flag = quality.optString("flag")
            val parse = quality.optString("parse", "1") == "1"
            val jx = quality.optString("jx", "0") == "1"
            val headers = PlaybackController.extractHeaders(quality)
            if (parse || jx) {
                val flags = ApiConfig.get().vipParseFlags ?: mutableListOf()
                val userJxList = (playUrl.isEmpty() && flags.contains(flag)) || jx
                host.initParse(flag, userJxList, playUrl, url)
            } else {
                host.view()?.showParse(false)
                host.playUrl(playUrl + url, headers)
            }
            true
        } catch (th: Throwable) {
            false
        }
    }
}
