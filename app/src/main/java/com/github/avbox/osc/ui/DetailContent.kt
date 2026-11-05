package com.github.avbox.osc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.data.FollowDays
import com.github.avbox.osc.ui.player.PlayContainer
import com.github.avbox.osc.ui.components.FollowReminderSheet
import com.github.avbox.osc.ui.components.marqueeOnOverflow

internal val HeroScrollFadeDistance = 300.dp

internal fun heroScrollProgress(firstVisibleIndex: Int, firstVisibleOffset: Int, referencePx: Int): Float = when {
    firstVisibleIndex > 0 -> 1f
    firstVisibleIndex < 0 -> 0f
    referencePx <= 0 -> 0f
    else -> (firstVisibleOffset.toFloat() / referencePx).coerceIn(0f, 1f)
}

/** 带播放的详情页：播放窗固定在选集正上方，线路 / 源 / TMDB 等内容上移到可滚动区。 */
private const val EmbeddedPlayerHeightDp = 200

@Composable
internal fun DetailContent(
    activity: DetailActivity,
    vm: DetailViewModel,
    listState: LazyListState,
    onPosterPic: (String) -> Unit,
    onSeed: (Int?) -> Unit,
    embeddedPlayer: PlayContainer? = null,
) {
    val vodUi by vm.vodUi.collectAsState()
    val info = vodUi?.info ?: return

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val collected by vm.collected.collectAsState()
    val followRecord by vm.follow.collectAsState()
    val description = remember(info.des) { removeHtmlTag(info.des) }
    var followScheduleOpen by rememberSaveable { mutableStateOf(false) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey
    val heroRollPaused by vm.fullScreen.collectAsState()

    // 上半部分内容：Hero / 线路 / 源 / TMDB。两种详情页共用这一份定义。
    val contentItems: LazyListScope.() -> Unit = {
        item(key = "hero") {
            DetailHero(
                title = info.name ?: "TVBox",
                altTitle = info.en,
                picture = info.pic,
                year = info.year,
                area = info.area,
                type = info.type,
                note = info.note,
                description = description,
                collected = collected,
                followed = followRecord != null,
                onPosterPic = onPosterPic,
                onSeed = onSeed,
                onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                onPlay = { vm.onPlayRequested(DetailPlaybackEntry.PlayCapsule) },
                onMusic = { activity.openMusicPlayer() },
                onCast = { activity.openCast() },
                onCollect = { vm.toggleCollect() },
                onFollow = { followScheduleOpen = true },
                vm = vm,
                rollPaused = heroRollPaused,
            )
        }

        if (flags.isNotEmpty()) {
            item(key = "flags") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    ) {
                        SectionTitleIcon(painterResource(R.drawable.ic_detail_line))
                        Text(
                            text = stringResource(R.string.detail_line),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.detail_source, displaySourceName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .marqueeOnOverflow()
                                .widthIn(max = 180.dp),
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                            LineCard(
                                name = flag.name ?: "",
                                count = info.seriesMap?.get(flag.name)?.size ?: 0,
                                selected = flag.name == currentFlag,
                                onClick = { vm.onFlagClick(flag.name ?: "") },
                            )
                        }
                    }
                }
            }
        }

        item(key = "sources") {
            SourceSection(vm, currentSourceName = displaySourceName)
        }

        item(key = "tmdb_info") {
            TmdbCastSection(vm, info.name, info.en, info.year)
        }

        item(key = "tmdb_genres") {
            TmdbGenreSection(vm, info.name, info.en, info.year)
        }
    }

    if (embeddedPlayer != null) {
        // 内容上移：可滚动区只放 Hero / 线路 / 源 / TMDB；播放窗与选集固定在屏幕下方，永远可见。
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                contentItems()
            }
            AndroidView(
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        (embeddedPlayer.parent as? android.view.ViewGroup)?.removeView(embeddedPlayer)
                        addView(
                            embeddedPlayer,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EmbeddedPlayerHeightDp.dp),
            )
            if (episodes.isNotEmpty()) {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }
    } else {
        // 关闭「详情页播放」：回到上游原顺序，选集紧跟 Hero。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            contentItems()
            if (episodes.isNotEmpty()) {
                item(key = "episodes") {
                    EpisodeRow(vm, info, episodes, playIndex, currentFlag)
                }
            }
        }
    }

    if (followScheduleOpen) {
        FollowReminderSheet(
            initialDays = followRecord?.let { FollowDays.decode(it.updateDays) }?.takeIf { it.isNotEmpty() }
                ?: setOf(0),
            initialHour = followRecord?.updateHour ?: 21,
            onDismissRequest = { followScheduleOpen = false },
            onSave = { days, hour -> vm.saveFollow(days, hour) },
        )
    }
}

@Composable
private fun LineCard(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DetailItemCard(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.width(DetailItemWidth),
    ) {
        Text(
            modifier = Modifier.marqueeOnOverflow(),
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.detail_line_video_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
