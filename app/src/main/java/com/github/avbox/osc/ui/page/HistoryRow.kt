package com.github.avbox.osc.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.ui.components.VodPoster
import com.github.avbox.osc.ui.theme.cardContainer
import com.github.avbox.osc.util.TimeAgoUnit
import com.github.avbox.osc.util.timeAgoValueOf
import kotlin.math.roundToInt
import com.github.avbox.osc.ui.components.marqueeOnOverflow

internal const val PROGRESS_ENTER_DURATION_MS = 600

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryRow(
    item: VodInfo,
    totalEpisodes: Int?,
    playedPercent: Int?,
    // 必须传 State 且在行内取值：分钟跳动靠本行作用域失效驱动，不依赖父级是否重组
    nowMs: State<Long>,
    editMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var progressEntered by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(HistoryCardCorner),
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(HistoryCardCorner))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f),
            ) {
                VodPoster(
                    name = item.name,
                    pic = item.pic,
                    year = item.year,
                    sourceKey = item.sourceKey,
                    tmdbCacheOnly = true,
                    altName = item.en,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(HistoryPosterCorner)),
                )
                if (editMode) {
                    SelectCircle(
                        selected = selected,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val numberedEpisode = item.playNote.isNullOrEmpty() || EpisodeTotals.isNumberedEpisode(item.playNote)
                val episodeLabel = when {
                    item.playNote.isNullOrEmpty() -> item.note ?: ""
                    numberedEpisode -> stringResource(R.string.history_episode_num, item.playIndex + 1)
                    else -> item.playNote
                }
                val watchedLabel = playedPercent?.let { stringResource(R.string.history_see_percent, it) }
                Text(
                    text = item.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .marqueeOnOverflow(),
                )
                Text(
                    modifier = Modifier.marqueeOnOverflow(),
                    text = listOf(episodeLabel, watchedLabel.orEmpty())
                        .filter { it.isNotEmpty() }
                        .joinToString(HistoryMetaSeparator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val eps = if (numberedEpisode) {
                    totalEpisodes ?: parseEpisodeTotal(item.note) ?: parseEpisodeTotal(item.state)
                } else {
                    null
                }
                val episodeFraction = eps?.let { total ->
                    (item.playIndex + 1).coerceIn(1, total).toFloat() / total
                }
                val barProgress = playedPercent?.let { it / 100f } ?: episodeFraction ?: 0f
                val barColor = MaterialTheme.colorScheme.primary
                val progressAnim = remember {
                    Animatable(if (progressEntered) barProgress else 0f)
                }
                LaunchedEffect(barProgress) {
                    val spec = if (progressEntered) {
                        ProgressIndicatorDefaults.ProgressAnimationSpec
                    } else {
                        tween(PROGRESS_ENTER_DURATION_MS)
                    }
                    progressEntered = true
                    progressAnim.animateTo(barProgress, spec)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progressAnim.value },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp),
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        drawStopIndicator = {},
                    )
                    if (eps != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.history_episode_progress,
                                (item.playIndex + 1).coerceIn(1, eps),
                                eps,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                        )
                    } else if (playedPercent != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.history_watched_percent, (barProgress * 100).roundToInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                        )
                    }
                }
                val timeText = historyTimeAgo(item.updateTime, nowMs.value)
                val siteText = if (item.sourceUnavailable && item.sourceName.isNotEmpty()) {
                    item.sourceName + HistoryMetaSeparator + stringResource(R.string.source_unavailable)
                } else {
                    item.sourceName
                }
                val metaText = listOf(timeText, siteText)
                    .filter { it.isNotEmpty() }
                    .joinToString(HistoryMetaSeparator)
                if (metaText.isNotEmpty()) {
                    Text(
                        modifier = Modifier.marqueeOnOverflow(),
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.sourceUnavailable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(HistoryPlayBadgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.player_ic_play),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.size(HistoryPlayIconSize),
                )
            }
        }
    }
}

private val HistoryCardCorner = 22.dp

private val HistoryPosterCorner = 12.dp

private val HistoryPlayBadgeSize = 34.dp

private val HistoryPlayIconSize = 22.dp

private const val HistoryMetaSeparator = " · "

@Composable
private fun historyTimeAgo(atMs: Long, nowMs: Long): String {
    if (atMs <= 0L) return ""
    val ago = timeAgoValueOf(atMs, nowMs)
    return when (ago.unit) {
        TimeAgoUnit.JustNow -> stringResource(R.string.time_ago_just_now)
        TimeAgoUnit.Minutes -> stringResource(R.string.time_ago_minutes, ago.value)
        TimeAgoUnit.Hours -> stringResource(R.string.time_ago_hours, ago.value)
        TimeAgoUnit.Days -> stringResource(R.string.time_ago_days, ago.value)
        TimeAgoUnit.Months -> stringResource(R.string.time_ago_months, ago.value)
    }
}

// i18n: keep —— 匹配源数据(片名/备注)里的"第N集/期",不能翻
private val EpisodeTotalRegex = Regex("(\\d+)\\s*[集期]")

internal fun parseEpisodeTotal(note: String?): Int? {
    val total = note?.let { EpisodeTotalRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
    return total.takeIf { it in 2..1000 }
}
