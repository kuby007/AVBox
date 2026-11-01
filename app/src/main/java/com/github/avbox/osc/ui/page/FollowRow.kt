package com.github.avbox.osc.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.github.avbox.osc.ui.components.marqueeOnOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.FollowDays
import com.github.avbox.osc.data.VodFollow
import com.github.avbox.osc.ui.components.AVBoxOptionMenuAction
import com.github.avbox.osc.ui.components.VodPoster
import com.github.avbox.osc.ui.components.WeekdayRes
import com.github.avbox.osc.ui.components.formatReminderTime
import com.github.avbox.osc.ui.components.joinedDaysText
import com.github.avbox.osc.ui.theme.SuccessGreen
import com.github.avbox.osc.ui.theme.cardContainer
import kotlin.math.roundToInt

private val ScheduleIconSize = 16.dp

private val ScheduleTextIndent = 22.dp

private val FollowCardCorner = 22.dp

private val FollowPosterCorner = 12.dp

private val FollowStatusDotSize = 10.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FollowRow(
    follow: VodFollow,
    history: VodInfo?,
    totalEpisodes: Int?,
    playedPercent: Int?,
    watched: Boolean,
    editMode: Boolean,
    selected: Boolean,
    selectedDay: Int?,
    today: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onWatchedChange: (Boolean) -> Unit,
) {
    var progressEntered by rememberSaveable { mutableStateOf(false) }
    val days = FollowDays.decode(follow.updateDays)
    val daysText = if (days.isEmpty()) {
        ""
    } else {
        joinedDaysText(
            WeekdayRes.map { stringResource(it) },
            days,
            stringResource(R.string.follow_days_separator),
        )
    }
    val hourText = formatReminderTime(follow.updateHour)
    val scopedDay = selectedDay?.takeIf { it in days }
    val scheduleText = when {
        days.isEmpty() -> stringResource(R.string.following_no_schedule)
        scopedDay == today -> stringResource(R.string.following_update_today_time, hourText)
        scopedDay != null -> stringResource(
            R.string.following_update_time,
            stringResource(WeekdayRes[scopedDay]) + " " + hourText,
        )
        else -> stringResource(R.string.following_update_days, daysText)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FollowCardCorner),
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(FollowCardCorner))
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
                    name = follow.name,
                    pic = follow.pic,
                    sourceKey = follow.sourceKey,
                    tmdbCacheOnly = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(FollowPosterCorner)),
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = follow.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .marqueeOnOverflow(),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val statusText = stringResource(
                        if (watched) R.string.following_watched else R.string.following_not_watched,
                    )
                    Box(
                        modifier = Modifier
                            .size(FollowStatusDotSize)
                            .clip(CircleShape)
                            .background(if (watched) SuccessGreen else MaterialTheme.colorScheme.error)
                            .semantics { contentDescription = statusText },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AVBoxOptionMenuAction(
                        options = listOf(
                            stringResource(R.string.following_not_watched),
                            stringResource(R.string.following_watched),
                        ),
                        selectedIndex = if (watched) 1 else 0,
                        onSelect = { index -> onWatchedChange(index == 1) },
                        contentDescription = stringResource(R.string.player_menu_more),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_follow_update_time),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ScheduleIconSize),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = scheduleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (days.isNotEmpty() && scopedDay == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(ScheduleTextIndent))
                        Text(
                            text = stringResource(R.string.following_update_time, hourText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                val eps = followEpisodeTotal(history, totalEpisodes)
                val episodeFraction = eps?.let { total ->
                    ((history?.playIndex ?: 0) + 1).coerceIn(1, total).toFloat() / total
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
                    val progressText = when {
                        history != null && eps != null -> stringResource(
                            R.string.history_episode_progress,
                            (history.playIndex + 1).coerceIn(1, eps),
                            eps,
                        )
                        playedPercent != null -> stringResource(R.string.history_watched_percent, (barProgress * 100).roundToInt())
                        history == null -> stringResource(R.string.following_not_watched)
                        else -> null
                    }
                    if (progressText != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                        )
                    }
                }
            }
        }
    }
}

private fun followEpisodeTotal(history: VodInfo?, totalEpisodes: Int?): Int? {
    if (history == null) return null
    val numberedEpisode = history.playNote.isNullOrEmpty() || EpisodeTotals.isNumberedEpisode(history.playNote)
    if (!numberedEpisode) return null
    return totalEpisodes ?: parseEpisodeTotal(history.note) ?: parseEpisodeTotal(history.state)
}
