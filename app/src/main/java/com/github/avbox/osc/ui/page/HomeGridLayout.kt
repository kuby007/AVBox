package com.github.avbox.osc.ui.page

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.MovieSort
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.components.FilterSheet
import com.github.avbox.osc.ui.components.SkeletonBox
import com.github.avbox.osc.ui.components.VodCard
import com.github.avbox.osc.ui.components.VodCardStyle
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.first

internal val HomeGridTabRowHeight = 52.dp

private val HomeTabIndicatorInset = 16.dp

private val HomeTabIndicatorHeight = 3.dp

private val HomeFilterChipSpacing = 8.dp

private val HomeFilterChipFitSlack = 2.dp

private val HomeFilterChipPadding = 14.dp

private val HomeFilterChipEqualWidthMaxWidth = 600.dp

private val HomeGridItemSpacing = 16.dp

private val HomeGridContentTopPadding = 16.dp

private const val HomeGridSkeletonCount = 18

internal fun shouldPrefetchNextPage(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    columns: Int,
): Boolean = totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1 - columns

@Composable
fun HomeGridLayout(
    vm: HomeViewModel,
    topPadding: Dp,
    contentPadding: PaddingValues,
    pullState: PullToRefreshState,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val sorts by vm.sorts.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val sourceKey by vm.currentSource.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()
    val titleMeasurer = rememberTextMeasurer()
    val titleLine = with(LocalDensity.current) {
        titleMeasurer.measure("M", style = MaterialTheme.typography.titleSmall).size.height.toDp()
    }

    val activeSortId by vm.activeSortId.collectAsStateWithLifecycle()
    val selectedSortId = activeSortId.orEmpty()
    var filterOpen by remember { mutableStateOf(false) }
    val gridStates = remember(sourceKey?.key) { mutableMapOf<String, LazyGridState>() }

    val partition = partitions.firstOrNull { it.sort.id == selectedSortId }
    val sort = partition?.sort ?: sorts.firstOrNull { it.id == selectedSortId }
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            HomeSortTabRow(
                sorts = sorts,
                selectedId = selectedSortId,
                onSelect = { vm.ensureLoaded(it) },
                showFilter = sort?.filters?.isNotEmpty() == true,
                onFilter = { filterOpen = true },
                startInset = navStart,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Crossfade(
                targetState = selectedSortId,
                animationSpec = tween(220),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "homeGridTab",
            ) { tabId ->
            val tabPartition = partitions.firstOrNull { it.sort.id == tabId }
            val tabSort = tabPartition?.sort ?: sorts.firstOrNull { it.id == tabId }
            val tabGridState = gridStates.getOrPut(tabId) { LazyGridState() }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = LocalConfiguration.current.screenWidthDp >
                LocalConfiguration.current.screenHeightDp
            val gridColumns = WindowSize.smartGridColumns(
                availableWidthDp = (maxWidth - 32.dp - navStart).value.toInt(),
                availableHeightDp = (
                    maxHeight - HomeGridContentTopPadding - 24.dp
                    ).value.toInt(),
                minColumns = 3,
                minRows = 1,
                landscape = isLandscape,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = tabGridState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(
                    start = 16.dp + navStart,
                    end = 16.dp,
                    top = HomeGridContentTopPadding,
                    bottom = (if (isLandscape) 24.dp else 88.dp) +
                        contentPadding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(HomeGridItemSpacing),
            ) {
                if (tabSort != null && tabSort.filters.isNotEmpty()) {
                    item(key = "chips_${tabSort.id}", span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.padding(
                                top = (HomeGridItemSpacing - HomeGridContentTopPadding).coerceAtLeast(0.dp),
                            ),
                        ) {
                            HomeFilterChipsRow(sort = tabSort) { selection ->
                                tabPartition?.let { vm.applyFilter(it, selection) }
                            }
                        }
                    }
                }
                when (tabPartition?.state) {
                    null -> if (sorts.isEmpty()) {
                        item(key = "no_sort", span = { GridItemSpan(maxLineSpan) }) {
                            if (sortLoadFailed) {
                                HomeGridHint(
                                    text = stringResource(R.string.common_load_failed_network),
                                    onRetry = { vm.retrySort() },
                                )
                            } else {
                                HomeGridHint(text = stringResource(R.string.common_empty_content))
                            }
                        }
                    } else {
                        items(HomeGridSkeletonCount) { HomeGridSkeleton(titleLine) }
                    }

                    HomeViewModel.PartitionState.Idle, HomeViewModel.PartitionState.Loading -> items(HomeGridSkeletonCount) {
                        HomeGridSkeleton(titleLine)
                    }

                    HomeViewModel.PartitionState.Empty -> item(
                        key = "empty_$tabId",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        HomeGridHint(text = stringResource(R.string.common_empty_content))
                    }

                    HomeViewModel.PartitionState.Error -> item(
                        key = "error_$tabId",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        HomeGridHint(
                            text = stringResource(R.string.common_load_failed_network),
                            onRetry = { vm.retryPartition(tabPartition) },
                        )
                    }

                    HomeViewModel.PartitionState.Ready -> {
                        val videos = tabPartition.videos
                        itemsIndexed(
                            videos,
                            key = { index, video -> "${index}_${video.id}_${video.name}" },
                        ) { _, video ->
                            VodCard(
                                video = video,
                                onClick = { onCardClick(video) },
                                onLongClick = { onCardLongClick(video) },
                                style = VodCardStyle.Stacked,
                            )
                        }
                        item(key = "more_$tabId", span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(videos.size) {
                                if (tabPartition.hasMore) vm.loadMorePartition(tabPartition)
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(
                    if (tabPartition.hasMore) R.string.common_loading_more else R.string.common_no_more,
                ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (tabPartition?.state == HomeViewModel.PartitionState.Ready) {
                LaunchedEffect(tabGridState, tabId, tabPartition.videos.size) {
                    snapshotFlow { tabGridState.layoutInfo }
                        .first { info ->
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@first false
                            shouldPrefetchNextPage(last, info.totalItemsCount, gridColumns)
                        }
                    vm.loadMorePartition(tabPartition)
                }
            }
            }
            }
        }
        HomePullRefreshIndicator(
            state = pullState,
            isRefreshing = false,
            topPadding = topPadding + HomeGridTabRowHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    if (filterOpen) {
        sort?.let {
            FilterSheet(
                sort = it,
                onDismiss = { filterOpen = false },
                onConfirm = { selection -> partition?.let { p -> vm.applyFilter(p, selection) } },
            )
        }
    }
}

@Composable
private fun HomeGridSkeleton(titleLine: Dp) {
    Column {
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(modifier = Modifier.height(6.dp + titleLine))
    }
}

@Composable
private fun HomeGridHint(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.common_retry))
            }
        }
    }
}

@Composable
private fun HomeSortTabRow(
    sorts: List<MovieSort.SortData>,
    selectedId: String,
    onSelect: (String) -> Unit,
    showFilter: Boolean,
    onFilter: () -> Unit,
    startInset: Dp,
) {
    if (sorts.isEmpty()) {
        Spacer(modifier = Modifier.fillMaxWidth().height(HomeGridTabRowHeight))
        return
    }
    val selectedIndex = sorts.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeGridTabRowHeight)
            .padding(start = startInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 16.dp,
            divider = {},
            indicator = {
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(selectedIndex)
                        .fillMaxWidth()
                        .padding(horizontal = HomeTabIndicatorInset)
                        .height(HomeTabIndicatorHeight)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
        ) {
            sorts.forEach { item ->
                Tab(
                    selected = item.id == selectedId,
                    onClick = { onSelect(item.id.orEmpty()) },
                    text = {
                        Text(
                            text = item.name ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showFilter) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFilter),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_filter),
                    contentDescription = stringResource(R.string.common_filter),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun HomeFilterChipsRow(sort: MovieSort.SortData, onPick: (Map<String, String>) -> Unit) {
    val filter = sort.filters.firstOrNull() ?: return
    val entries = filter.values.orEmpty().entries.toList()
    val selectedKey = sort.filterSelect[filter.key]
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    fun pick(entry: Map.Entry<String, String>) {
        val next = HashMap(sort.filterSelect)
        if (selectedKey == entry.key) {
            next.remove(filter.key)
        } else {
            next[filter.key] = entry.key
        }
        onPick(next)
    }

    val neededWidth = entries.fold(0.dp) { acc, entry ->
        acc + with(density) { measurer.measure(entry.value, style).size.width.toDp() } +
            HomeFilterChipPadding * 2 + HomeFilterChipSpacing
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth <= HomeFilterChipEqualWidthMaxWidth &&
            neededWidth - HomeFilterChipSpacing + HomeFilterChipFitSlack <= maxWidth
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeFilterChipSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    HomeFilterChip(
                        text = entry.value,
                        selected = selectedKey == entry.key,
                        onClick = { pick(entry) },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeFilterChipSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(entries, key = { it.key }) { entry ->
                    HomeFilterChip(
                        text = entry.value,
                        selected = selectedKey == entry.key,
                        onClick = { pick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
            .clip(ContinuousCapsule)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceBright
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = HomeFilterChipPadding, vertical = 7.dp),
    )
}
