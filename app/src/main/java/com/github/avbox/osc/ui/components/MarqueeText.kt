package com.github.avbox.osc.ui.components

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.ui.theme.TextScrollState

/**
 * 单行文字超长时自动滚动显示（跑马灯）。
 *
 * - 仅在【内容确实溢出】时才滚动，短文字保持静止，不会满屏乱滚；
 * - 常驻滚动：不依赖焦点，进入界面即开始，滚完停顿 [TextScrollState.DELAY_MILLIS] 再滚下一轮；
 * - 开关关闭时原样返回 [Modifier]，保持原来的省略号截断样式，行为与改动前完全一致。
 *
 * 用法：挂在 Text 的 modifier 上即可，放在 fillMaxWidth 之后。
 */
@Composable
fun Modifier.marqueeOnOverflow(): Modifier {
    if (!TextScrollState.enabled) return this
    return this.basicMarquee(
        iterations = Int.MAX_VALUE,
        animationMode = MarqueeAnimationMode.Immediately,
        initialDelayMillis = TextScrollState.DELAY_MILLIS,
        spacing = MarqueeSpacing.fractionOfContainer(1f / 3f),
        velocity = TextScrollState.VELOCITY_DP.dp,
    )
}
