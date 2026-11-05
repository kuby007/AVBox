package com.github.avbox.osc.ui

internal enum class DetailPlaybackEntry {
    PlayCapsule,
    Episode,
    Line,
    Cast,
    ExitFullscreen,
    SwitchRollback,
    MusicReturn,
    /** 详情页播放开关开启时：宫格直接进带播放的详情页，播放窗内嵌、不进全屏 */
    EmbeddedPlay,
}

internal data class DetailPlaybackPlan(
    val startPlayback: Boolean,
    val enterFullScreen: Boolean,
    val stopPlayback: Boolean,
)

internal object DetailPlaybackPolicy {

    fun plan(entry: DetailPlaybackEntry, resumable: Boolean = false): DetailPlaybackPlan = when (entry) {
        DetailPlaybackEntry.PlayCapsule,
        DetailPlaybackEntry.Episode -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = true, stopPlayback = false)

        DetailPlaybackEntry.EmbeddedPlay -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.SwitchRollback,
        DetailPlaybackEntry.MusicReturn -> DetailPlaybackPlan(startPlayback = resumable, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.Line,
        DetailPlaybackEntry.Cast -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.ExitFullscreen -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = true)
    }
}
