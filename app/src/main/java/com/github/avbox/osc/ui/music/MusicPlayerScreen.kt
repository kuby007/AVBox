package com.github.avbox.osc.ui.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.player.CastSheet
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.ImagePalette
import com.github.avbox.osc.ui.components.LocalTopBarGlassBackdrop
import com.github.avbox.osc.ui.components.PosterBackdrop
import com.github.avbox.osc.ui.components.ScallopShape
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.ui.components.glassTopBarSurface
import com.github.avbox.osc.ui.player.PlayerTipState
import com.github.avbox.osc.ui.theme.AppThemeState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import com.github.avbox.osc.ui.components.marqueeOnOverflow

@Composable
fun MusicPlayerScreen(
    state: MusicPlayerState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQueue: (Int) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleCollect: () -> Unit,
    onCast: () -> Unit,
) {
    var queueVisible by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf<Int?>(null) }
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme())
    val colorScheme = seed?.let { argb ->
        remember(argb, darkTheme) {
            AppThemeState.customScheme(argb, darkTheme, AppThemeState.config.style)
        }
    } ?: MaterialTheme.colorScheme

    MaterialTheme(colorScheme = colorScheme) {
        val backdrop = rememberLayerBackdrop(onDraw = { drawContent() })
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                PosterBackdrop(state.artwork)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                CompositionLocalProvider(LocalTopBarGlassBackdrop provides backdrop) {
                    MusicTopBar(
                        sourceName = state.sourceName,
                        onBack = onBack,
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth * 0.84f, maxHeight)
                    MusicCover(
                        artwork = state.artwork,
                        side = side,
                        onSeed = { argb -> if (argb != null) seed = argb },
                    )
                }

                MusicInfo(title = state.title, subtitle = state.subtitle)

                if (state.lyrics.isNotEmpty()) {
                    MusicLyrics(
                        lines = state.lyrics,
                        positionMs = state.positionMs,
                        onSeekLine = onSeek,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }

                MusicWaveProgress(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    waveSeed = state.waveSeed,
                    playing = state.playing,
                    onSeek = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp),
                )

                MusicControls(
                    playing = state.playing,
                    buffering = state.buffering,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )

                Spacer(Modifier.height(22.dp))

                CompositionLocalProvider(LocalTopBarGlassBackdrop provides backdrop) {
                    MusicBottomActions(
                        playMode = state.playMode,
                        collected = state.collected,
                        onCyclePlayMode = onCyclePlayMode,
                        onToggleCollect = onToggleCollect,
                        onCast = onCast,
                        onOpenQueue = { queueVisible = true },
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            MusicTipOverlay(state.tip)
        }

        if (queueVisible) {
            AVBoxBottomSheet(
                onDismissRequest = { queueVisible = false },
                title = stringResource(R.string.music_queue),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                isScrollable = false,
            ) {
                val queueListState = rememberLazyListState()
                LaunchedEffect(Unit) {
                    if (state.queueIndex in state.queue.indices) {
                        queueListState.scrollToItem(state.queueIndex)
                    }
                }
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(state.queue) { index, name ->
                        val current = index == state.queueIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    queueVisible = false
                                    onSelectQueue(index)
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name.ifEmpty { stringResource(R.string.music_song_index, index + 1) },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .marqueeOnOverflow(),
                            )
                            if (current) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        MusicCastOverlay(state)
    }
}

@Composable
private fun MusicCastOverlay(state: MusicPlayerState) {
    state.castSheet?.let { sheet ->
        CastSheet(sheet) { state.dismissCastSheet() }
    }
}

@Composable
private fun MusicTopBar(sourceName: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onBack)
        if (sourceName.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            MusicSourcePill(sourceName)
        }
    }
}

@Composable
private fun MusicSourcePill(sourceName: String) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .glassTopBarSurface(CapsuleShape, MaterialTheme.colorScheme.surfaceBright)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sourceName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 170.dp)
                .marqueeOnOverflow(),
        )
    }
}

@Composable
private fun MusicBottomActions(
    playMode: MusicPlayMode,
    collected: Boolean,
    onCyclePlayMode: () -> Unit,
    onToggleCollect: () -> Unit,
    onCast: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val modeIcon = when (playMode) {
        MusicPlayMode.SINGLE -> R.drawable.ic_repeat_one
        MusicPlayMode.LIST -> R.drawable.ic_repeat
        MusicPlayMode.ORDER -> R.drawable.ic_order_play
    }
    val segmentCount = 4
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(PlaybackControlsWidth)
                .height(66.dp)
                .clip(CapsuleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomActionItem(
                    iconRes = modeIcon,
                    label = stringResource(playMode.labelRes),
                    active = playMode != MusicPlayMode.ORDER,
                    activeColor = MaterialTheme.colorScheme.primary,
                    onActiveColor = MaterialTheme.colorScheme.onPrimary,
                    shape = segmentShape(index = 0, count = segmentCount),
                    onClick = onCyclePlayMode,
                    modifier = Modifier.weight(1f),
                )
                BottomActionItem(
                    iconRes = if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect,
                    label = stringResource(if (collected) R.string.detail_uncollect else R.string.common_collect),
                    active = collected,
                    activeColor = MaterialTheme.colorScheme.tertiary,
                    onActiveColor = MaterialTheme.colorScheme.onTertiary,
                    shape = segmentShape(index = 1, count = segmentCount),
                    onClick = onToggleCollect,
                    modifier = Modifier.weight(1f),
                )
                BottomActionItem(
                    iconRes = R.drawable.ic_detail_cast,
                    label = stringResource(R.string.common_cast),
                    active = false,
                    activeColor = MaterialTheme.colorScheme.primary,
                    onActiveColor = MaterialTheme.colorScheme.onPrimary,
                    shape = segmentShape(index = 2, count = segmentCount),
                    onClick = onCast,
                    modifier = Modifier.weight(1f),
                )
                BottomActionItem(
                    iconRes = R.drawable.ic_music_queue,
                    label = stringResource(R.string.music_episodes),
                    active = false,
                    activeColor = MaterialTheme.colorScheme.primary,
                    onActiveColor = MaterialTheme.colorScheme.onPrimary,
                    shape = segmentShape(index = 3, count = segmentCount),
                    onClick = onOpenQueue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomActionItem(
    @DrawableRes iconRes: Int,
    label: String,
    active: Boolean,
    activeColor: Color,
    onActiveColor: Color,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "bottomActionScale",
    )
    val background by animateColorAsState(
        targetValue = if (active) activeColor else MaterialTheme.colorScheme.surfaceBright,
        animationSpec = tween(durationMillis = 250),
        label = "bottomActionColor",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(scale)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (active) onActiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

private val SegmentCornerLarge = CornerSize(percent = 50)

private val SegmentCornerSmall = CornerSize(8.dp)

private fun segmentShape(index: Int, count: Int): Shape = when (index) {
    0 -> RoundedCornerShape(
        topStart = SegmentCornerLarge,
        topEnd = SegmentCornerSmall,
        bottomEnd = SegmentCornerSmall,
        bottomStart = SegmentCornerLarge,
    )

    count - 1 -> RoundedCornerShape(
        topStart = SegmentCornerSmall,
        topEnd = SegmentCornerLarge,
        bottomEnd = SegmentCornerLarge,
        bottomStart = SegmentCornerSmall,
    )

    else -> RoundedCornerShape(SegmentCornerSmall)
}

@Composable
private fun MusicCover(artwork: String, side: Dp, onSeed: (Int?) -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork.isNotEmpty()) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    val image = result.result.image
                    scope.launch {
                        val argb = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                        onSeed(argb)
                    }
                },
                onError = { onSeed(null) },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun MusicInfo(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier.marqueeOnOverflow(),
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .marqueeOnOverflow(),
            )
        }
    }
}

@Composable
private fun MusicLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val current = remember(lines, positionMs) { lyricIndex(lines, positionMs) }
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            Text(
                text = line.text,
                style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeekLine(line.timeMs) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MusicWaveProgress(
    positionMs: Long,
    durationMs: Long,
    waveSeed: Int,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val amplitudes = remember { WaveAmplitudeCache() }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragFraction else (positionMs.toFloat() / duration).coerceIn(0f, 1f)

    val phaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) {
            phaseState.value = 0f
            return@LaunchedEffect
        }
        var startNanos = 0L
        var lastEmitNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) {
                    startNanos = now
                    lastEmitNanos = now
                }
                if (now - lastEmitNanos >= WAVE_FRAME_INTERVAL_NANOS) {
                    lastEmitNanos = now
                    val elapsed = (now - startNanos) % WAVE_PERIOD_NANOS
                    phaseState.value = elapsed.toFloat() / WAVE_PERIOD_NANOS.toFloat() * TWO_PI
                }
            }
        }
    }

    val playedColor = MaterialTheme.colorScheme.onSurface
    val idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) {
                            val target = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek((target * durationMs).toLong())
                        }
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dragFraction = (dragFraction + delta / widthPx).coerceIn(0f, 1f)
                    },
                    onDragStarted = { offset ->
                        dragging = true
                        dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                    },
                    onDragStopped = {
                        dragging = false
                        if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                    },
                ),
        ) {
            val barWidth = 3.dp.toPx()
            val step = barWidth + 1.5.dp.toPx()
            val count = (size.width / step).toInt().coerceAtLeast(1)
            val centerY = size.height / 2f
            val minHeight = 4.dp.toPx()
            val maxHeight = (size.height - 2.dp.toPx()).coerceAtLeast(minHeight)
            val playedX = size.width * fraction
            val phase = phaseState.value
            val amplitudeValues = amplitudes.get(count, waveSeed)

            for (index in 0 until count) {
                val amplitude = amplitudeValues[index] *
                    (0.84f + 0.16f * sin(phase + index * 0.35f))
                val barHeight = (minHeight + (maxHeight - minHeight) * amplitude)
                    .coerceIn(minHeight, maxHeight)
                val x = index * step + barWidth / 2f
                drawRoundRect(
                    color = if (x <= playedX) playedColor else idleColor,
                    topLeft = Offset(x - barWidth / 2f, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(if (dragging) (dragFraction * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SkipButtonSize = 64.dp

private val PlayButtonWidth = 84.dp

private val PlayButtonHeight = 76.dp

private val SkipToPlayGap = 20.dp

private val PlayToSkipGap = 22.dp

private val PlaybackControlsWidth = SkipButtonSize * 2 + PlayButtonWidth + SkipToPlayGap + PlayToSkipGap

@Composable
private fun MusicControls(
    playing: Boolean,
    buffering: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScallopControlButton(
            iconRes = R.drawable.player_ic_prev,
            label = stringResource(R.string.music_previous),
            onClick = onPrevious,
        )
        Spacer(Modifier.width(SkipToPlayGap))
        Box(
            modifier = Modifier
                .size(width = PlayButtonWidth, height = PlayButtonHeight)
                .clip(PlayButtonShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            if (buffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                    ),
                    contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.width(PlayToSkipGap))
        ScallopControlButton(
            iconRes = R.drawable.player_ic_next,
            label = stringResource(R.string.music_next),
            onClick = onNext,
        )
    }
}

@Composable
private fun ScallopControlButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SkipButtonSize)
            .clip(ScallopIconShape)
            .background(MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}

private val CapsuleShape = RoundedCornerShape(percent = 50)

private val PlayButtonShape: Shape = EggShape()

private class EggShape(
    private val topControl: Float = 0.45f,
    private val bottomControl: Float = 0.60f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val rx = w / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            cubicTo(cx + rx * topControl, 0f, cx + rx, h * 0.18f, cx + rx, h * 0.55f)
            cubicTo(cx + rx, h * 0.85f, cx + rx * bottomControl, h, cx, h)
            cubicTo(cx - rx * bottomControl, h, cx - rx, h * 0.85f, cx - rx, h * 0.55f)
            cubicTo(cx - rx, h * 0.18f, cx - rx * topControl, 0f, cx, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

private val ScallopIconShape: Shape = ScallopShape()

private val TWO_PI = (2f * PI).toFloat()

private const val WAVE_PERIOD_NANOS = 2_000_000_000L

private const val WAVE_FRAME_INTERVAL_NANOS = 1_000_000_000L / 30

@Composable
private fun MusicTipOverlay(tip: PlayerTipState) {
    if (tip.msg.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tip.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = tip.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tip.err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private class WaveAmplitudeCache {
    private var seed = Int.MIN_VALUE
    private var count = -1
    private var values = FloatArray(0)

    fun get(count: Int, seed: Int): FloatArray {
        if (count != this.count || seed != this.seed) {
            this.count = count
            this.seed = seed
            values = FloatArray(count) { waveAmplitude(it, seed) }
        }
        return values
    }
}

private fun waveAmplitude(index: Int, seed: Int): Float {
    val fine = waveNoise(index, seed)
    val coarse = waveNoise(index / 5, seed + 17)
    return (0.35f + 0.65f * (0.6f * fine + 0.4f * coarse)).coerceIn(0.15f, 1f)
}

private fun waveNoise(value: Int, seed: Int): Float {
    var x = value * 374761393 + seed * 668265263
    x = (x xor (x shr 13)) * 1274126177
    x = x xor (x shr 16)
    return (x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
}

internal fun lyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

internal fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000
    val minute = safe / 60
    val second = safe % 60
    return "%d:%02d".format(minute, second)
}
