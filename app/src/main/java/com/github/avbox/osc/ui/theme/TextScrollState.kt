package com.github.avbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

/**
 * 超长文字滚动（跑马灯）开关状态。
 *
 * 默认开启：单行标题超出宽度时自动滚动显示，滚完停顿 2 秒再滚下一轮。
 * 关闭后退回原来的静态截断（省略号）样式。
 */
object TextScrollState {

    const val DEFAULT_ENABLED = true

    /** 每轮滚动结束后的停顿时长（毫秒） */
    const val DELAY_MILLIS = 2_000

    /** 滚动速度，与 foundation 默认值一致 */
    const val VELOCITY_DP = 30f

    private var current by mutableStateOf(load())

    var enabled: Boolean
        get() = current
        set(value) {
            KV.put(KVKeys.TEXT_SCROLL_ENABLED, value)
            current = value
        }

    private fun load(): Boolean =
        KV.get(KVKeys.TEXT_SCROLL_ENABLED, KVKeys.TEXT_SCROLL_ENABLED_DEFAULT)

    fun restoreDefaults() {
        enabled = DEFAULT_ENABLED
    }
}
