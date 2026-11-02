@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.avbox.osc.ui.page

import com.github.avbox.osc.api.SearchSourcesState
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.MovieSort
import com.github.avbox.osc.net.SiteSearch
import com.github.avbox.osc.ui.ConfigManageActivity
import com.github.avbox.osc.ui.PartitionListActivity
import com.github.avbox.osc.ui.SearchActivity
import com.github.avbox.osc.ui.WindowSize
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.AVBoxOptionMenuAction
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.HeroSpotlight
import com.github.avbox.osc.ui.components.HomeBackdrop
import com.github.avbox.osc.ui.components.HomeHeroBackdrop
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.ProvideTmdbPosterLookup
import com.github.avbox.osc.ui.components.homeHeroBackdropSupported
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.SearchField
import com.github.avbox.osc.ui.components.SearchSettingsSheet
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.SegmentOption
import com.github.avbox.osc.ui.components.SegmentStyle
import com.github.avbox.osc.ui.components.CapsuleSegmentedButton
import com.github.avbox.osc.ui.components.SettingsOptionRow
import com.github.avbox.osc.ui.components.DenseSegmentContentPadding
import com.github.avbox.osc.ui.components.SettingsRow
import com.github.avbox.osc.ui.components.SkeletonBox
import com.github.avbox.osc.ui.components.VodCard
import com.github.avbox.osc.ui.components.VodCardMenu
import com.github.avbox.osc.ui.components.VodCardStyle
import com.github.avbox.osc.ui.components.clearFocusOnDrag
import com.github.avbox.osc.ui.components.glassTopBarSurface
import com.github.avbox.osc.ui.components.heroSpotlightHeight
import com.github.avbox.osc.ui.components.rememberVodCardMenuState
import com.github.avbox.osc.ui.page.jumpToSearch
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.ui.theme.cardContainer
import com.github.avbox.osc.util.HomeSettings
import com.github.avbox.osc.util.SiteExt
import com.github.avbox.osc.util.SiteProxy
import com.kyant.capsule.ContinuousCapsule
import kotlin.math.roundToInt
import com.github.avbox.osc.ui.SiteCardShapeLeft
import com.github.avbox.osc.ui.SiteCardShapeRight
import com.github.avbox.osc.ui.SiteCardShapeSingle
import com.github.avbox.osc.ui.SiteKind
import com.github.avbox.osc.ui.SiteSourceCard
import com.github.avbox.osc.ui.siteKindOf
import com.github.avbox.osc.ui.components.marqueeOnOverflow
import androidx.compose.foundation.layout.fillMaxHeight

private val HomeSourceCapsuleMaxWidth = 240.dp

private const val CapsuleLogoZoom = 1.5f

private val HomeTopBarControlSpacing = 8.dp

private val HomeCapsuleArrowInset = 5.dp

@Composable
fun HomePage(
    vm: HomeViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val sorts by vm.sorts.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState(
        lookupCollected = vm::isVideoCollected,
        onCollectToggle = vm::toggleVideoCollect,
    )

    val listState = rememberLazyListState()

    val pullState = rememberPullToRefreshState()

    val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()
    val homeLayout by HomeSettings.layoutFlow.collectAsStateWithLifecycle()
    LaunchedEffect(homeLayout) { vm.onLayoutChanged() }
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    var showSourceSheet by remember { mutableStateOf(false) }
    var proxyOn by remember(currentSource) { mutableStateOf(SiteProxy.isEnabled(currentSource)) }
    // 从站点管理 / 配置管理等页面回到首页时，按当前站点重新套一次代理：
    // 那边改过配置后，首页的代理状态可能与新配置不一致（旧的还挂着、新的没生效）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentSource) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                SiteProxy.apply(currentSource)
                proxyOn = SiteProxy.isEnabled(currentSource)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var extValue by remember(currentSource) { mutableStateOf(SiteExt.current(currentSource)) }
    var extDialog by remember { mutableStateOf(false) }
    var proxyUrlDialog by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    var backdropPic by remember { mutableStateOf("") }
    val heroBackdropEnabled = homeLayout == HomeSettings.HomeLayout.Horizontal &&
        homeHeroBackdropSupported
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme())
    val backdropContainer by animateColorAsState(
        targetValue = backdropSeed
            ?.takeIf { homeLayout == HomeSettings.HomeLayout.Horizontal }
            ?.let { Color(HomeBackdrop.surfaceOf(it, darkTheme)) }
            ?: MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(200),
        label = "homeBackdrop",
    )

    AppTopBarScaffold(
        collapseEnabled = false,
        topBarStartInset = navStart,
        topScrimEnabled = homeLayout != HomeSettings.HomeLayout.Horizontal,
        containerColor = backdropContainer,
        titleContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .widthIn(
                            max = minOf(
                                HomeSourceCapsuleMaxWidth,
                                maxWidth - HomeTopBarControlSpacing,
                            ),
                        )
                        .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
                        .heightIn(min = 40.dp)
                        .clickable {
                            vm.refreshSubscribes()
                            showSourceSheet = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val capsuleLogo = remember { ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .drawBehind {
                                capsuleLogo?.let { drawable ->
                                    val w = size.width
                                    val h = size.height
                                    drawable.setBounds(
                                        (w / 2 - w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 - h * CapsuleLogoZoom / 2).roundToInt(),
                                        (w / 2 + w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 + h * CapsuleLogoZoom / 2).roundToInt(),
                                    )
                                    drawable.draw(drawContext.canvas.nativeCanvas)
                                }
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentSource?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.home_subscription_source),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .marqueeOnOverflow(),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        actions = {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.surfaceBright),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { showSearchSettings = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.search_settings),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            context.startActivity(Intent(context, SearchActivity::class.java))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.common_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // 与左侧搜索按钮同款：不套圆圈，只靠图标颜色区分开/关
                val extOn = extValue.isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { extDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.player_ic_params),
                        contentDescription = stringResource(R.string.home_site_ext),
                        tint = if (extOn) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(if (extOn) 24.dp else 20.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .combinedClickable(
                            onClick = {
                                val bean = currentSource ?: return@combinedClickable
                                val on = SiteProxy.toggle(bean)
                                proxyOn = on
                                Toast.makeText(
                                    context,
                                    context.getString(if (on) R.string.toast_proxy_on else R.string.toast_proxy_off),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                vm.reload()
                            },
                            onLongClick = { proxyUrlDialog = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_proxy),
                        contentDescription = stringResource(R.string.home_proxy),
                        tint = if (proxyOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(if (proxyOn) 24.dp else 20.dp),
                    )
                }
            }
        },
    ) { topPad, _ ->
        if (heroBackdropEnabled && backdropPic.isNotEmpty()) {
            HomeHeroBackdrop(pic = backdropPic, scrimColor = backdropContainer)
        }
        when {
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad, bottom = navBottom),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_record),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.home_no_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ConfigManageActivity.start(context) }) {
                            Text(stringResource(R.string.config_add_subscribe))
                        }
                    }
                }
            }
            else -> {
            // 横屏且宽度足够时改左右分栏：左侧 Hero，右侧信息流
            val homeSplitLandscape = screenWidthDp > screenHeightDp && screenWidthDp >= 640
            val homeHeroPanelWidth = (screenWidthDp * 0.38f).dp
            if (homeLayout == HomeSettings.HomeLayout.Vertical) {
                ProvideTmdbPosterLookup(vm) {
                    HomeGridLayout(
                        vm = vm,
                        topPadding = topPad,
                        contentPadding = contentPadding,
                        pullState = pullState,
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video -> vodMenu.show(video) },
                    )
                }
            } else if (homeSplitLandscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(homeHeroPanelWidth)
                            .fillMaxHeight()
                            .padding(top = topPad),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty() -> {
                                ProvideTmdbPosterLookup(vm) {
                                    HeroSpotlight(
                                        videos = rec.videos.take(HomeViewModel.HERO_VIDEO_COUNT),
                                        backdropColor = backdropContainer,
                                        onBackdropSeed = { argb -> backdropSeed = argb },
                                        onPosterPic = { pic -> backdropPic = pic },
                                        onCardClick = { video -> handleCardClick(vm, video, context) },
                                        fadeToBackdrop = heroBackdropEnabled,
                                    )
                                }
                            }
                            rec.state == HomeViewModel.PartitionState.Loading -> {
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(heroSpotlightHeight(screenHeightDp, screenWidthDp)),
                                )
                            }
                            rec.state == HomeViewModel.PartitionState.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(heroSpotlightHeight(screenHeightDp, screenWidthDp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadStateBox(
                                        state = LoadState.Error(),
                                        emptyText = "",
                                        errorText = stringResource(R.string.home_load_timeout),
                                        retryText = stringResource(R.string.common_retry),
                                        onRetry = { vm.loadHome() },
                                    )
                                }
                            }
                            else -> {
                                Spacer(modifier = Modifier.height(topPad + 8.dp))
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pullToRefresh(
                                isRefreshing = false,
                                state = pullState,
                                onRefresh = { vm.reload() },
                            ),
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            top = 0.dp,
                            bottom = 88.dp + navBottom,
                        ),
                    ) {
                    item(key = "cats") {
                        HomeCategoryChips(
                            sorts = sorts,
                            onOpenSort = { sort -> PartitionListActivity.startForPartition(context, sort) },
                            modifier = Modifier.padding(start = navStart),
                        )
                    }
                    if (rec.state != HomeViewModel.PartitionState.Empty &&
                        (rec.state == HomeViewModel.PartitionState.Loading ||
                            rec.videos.size > HomeViewModel.HERO_VIDEO_COUNT)
                    ) {
                        item(key = "rec") {
                            ProvideTmdbPosterLookup(vm) {
                                PartitionSection(
                                    title = stringResource(R.string.home_recommend),
                                    state = rec.state,
                                    videos = rec.videos.drop(HomeViewModel.HERO_VIDEO_COUNT),
                                    onLoadMore = {},
                                    onCardClick = { video -> handleCardClick(vm, video, context) },
                                    onCardLongClick = { video -> vodMenu.show(video) },
                                    modifier = Modifier.padding(start = navStart),
                                )
                            }
                        }
                    }
                    if (sortLoadFailed) {
                        item(key = "sort_error") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = navStart)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.common_load_failed_network),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.retrySort() }) {
                                    Text(text = stringResource(R.string.common_retry))
                                }
                            }
                        }
                    }
                    items(partitions, key = { it.sort.id.orEmpty() }) { p ->
                        ProvideTmdbPosterLookup(vm) {
                            PartitionSection(
                                title = p.sort.name ?: "",
                                state = p.state,
                                videos = p.videos,
                                onLoadMore = { vm.loadMorePartition(p) },
                                onCardClick = { video -> handleCardClick(vm, video, context) },
                                onCardLongClick = { video -> vodMenu.show(video) },
                                onOpenAll = {
                                    PartitionListActivity.startForPartition(context, p.sort)
                                },
                                onRetry = { vm.retryPartition(p) },
                                modifier = Modifier.padding(start = navStart),
                            )
                        }
                    }
                    }
                }
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 0.dp,
                    bottom = 88.dp + navBottom,
                ),
            ) {
                item(key = "hero") {
                    when {
                        rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty() -> {
                            ProvideTmdbPosterLookup(vm) {
                                HeroSpotlight(
                                    videos = rec.videos.take(HomeViewModel.HERO_VIDEO_COUNT),
                                    backdropColor = backdropContainer,
                                    onBackdropSeed = { argb -> backdropSeed = argb },
                                    onPosterPic = { pic -> backdropPic = pic },
                                    onCardClick = { video -> handleCardClick(vm, video, context) },
                                    fadeToBackdrop = heroBackdropEnabled,
                                )
                            }
                        }
                        rec.state == HomeViewModel.PartitionState.Loading -> {
                            SkeletonBox(
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .height(heroSpotlightHeight(screenHeightDp, screenWidthDp)),
                            )
                        }
                        rec.state == HomeViewModel.PartitionState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .height(heroSpotlightHeight(screenHeightDp, screenWidthDp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadStateBox(
                                    state = LoadState.Error(),
                                    emptyText = "",
                                    errorText = stringResource(R.string.home_load_timeout),
                                    retryText = stringResource(R.string.common_retry),
                                    onRetry = { vm.loadHome() },
                                )
                            }
                        }
                        else -> {
                            Spacer(modifier = Modifier.height(topPad + 8.dp))
                        }
                    }
                }
                item(key = "cats") {
                    HomeCategoryChips(
                        sorts = sorts,
                        onOpenSort = { sort -> PartitionListActivity.startForPartition(context, sort) },
                        modifier = Modifier.padding(start = navStart),
                    )
                }
                if (rec.state != HomeViewModel.PartitionState.Empty &&
                    (rec.state == HomeViewModel.PartitionState.Loading ||
                        rec.videos.size > HomeViewModel.HERO_VIDEO_COUNT)
                ) {
                    item(key = "rec") {
                        ProvideTmdbPosterLookup(vm) {
                            PartitionSection(
                                title = stringResource(R.string.home_recommend),
                                state = rec.state,
                                videos = rec.videos.drop(HomeViewModel.HERO_VIDEO_COUNT),
                                onLoadMore = {},
                                onCardClick = { video -> handleCardClick(vm, video, context) },
                                onCardLongClick = { video -> vodMenu.show(video) },
                                modifier = Modifier.padding(start = navStart),
                            )
                        }
                    }
                }
                if (sortLoadFailed) {
                    item(key = "sort_error") {
                        Row(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(start = navStart)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.common_load_failed_network),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.retrySort() }) {
                                Text(text = stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
                items(partitions, key = { it.sort.id.orEmpty() }) { p ->
                    ProvideTmdbPosterLookup(vm) {
                        PartitionSection(
                            title = p.sort.name ?: "",
                            state = p.state,
                            videos = p.videos,
                            onLoadMore = { vm.loadMorePartition(p) },
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                            onCardLongClick = { video -> vodMenu.show(video) },
                            onOpenAll = {
                                PartitionListActivity.startForPartition(context, p.sort)
                            },
                            onRetry = { vm.retryPartition(p) },
                            modifier = Modifier.padding(start = navStart),
                        )
                    }
                }
            }
        }
        }

        if (sources.isNotEmpty() && homeLayout == HomeSettings.HomeLayout.Horizontal) {
            HomePullRefreshIndicator(
                state = pullState,
                isRefreshing = false,
                topPadding = topPad,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showSourceSheet) {
        // 横屏时屏幕"高度"其实很短（手机横放只有 360dp 左右），
        // 仍按 0.72 取的话弹窗只剩两百多 dp，装不下搜索框+分类+列表，
        // 底部配置管理会把列表挤没。横屏改按接近满屏给高度。
        val isLandscape = screenWidthDp > screenHeightDp
        // 横屏时按 screenHeightDp 乘比例算出来的高度会超出实际窗口
        // （系统栏还要占掉一截），底部配置管理被挤出屏幕、列表只剩一行。
        // 这里横屏直接按父容器实际高度取 96%，竖屏维持原来的 72%。
        val sheetModifier = if (isLandscape) {
            Modifier.fillMaxHeight(0.96f)
        } else {
            Modifier.height((screenHeightDp * 0.72f).dp)
        }
        AVBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            modifier = sheetModifier,
            title = stringResource(R.string.home_subscription_source),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            val dismissAnimated = LocalSheetDismiss.current
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val subscribes by vm.subscribeItems.collectAsStateWithLifecycle()
            val activeSubscribeIndex by vm.activeSubscribeIndex.collectAsStateWithLifecycle()
            var query by remember { mutableStateOf("") }
            val filtered = remember(sources, query) { SiteSearch.filter(sources, query) }
            val subscribeOptions = remember(subscribes) { subscribes.map { it.name.ifEmpty { it.url } } }
            val sourceGrid by HomeSettings.sourceGridFlow.collectAsStateWithLifecycle()
            val listState = rememberLazyListState()
            val selectedIndex = filtered.indexOfFirst { it.key == currentSource?.key }
            LaunchedEffect(query.isEmpty()) {
                if (query.isEmpty() && selectedIndex > 0) listState.scrollToItem(selectedIndex)
            }
            fun switchSubscribe(item: SubscribeSource) {
                if (vm.isSubscribeDisabled(item)) {
                    Toast.makeText(context, R.string.toast_source_auto_disabled, Toast.LENGTH_LONG).show()
                } else {
                    dismissAnimated()
                    vm.switchSubscribe(item)
                }
            }
            // 横屏左右分栏：左侧搜索+分类+站点列表，右侧配置管理
            // 竖屏维持自上而下的单列，避免列表被底部配置管理挤没
            val splitLandscape = isLandscape && screenWidthDp >= 640
            var sourceKind by remember { mutableStateOf(SiteKind.ALL) }
            val visible = remember(filtered, sourceKind) {
                filtered.filter { sourceKind == SiteKind.ALL || siteKindOf(it.api) == sourceKind }
            }
            // 多列模式按屏宽算最佳列数：横屏宽了就多列，别把空间浪费掉
            val perRow = if (sourceGrid) {
                val listWidth = if (splitLandscape) (screenWidthDp * 0.64f).toInt() - 32 else screenWidthDp - 32
                WindowSize.listColumns(listWidth, minColumns = 2, maxColumns = 6)
            } else {
                1
            }
            val topControls: @Composable () -> Unit = {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { keyboard?.hide() },
                    hint = stringResource(R.string.home_site_search_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = if (isLandscape) 8.dp else 12.dp),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (subscribeOptions.isNotEmpty()) {
                                AVBoxOptionMenuAction(
                                    options = subscribeOptions,
                                    selectedIndex = activeSubscribeIndex,
                                    onSelect = { index -> subscribes.getOrNull(index)?.let(::switchSubscribe) },
                                    contentDescription = stringResource(R.string.home_subscription_source),
                                )
                            }
                            IconButton(onClick = { vm.refreshSourceList() }) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.common_refresh),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(onClick = { HomeSettings.setSourceGrid(!sourceGrid) }) {
                                Icon(
                                    painter = painterResource(
                                        if (sourceGrid) R.drawable.ic_layout_vertical else R.drawable.ic_layout_horizontal,
                                    ),
                                    contentDescription = stringResource(
                                        if (sourceGrid) R.string.config_list_single else R.string.config_list_double,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = if (isLandscape) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleSegmentedButton(
                        options = SiteKind.values().map { SegmentOption(it.label, it) },
                        selectedValue = sourceKind,
                        onOptionSelected = { sourceKind = it },
                        style = SegmentStyle.Separated,
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                        pressMorph = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        contentPadding = DenseSegmentContentPadding,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                    )
                }
            }
            val siteListPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                val gridLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                val gridRowHeight = 52.dp
                Box(
                    modifier = panelModifier
                        .fillMaxWidth()
                        .padding(horizontal = if (splitLandscape) 0.dp else 16.dp)
                        .drawBehind {
                            val rowH = gridRowHeight.toPx()
                            val colW = size.width / perRow
                            var y = rowH
                            while (y < size.height) {
                                drawLine(
                                    color = gridLineColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                                y += rowH
                            }
                            for (c in 1 until perRow) {
                                val x = colW * c
                                drawLine(
                                    color = gridLineColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                        .clearFocusOnDrag(focusManager)
                        .pointerInput(focusManager) {
                            detectTapGestures(onTap = { focusManager.clearFocus() })
                        },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (visible.isEmpty() && query.isNotEmpty()) {
                            item {
                                LoadStateBox(
                                    state = LoadState.Empty,
                                    emptyText = stringResource(R.string.home_site_search_empty),
                                    errorText = "",
                                    retryText = "",
                                    emptyIconRes = R.drawable.ic_empty_record,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                )
                            }
                        }
                        items(visible.chunked(perRow)) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                row.forEachIndexed { index, bean ->
                                    val selected = bean.key == currentSource?.key
                                    SiteSourceCard(
                                        name = bean.name?.takeIf { it.isNotEmpty() } ?: bean.key.orEmpty(),
                                        selected = selected,
                                        dotOnLeft = index == 0,
                                        shape = if (perRow == 2) {
                                            if (index == 0) SiteCardShapeLeft else SiteCardShapeRight
                                        } else {
                                            SiteCardShapeSingle
                                        },
                                        onClick = {
                                            if (!selected) {
                                                vm.switchSource(bean)
                                            }
                                            dismissAnimated()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size < perRow) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            val configPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                SettingsGroup(
                    title = null,
                    modifier = panelModifier
                        .padding(
                            start = if (splitLandscape) 0.dp else 16.dp,
                            end = if (splitLandscape) 0.dp else 16.dp,
                            top = if (isLandscape) 6.dp else 10.dp,
                            bottom = if (isLandscape) 8.dp else 16.dp,
                        ),
                ) {
                    SettingsCard(
                        position = SettingsCardPosition.SINGLE,
                        color = MaterialTheme.colorScheme.surfaceBright,
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.settings_config_manage),
                            onClick = {
                                dismissAnimated()
                                ConfigManageActivity.start(context)
                            },
                        )
                    }
                }
            }
            if (splitLandscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(0.64f)
                            .fillMaxHeight()
                            .padding(start = 16.dp, end = 8.dp),
                    ) {
                        topControls()
                        siteListPanel(Modifier.weight(1f).fillMaxWidth())
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.36f)
                            .fillMaxHeight()
                            .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    ) {
                        configPanel(Modifier.fillMaxWidth())
                    }
                }
            } else {
                topControls()
                siteListPanel(Modifier.weight(1f).fillMaxWidth())
                configPanel(Modifier.fillMaxWidth())
            }
        }
    }

    if (showSearchSettings) {
        SearchSettingsSheet(
            onDismiss = { showSearchSettings = false },
            onSelectionChanged = { SearchSourcesState.refresh() },
        )
    }

    if (extDialog) {
        TextEditDialog(
            title = stringResource(R.string.home_site_ext),
            initialText = extValue,
            placeholder = stringResource(R.string.home_site_ext_hint),
            singleLine = false,
            fieldHeight = 132.dp,
            onDismiss = { extDialog = false },
            onConfirm = { text ->
                extDialog = false
                val bean = currentSource ?: return@TextEditDialog
                bean.ext = text
                SiteExt.set(bean.key, text)
                extValue = text
                Toast.makeText(context, context.getString(R.string.toast_site_ext_saved), Toast.LENGTH_SHORT).show()
                vm.reload()
            },
        )
    }

    if (proxyUrlDialog) {
        TextEditDialog(
            title = stringResource(R.string.settings_proxy_url),
            initialText = SiteProxy.configuredUrl(),
            placeholder = SiteProxy.DEFAULT_URL,
            onDismiss = { proxyUrlDialog = false },
            onConfirm = { text ->
                proxyUrlDialog = false
                SiteProxy.setConfiguredUrl(text)
                SiteProxy.apply(currentSource)
                proxyOn = SiteProxy.isEnabled(currentSource)
                Toast.makeText(context, context.getString(R.string.toast_proxy_url_saved), Toast.LENGTH_SHORT).show()
                vm.reload()
            },
        )
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

@Composable
fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

@Composable
private fun PartitionSection(
    title: String,
    state: HomeViewModel.PartitionState,
    videos: List<Movie.Video>,
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    cardWidth: Dp = 110.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                HomeCapsule(
                    text = stringResource(R.string.common_all),
                    onClick = onOpenAll,
                    trailingArrow = true,
                )
            }
        }
        when (state) {
            HomeViewModel.PartitionState.Idle -> Unit

            HomeViewModel.PartitionState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            HomeViewModel.PartitionState.Empty -> Text(
                text = stringResource(R.string.common_empty_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HomeViewModel.PartitionState.Error -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_load_failed_network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry?.invoke() }) {
                        Text(text = stringResource(R.string.common_retry))
                    }
                }
            }

            HomeViewModel.PartitionState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(videos) { index, video ->
                    VodCard(
                        video = video,
                        onClick = { onCardClick(video) },
                        onLongClick = { onCardLongClick(video) },
                        modifier = Modifier.width(cardWidth),
                        style = VodCardStyle.Stacked,
                    )
                }
                item(key = "more_$title") {
                    LaunchedEffect(videos.size) { onLoadMore() }
                }
            }
        }
    }
}

@Composable
private fun HomeCategoryChips(
    sorts: List<MovieSort.SortData>,
    onOpenSort: (MovieSort.SortData) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sorts.isEmpty()) return
    Column(modifier = modifier.padding(top = 24.dp, bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.home_all_categories),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sorts) { sort ->
                HomeCapsule(
                    text = sort.name ?: "",
                    onClick = { onOpenSort(sort) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeCapsule(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    trailingArrow: Boolean = false,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .offset(x = HomeCapsuleArrowInset),
            )
        }
    }
}
