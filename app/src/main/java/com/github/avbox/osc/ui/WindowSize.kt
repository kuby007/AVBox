package com.github.avbox.osc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

enum class WindowWidthClass { Compact, Medium, Expanded }

object WindowSize {

    const val MEDIUM_MIN_WIDTH_DP = 600

    const val EXPANDED_MIN_WIDTH_DP = 840

    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    @JvmStatic
    fun classify(windowWidthDp: Int): WindowWidthClass = when {
        windowWidthDp < MEDIUM_MIN_WIDTH_DP -> WindowWidthClass.Compact
        windowWidthDp < EXPANDED_MIN_WIDTH_DP -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }

    @JvmStatic
    fun shouldLockPortrait(smallestWidthDp: Int): Boolean =
        smallestWidthDp < LARGE_SCREEN_MIN_WIDTH_DP

    const val TARGET_CARD_WIDTH_DP = 130

    const val TARGET_ROW_CARD_WIDTH_DP = 200

    const val GRID_COLUMN_SPACING_DP = 12

    @JvmStatic
    fun gridColumns(availableWidthDp: Int, minColumns: Int): Int {
        if (availableWidthDp <= 0) return minColumns
        val perColumn = TARGET_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        return ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn).coerceAtLeast(minColumns)
    }

    @JvmStatic
    fun rowColumns(availableWidthDp: Int, maxColumns: Int): Int {
        if (availableWidthDp <= 0) return 1
        val perColumn = TARGET_ROW_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        return ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn).coerceIn(1, maxColumns)
    }

    /** 海报卡片高 / 宽（aspectRatio 2/3） */
    const val CARD_ASPECT_HEIGHT = 1.5f

    /** 卡片下方标题区预估高度 */
    const val CARD_TITLE_HEIGHT_DP = 44

    /** 卡片宽度下限，横屏再挤也不让卡片小到看不清 */
    const val MIN_CARD_WIDTH_DP = 100

    /** 横屏下卡片下方标题区更紧凑，给海报让出高度 */
    const val CARD_TITLE_HEIGHT_LANDSCAPE_DP = 34

    /**
     * 行式列表（站点列表）列数：只由宽度决定，保证至少 [minColumns] 列。
     * 横屏宽度变大时自动多列，避免大片留白。
     */
    @JvmStatic
    fun listColumns(availableWidthDp: Int, minColumns: Int, maxColumns: Int): Int {
        if (availableWidthDp <= 0) return minColumns
        val perColumn = TARGET_ROW_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP
        val byWidth = ((availableWidthDp + GRID_COLUMN_SPACING_DP) / perColumn)
        return byWidth.coerceIn(minColumns, maxColumns.coerceAtLeast(minColumns))
    }

    /**
     * 横竖屏通用的智能宫格列数。
     *
     * 只按宽度算列数在横屏下会翻车：手机横放时宽度接近翻倍，列数却不变，
     * 单卡被拉得很大，一屏连一行都放不下。这里同时纳入可用高度：
     * 先算出"要放下 [minRows] 行时每个卡片能有多高"，反推列数，
     * 再与按宽度得出的列数取大者（高度不够就得多列、卡片更小），
     * 最后用 [MIN_CARD_WIDTH_DP] 封顶，避免卡片缩到看不清。
     */
    @JvmStatic
    fun smartGridColumns(
        availableWidthDp: Int,
        availableHeightDp: Int,
        minColumns: Int,
        minRows: Int,
        landscape: Boolean = false,
    ): Int {
        if (availableWidthDp <= 0) return minColumns
        val titleHeight = if (landscape) CARD_TITLE_HEIGHT_LANDSCAPE_DP else CARD_TITLE_HEIGHT_DP
        val byWidth = gridColumns(availableWidthDp, minColumns)
        if (availableHeightDp <= 0) return byWidth
        val targetRowHeight = availableHeightDp.toFloat() / minRows.coerceAtLeast(1)
        val cardHeight = targetRowHeight - titleHeight - GRID_COLUMN_SPACING_DP
        if (cardHeight <= MIN_CARD_WIDTH_DP) {
            // 高度实在不够，退化为"尽量多列、卡片取最小可读宽度"
            val cap = (availableWidthDp / (MIN_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP))
                .coerceAtLeast(minColumns)
            return byWidth.coerceAtLeast(cap)
        }
        val cardWidth = cardHeight / CARD_ASPECT_HEIGHT
        val byHeight = kotlin.math.ceil(
            (availableWidthDp + GRID_COLUMN_SPACING_DP).toDouble() /
                (cardWidth + GRID_COLUMN_SPACING_DP)
        ).toInt()
        val cap = (availableWidthDp / (MIN_CARD_WIDTH_DP + GRID_COLUMN_SPACING_DP))
            .coerceAtLeast(minColumns)
        return byWidth.coerceAtLeast(byHeight).coerceAtMost(cap)
    }
}

@Composable
fun currentWindowWidthClass(): WindowWidthClass =
    WindowSize.classify(LocalConfiguration.current.screenWidthDp)
