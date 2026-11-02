package com.github.avbox.osc.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.ui.theme.ContinuousDialog
import com.github.avbox.osc.ui.theme.ContinuousSheetStart
import com.github.avbox.osc.ui.theme.ContinuousSheetTop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    slideFromEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = OverlayRequest(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    title = title,
    containerColor = containerColor,
    isScrollable = isScrollable,
    headerContent = headerContent,
    variant = if (slideFromEnd) SheetVariant.END else SheetVariant.BOTTOM,
    content = content,
)

@Composable
internal fun OverlayRequest(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    variant: SheetVariant = SheetVariant.BOTTOM,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    if (host == null) {
        SheetOverlay(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            containerColor = containerColor,
            isScrollable = isScrollable,
            headerContent = headerContent,
            variant = variant,
            dismissible = dismissible,
            content = content,
        )
    } else {
        val id = remember { Any() }
        SideEffect {
            host.submit(
                SheetRequest(
                    id, onDismissRequest, modifier, title, containerColor, isScrollable, headerContent, variant,
                    dismissible, content,
                ),
            )
        }
        DisposableEffect(id) {
            onDispose { host.clear(id) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxOptionSheet(
    onDismissRequest: () -> Unit,
    title: String?,
    options: List<String>,
    selected: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        var accepted by remember { mutableStateOf(false) }
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SettingsCard(
                    position = optionCardPosition(index, options.size),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = option,
                        selected = option == selected,
                        onClick = {
                            if (!accepted) {
                                accepted = true
                                onSelect(option)
                                dismissAnimated()
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun optionCardPosition(index: Int, size: Int): SettingsCardPosition = when {
    size <= 1 -> SettingsCardPosition.SINGLE
    index == 0 -> SettingsCardPosition.FIRST
    index == size - 1 -> SettingsCardPosition.LAST
    else -> SettingsCardPosition.MIDDLE
}

private val SheetMaxWidth = 640.dp

private const val SheetMaxHeightFraction = 0.9f

private const val SheetMaxHeightFractionLandscape = 0.96f

private const val SHEET_END_WIDTH_FRACTION = 0.45f

private const val SHEET_SLIDE_DURATION_MS = 280

private const val DIALOG_FADE_DURATION_MS = 220
private const val DIALOG_ENTER_SCALE = 0.90f
private const val DIALOG_SCRIM_ALPHA = 0.6f
private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DIALOG_HORIZONTAL_MARGIN = 24.dp

private const val SHEET_DRAG_DISMISS_FRACTION = 0.25f
private const val SHEET_DRAG_DISMISS_VELOCITY = 1400f

internal enum class SheetVariant { BOTTOM, CENTER, END }

internal class SheetRequest(
    val id: Any,
    val onDismissRequest: () -> Unit,
    val modifier: Modifier,
    val title: String?,
    val containerColor: Color?,
    val isScrollable: Boolean,
    val headerContent: (@Composable () -> Unit)?,
    val variant: SheetVariant,
    val dismissible: Boolean,
    val content: @Composable ColumnScope.() -> Unit,
)

@Stable
class SheetHostState {
    internal var request by mutableStateOf<SheetRequest?>(null)
        private set

    internal fun submit(newRequest: SheetRequest) {
        request = newRequest
    }

    internal fun clear(id: Any) {
        if (request?.id === id) request = null
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

val LocalSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

val LocalSheetDismissThen = staticCompositionLocalOf<(action: () -> Unit) -> Unit> { { it() } }

@Composable
fun SheetHost(state: SheetHostState) {
    state.request?.let { req ->
        key(req.id) {
            SheetOverlay(
                onDismissRequest = req.onDismissRequest,
                modifier = req.modifier,
                title = req.title,
                containerColor = req.containerColor,
                isScrollable = req.isScrollable,
                headerContent = req.headerContent,
                variant = req.variant,
                dismissible = req.dismissible,
                content = req.content,
            )
        }
    }
}

@Composable
fun SheetHostScaffold(content: @Composable () -> Unit) {
    val host = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides host) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            SheetHost(host)
        }
    }
}

/**
 * 弹层统一挂在页面内容之上的最顶层。
 *
 * 之前弹层只是"排在内容之后"，一旦同级还有别的兄弟（侧栏、悬浮条、
 * 毛玻璃 backdrop 之类）在它之后绘制，弹窗就会被盖住，看起来像是
 * "跑到了首页下面"。这里显式抬到 zIndex 最高，
 * 保证无论同层还有谁，弹层永远在最上面。
 */
@Composable
fun TopLayerSheetHost(state: SheetHostState) {
    Box(modifier = Modifier.fillMaxSize().zIndex(Float.MAX_VALUE)) {
        SheetHost(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetOverlay(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    variant: SheetVariant = SheetVariant.BOTTOM,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val collapse = remember { Animatable(1f) }
    var entered by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var plainDismissPending by remember { mutableStateOf(false) }
    val centered = variant == SheetVariant.CENTER
    val durationMs = if (centered) DIALOG_FADE_DURATION_MS else SHEET_SLIDE_DURATION_MS

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        collapse.animateTo(0f, tween(durationMs))
        entered = true
    }

    fun dismissWithAnimation(after: (() -> Unit)? = null) {
        if (dismissing) return
        if (!dismissible && after == null) {
            onDismissRequest()
            return
        }
        dismissing = true
        plainDismissPending = after == null
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            collapse.animateTo(1f, tween(durationMs))
            if (after == null) {
                plainDismissPending = false
                onDismissRequest()
            } else {
                after()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (plainDismissPending) onDismissRequest() }
    }

    BackHandler(enabled = entered) { dismissWithAnimation() }

    val scrimColor = if (centered) {
        BottomSheetDefaults.ScrimColor.copy(alpha = DIALOG_SCRIM_ALPHA)
    } else {
        BottomSheetDefaults.ScrimColor
    }

    SheetSurface(
        collapse = collapse,
        scrimColor = scrimColor,
        entered = entered,
        variant = variant,
        durationMs = durationMs,
        scope = scope,
        dismiss = { dismissWithAnimation() },
        dismissThen = { action -> dismissWithAnimation(action) },
        modifier = modifier,
        title = title,
        containerColor = containerColor,
        isScrollable = isScrollable,
        headerContent = headerContent,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetSurface(
    collapse: Animatable<Float, AnimationVector1D>,
    scrimColor: Color,
    entered: Boolean,
    variant: SheetVariant,
    durationMs: Int,
    scope: CoroutineScope,
    dismiss: () -> Unit,
    dismissThen: (action: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val centered = variant == SheetVariant.CENTER
    val fromEnd = variant == SheetVariant.END
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var panelWidthPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 横屏时窗口高度只有 390dp 上下，再按 0.9 截断会让弹窗只剩一条缝，
        // 内容全被挤没。横屏放宽到 0.96，让弹窗尽量用满可用高度。
        val panelMaxHeight = maxHeight *
            (if (maxWidth > maxHeight) SheetMaxHeightFractionLandscape else SheetMaxHeightFraction)
        val panelMaxWidth = minOf(SheetMaxWidth, maxWidth * SHEET_END_WIDTH_FRACTION)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - collapse.value }
                .background(scrimColor)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.positionChanged() }) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .clickable(
                    enabled = entered,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (centered) Modifier.imePadding() else Modifier),
            contentAlignment = when {
                centered -> Alignment.Center
                fromEnd -> Alignment.CenterEnd
                else -> Alignment.BottomCenter
            },
        ) {
            Surface(
                modifier = Modifier
                    .then(modifier)
                    .then(
                        when {
                            centered -> Modifier
                                .padding(horizontal = DIALOG_HORIZONTAL_MARGIN)
                                .widthIn(min = DialogMinWidth, max = DialogMaxWidth)
                                .fillMaxWidth()
                                .heightIn(max = panelMaxHeight)

                            fromEnd -> Modifier
                                .widthIn(max = panelMaxWidth)
                                .fillMaxWidth()
                                .fillMaxHeight()

                            else -> Modifier
                                .widthIn(max = SheetMaxWidth)
                                .fillMaxWidth()
                                .heightIn(max = panelMaxHeight)
                        },
                    )
                    .graphicsLayer {
                        if (centered) {
                            val progress = 1f - collapse.value
                            val scale = DIALOG_ENTER_SCALE + (1f - DIALOG_ENTER_SCALE) * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                        } else if (fromEnd) {
                            translationX = collapse.value * size.width
                        } else {
                            translationY = collapse.value * size.height
                        }
                    }
                    .onGloballyPositioned {
                        panelHeightPx = it.size.height
                        panelWidthPx = it.size.width
                    },
                shape = when {
                    centered -> ContinuousDialog
                    fromEnd -> ContinuousSheetStart
                    else -> ContinuousSheetTop
                },
                color = containerColor ?: BottomSheetDefaults.ContainerColor,
            ) {
                CompositionLocalProvider(
                    LocalSheetDismiss provides { dismiss() },
                    LocalSheetDismissThen provides { action -> dismissThen(action) },
                ) {
                    Column(
                        modifier = if (centered || fromEnd) Modifier else Modifier.imePadding(),
                    ) {
                        if (!centered) {
                            Column(
                                modifier = Modifier.draggable(
                                    state = sheetDragState(
                                        collapse,
                                        entered,
                                        if (fromEnd) panelWidthPx else panelHeightPx,
                                    ),
                                    orientation = if (fromEnd) Orientation.Horizontal else Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        val dismiss = collapse.value > SHEET_DRAG_DISMISS_FRACTION ||
                                                velocity > SHEET_DRAG_DISMISS_VELOCITY
                                        if (dismiss) {
                                            dismiss()
                                        } else {
                                            scope.launch { collapse.animateTo(0f, tween(durationMs)) }
                                        }
                                    },
                                ),
                            ) {
                                if (!fromEnd) {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        BottomSheetDefaults.DragHandle()
                                    }
                                }
                                headerContent?.invoke()
                                title?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = if (fromEnd) {
                                            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                                        } else {
                                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        },
                                    )
                                }
                            }
                        }
                        if (isScrollable) {
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                content = content,
                            )
                        } else {
                            Column(modifier = Modifier.weight(1f, fill = false), content = content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sheetDragState(
    collapse: Animatable<Float, AnimationVector1D>,
    entered: Boolean,
    panelSpanPx: Int,
): DraggableState {
    val scope = rememberCoroutineScope()
    return rememberDraggableState { delta ->
        if (entered && panelSpanPx > 0) {
            scope.launch {
                collapse.snapTo(
                    (collapse.value + delta / panelSpanPx).coerceIn(0f, 1f),
                )
            }
        }
    }
}
