@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.avbox.osc.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.MovieSort
import com.github.avbox.osc.ui.components.FilterSheet
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.ProvideTmdbPosterLookup
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.VodCard
import com.github.avbox.osc.ui.components.VodCardMenu
import com.github.avbox.osc.ui.components.VodCardStyle
import com.github.avbox.osc.ui.components.glassTopBarSurface
import com.github.avbox.osc.ui.components.rememberVodCardMenuState
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.page.PartitionListViewModel
import com.github.avbox.osc.ui.page.dispatchVodCardClick
import com.github.avbox.osc.ui.page.openVodCardOrDetail
import com.github.avbox.osc.ui.page.shouldPrefetchNextPage
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.avbox.osc.ui.page.jumpToSearch
import com.github.avbox.osc.ui.components.marqueeOnOverflow

class PartitionListActivity : BaseActivity() {

    private val vm: PartitionListViewModel by lazy {
        ViewModelProvider(this)[PartitionListViewModel::class.java]
    }

    private var mode by mutableStateOf(MODE_PARTITION)
    private var entryMode by mutableStateOf(MODE_PARTITION)

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SORT = "sort_json"
        private const val EXTRA_VIDEOS = "videos_json"
        private const val EXTRA_SOURCE = "source_key"
        const val MODE_PARTITION = "partition"
        const val MODE_SEARCH = "search"
        const val MODE_FOLDER = "folder"

        fun startForPartition(context: Context, sort: MovieSort.SortData) {
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_PARTITION)
                putExtra(EXTRA_TITLE, sort.name ?: "")
                putExtra(EXTRA_SORT, Gson().toJson(sort))
            })
        }

        fun startForFolder(context: Context, folderId: String, folderName: String, sourceKey: String?) {
            val sort = MovieSort.SortData(folderId, folderName)
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_FOLDER)
                putExtra(EXTRA_TITLE, folderName)
                putExtra(EXTRA_SORT, Gson().toJson(sort))
                putExtra(EXTRA_SOURCE, sourceKey)
            })
        }

        fun startForSearch(context: Context, videos: List<Movie.Video>, title: String) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val videosJson = Gson().toJson(videos)
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                        putExtra(EXTRA_MODE, MODE_SEARCH)
                        putExtra(EXTRA_TITLE, title)
                        putExtra(EXTRA_VIDEOS, videosJson)
                    })
                }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
        entryMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARTITION
        mode = entryMode
        openFromIntent(intent)
        val searchVideosJson = intent.getStringExtra(EXTRA_VIDEOS)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    PartitionListScreen(
                        mode = mode,
                        videosJson = searchVideosJson,
                        onBack = { goBack() },
                        vm = vm,
                    )
                }
            }
        }
    }

    private fun goBack() {
        if (!vm.back()) {
            finish()
            return
        }
        mode = if (vm.isRoot() && entryMode != MODE_SEARCH) entryMode else MODE_FOLDER
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARTITION
        openFromIntent(intent)
    }

    private fun openFromIntent(intent: Intent?) {
        val data = intent ?: return
        if ((data.getStringExtra(EXTRA_MODE) ?: MODE_PARTITION) == MODE_SEARCH) {
            vm.setStaticTitle(data.getStringExtra(EXTRA_TITLE) ?: "")
            return
        }
        val sortJson = data.getStringExtra(EXTRA_SORT) ?: return
        vm.open(
            Gson().fromJson(sortJson, MovieSort.SortData::class.java),
            data.getStringExtra(EXTRA_SOURCE),
            data.getStringExtra(EXTRA_TITLE) ?: "",
        )
    }
}

@Composable
private fun PartitionListScreen(mode: String, videosJson: String?, onBack: () -> Unit, vm: PartitionListViewModel) {
    val context = LocalContext.current
    var filterOpen by remember { mutableStateOf(false) }
    val ui by vm.ui.collectAsState()
    val title by vm.title.collectAsState()
    val level by vm.level.collectAsState()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    val searchVideos by produceState<List<Movie.Video>>(emptyList(), videosJson) {
        val json = videosJson ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val type = object : TypeToken<List<Movie.Video>>() {}.type
            Gson().fromJson<List<Movie.Video>>(json, type)
        }
    }

    AppTopBarScaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContent = {
            Text(
                modifier = Modifier.marqueeOnOverflow(),
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            BarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back)) { onBack() }
        },
        actions = {
            if (mode == PartitionListActivity.MODE_PARTITION) {
                val selectedCount = vm.sort?.filterSelectCount() ?: 0
                BarActionBox(
                    R.drawable.ic_filter,
                    stringResource(R.string.common_filter),
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) { filterOpen = true }
            }
        },
    ) { topPad, _ ->
        when {
            mode == PartitionListActivity.MODE_SEARCH -> ProvideTmdbPosterLookup(vm) {
                VideoGrid(
                    videos = searchVideos,
                    onCardClick = { video -> context.openVodCardOrDetail(video) },
                    onCardLongClick = { video -> vodMenu.show(video) },
                    onLoadMore = {},
                    topPadding = topPad - 20.dp,
                )
            }

            ui.state == PartitionListViewModel.State.Loading -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
                loadingContent = { ContainedLoadingIndicator(Modifier.size(64.dp)) },
            )

            ui.state == PartitionListViewModel.State.Empty -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = stringResource(R.string.common_empty_content),
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> ProvideTmdbPosterLookup(vm) {
                VideoGrid(
                    videos = ui.videos,
                    onCardClick = { video -> context.dispatchVodCardClick(video, onAction = { vm.runAction(it) }) },
                    onCardLongClick = { video -> vodMenu.show(video) },
                    onLoadMore = { vm.loadMore() },
                    enableLoadMore = true,
                    topPadding = topPad - 20.dp,
                    resetKey = level,
                )
            }
        }
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }

    if (filterOpen) {
        vm.sort?.let { sort ->
            FilterSheet(
                sort = sort,
                onDismiss = { filterOpen = false },
                onConfirm = { selection ->
                    vm.applyFilter(selection)
                },
            )
        }
    }
}

@Composable
private fun BarActionBox(
    iconRes: Int,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VideoGrid(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit = {},
    enableLoadMore: Boolean = false,
    topPadding: Dp = 0.dp,
    resetKey: Any? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val gridColumns = WindowSize.gridColumns(
        availableWidthDp = (maxWidth - 32.dp).value.toInt(),
        minColumns = 3,
    )
    val gridState = rememberLazyGridState()
    LaunchedEffect(resetKey) {
        if (resetKey != null) gridState.scrollToItem(0)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(videos, key = { i, video -> "${i}_${video.id}_${video.name}" }) { _, video ->
            VodCard(
                video = video,
                onClick = { onCardClick(video) },
                onLongClick = { onCardLongClick(video) },
                style = VodCardStyle.Stacked,
            )
        }
        if (enableLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(videos.size) { onLoadMore() }
            }
        }
    }
    if (enableLoadMore) {
        LaunchedEffect(gridState, videos.size) {
            snapshotFlow { gridState.layoutInfo }
                .first { info ->
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@first false
                    shouldPrefetchNextPage(last, info.totalItemsCount, gridColumns)
                }
            onLoadMore()
        }
    }
    }
}
