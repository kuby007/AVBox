package com.github.avbox.osc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.Image
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.player.PLAYER_PRESS_SCALE
import com.github.avbox.osc.ui.player.playerPressEffect
import com.github.avbox.osc.ui.components.HeroImageSizes
import com.github.avbox.osc.ui.components.ImagePalette
import com.github.avbox.osc.ui.components.LocalTopBarGlassBackdrop
import com.github.avbox.osc.ui.components.PosterSize
import com.github.avbox.osc.ui.components.PressMorphButtonShapes
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.ui.components.VodPoster
import com.github.avbox.osc.ui.components.isRatingNote
import com.github.avbox.osc.ui.components.pressScale
import com.github.avbox.osc.ui.components.rememberHeroImageSizes
import com.github.avbox.osc.ui.components.heroPosterSize
import com.github.avbox.osc.ui.components.tmdbUrlOf
import com.github.avbox.osc.ui.page.TmdbPosterStyle
import com.github.avbox.osc.ui.theme.AppThemeState
import com.github.avbox.osc.util.TmdbImages
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.avbox.osc.ui.components.marqueeOnOverflow

private const val DetailTopScrimAlpha = 0.32f

private const val HeroCircleAlpha = 0.6f

private const val HERO_IMAGE_HEIGHT_RATIO = 0.50f

private const val HERO_ROLL_INTERVAL_MS = 5000L

private const val HERO_ROLL_ANIM_MS = 300

private val DetailHeroFadeStops = arrayOf(
    0.75f to 0.05f,
    0.84f to 0.40f,
    0.90f to 0.72f,
    0.96f to 0.92f,
    1f to 1f,
)

private const val HERO_TMDB_LOADING_TIMEOUT_MS = 1200L

private const val HERO_TMDB_LOGO_TIMEOUT_MS = 1200L

private const val HeroLogoWidthFraction = 0.85f

private const val HeroFlatLogoWidthFraction = 0.42f

private const val HeroFlatDescriptionWidthFraction = 0.55f

private val HeroFlatBottomPadding = 28.dp

private val DetailTopScrimExtra = 24.dp

private val HeroImageMinHeight = 280.dp

private val HeroImageMaxHeight = 460.dp

private val HeroContentHorizontalPadding = 16.dp

private val HeroContentTopPadding = 10.dp

private val HeroContentBottomPadding = 4.dp

private val HeroLogoBottomPadding = 16.dp

private val HeroPlayCapsuleHeight = 52.dp

private val HeroPlayCapsuleMinWidth = 180.dp

private val HeroPlayCapsuleHorizontalPadding = 32.dp

private val HeroCircleButtonSize = 50.dp

private val HeroCircleButtonSpacing = 24.dp

private val HeroActionSpacing = 16.dp

private val HeroButtonRowSpacing = 16.dp

private val HeroLogoHeight = 84.dp

private val HeroRatingIconSize = 14.dp

private val HeroRatingStarColor = Color(0xFFFFC107)

private val HeroDescriptionSpacing = 14.dp

private const val HeroDescriptionAlpha = 0.82f

private const val HeroDescriptionCollapsedLines = 3

private val MetaSeparator = " · "

private val TypeSeparators = Regex("[,，、/|;；]+")

@Composable
internal fun DetailTopScrim(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusTop + DetailTopScrimExtra)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = DetailTopScrimAlpha * alpha.coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                ),
            ),
    )
}

@Composable
internal fun DetailHero(
    title: String,
    altTitle: String?,
    picture: String?,
    year: Int,
    area: String?,
    type: String?,
    note: String?,
    description: String?,
    collected: Boolean,
    followed: Boolean,
    onPosterPic: (String) -> Unit,
    onSeed: (Int?) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onMusic: () -> Unit,
    onCast: () -> Unit,
    onCollect: () -> Unit,
    onFollow: () -> Unit,
    vm: DetailViewModel,
    rollPaused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val glassBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    val circleColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = HeroCircleAlpha)
    val detail = rememberTmdbDetail(vm, title, altTitle, year)
    val ratingText = detail?.rating
        ?.takeIf { it > 0.0 }
        ?.let { String.format(Locale.US, "%.1f", it) }
    val types = type.orEmpty()
        .split(TypeSeparators)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(MetaSeparator)
    val metaTail = listOfNotNull(
        note?.trim().orEmpty().takeIf { it.isNotEmpty() && !isRatingNote(it) },
        year.takeIf { it > 0 }?.toString(),
        area?.takeIf { it.isNotBlank() },
        types.takeIf { it.isNotEmpty() },
    ).joinToString(MetaSeparator)
    val descriptionText = detail?.overview?.takeIf { it.isNotBlank() }
        ?: description?.takeIf { it.isNotBlank() }
    var descriptionExpanded by rememberSaveable(title) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val imageHeight = (configuration.screenHeightDp * HERO_IMAGE_HEIGHT_RATIO)
        .dp
        .coerceIn(HeroImageMinHeight, HeroImageMaxHeight)
    val flat = flatHeroLayout(
        configuration.smallestScreenWidthDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    val captionColor = if (flat) Color.White else heroCaptionColor()
    if (flat) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = configuration.screenHeightDp.dp)
                .clipToBounds(),
        ) {
            DetailHeroTopActions(
                collected = collected,
                onBack = onBack,
                onCollect = onCollect,
                fallbackColor = circleColor,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(
                        start = HeroContentHorizontalPadding,
                        end = HeroContentHorizontalPadding,
                        bottom = HeroFlatBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DetailHeroTitle(
                    vm = vm,
                    title = title,
                    altTitle = altTitle,
                    year = year,
                    widthFraction = HeroFlatLogoWidthFraction,
                    color = captionColor,
                )
                DetailHeroInfoBlock(
                    ratingText = ratingText,
                    metaTail = metaTail,
                    descriptionText = descriptionText,
                    descriptionExpanded = descriptionExpanded,
                    onToggleDescription = { descriptionExpanded = !descriptionExpanded },
                    onPlay = onPlay,
                    followed = followed,
                    onMusic = onMusic,
                    onCast = onCast,
                    onFollow = onFollow,
                    captionColor = captionColor,
                    flat = true,
                    modifier = Modifier.padding(top = HeroContentTopPadding),
                )
            }
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                *DetailHeroFadeStops
                                    .map { (position, alpha) -> position to Color.White.copy(alpha = 1f - alpha) }
                                    .toTypedArray(),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize().layerBackdrop(glassBackdrop)) {
                    DetailHeroPoster(
                        vm = vm,
                        title = title,
                        altTitle = altTitle,
                        picture = picture,
                        year = year,
                        onImage = { image ->
                            scope.launch {
                                val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                                onSeed(seed)
                            }
                        },
                        onBackdropPic = onPosterPic,
                        paused = rollPaused,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            CompositionLocalProvider(LocalTopBarGlassBackdrop provides glassBackdrop) {
                DetailHeroTopActions(
                    collected = collected,
                    onBack = onBack,
                    onCollect = onCollect,
                    fallbackColor = circleColor,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = HeroContentHorizontalPadding,
                        end = HeroContentHorizontalPadding,
                        bottom = HeroLogoBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DetailHeroTitle(
                    vm = vm,
                    title = title,
                    altTitle = altTitle,
                    year = year,
                    widthFraction = HeroLogoWidthFraction,
                    color = captionColor,
                )
            }
        }
        DetailHeroInfoBlock(
            ratingText = ratingText,
            metaTail = metaTail,
            descriptionText = descriptionText,
            descriptionExpanded = descriptionExpanded,
            onToggleDescription = { descriptionExpanded = !descriptionExpanded },
            onPlay = onPlay,
            followed = followed,
            onMusic = onMusic,
            onCast = onCast,
            onFollow = onFollow,
            captionColor = captionColor,
            modifier = Modifier.padding(
                start = HeroContentHorizontalPadding,
                end = HeroContentHorizontalPadding,
                top = HeroContentTopPadding,
                bottom = HeroContentBottomPadding,
            ),
        )
    }
}

@Composable
private fun DetailHeroTopActions(
    collected: Boolean,
    onBack: () -> Unit,
    onCollect: () -> Unit,
    fallbackColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarActionBox(
            iconRes = R.drawable.ic_arrow_left,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack,
            fallbackColor = fallbackColor,
        )
        Spacer(Modifier.weight(1f))
        TopBarActionBox(
            iconRes = if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect,
            contentDescription = stringResource(
                if (collected) R.string.detail_uncollect else R.string.detail_collect,
            ),
            onClick = onCollect,
            fallbackColor = fallbackColor,
            tint = if (collected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailHeroInfoBlock(
    ratingText: String?,
    metaTail: String,
    descriptionText: String?,
    descriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onPlay: () -> Unit,
    followed: Boolean,
    onMusic: () -> Unit,
    onCast: () -> Unit,
    onFollow: () -> Unit,
    captionColor: Color,
    flat: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clipToBounds(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ratingText != null || metaTail.isNotEmpty()) {
            DetailHeroRatingLine(rating = ratingText, tail = metaTail, color = captionColor)
        }
        if (!descriptionText.isNullOrEmpty()) {
            DetailHeroDescription(
                text = descriptionText,
                expanded = descriptionExpanded,
                onToggle = onToggleDescription,
                color = captionColor,
                flat = flat,
            )
        }
        val playInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onPlay,
            modifier = Modifier
                .padding(top = HeroActionSpacing)
                .widthIn(min = HeroPlayCapsuleMinWidth)
                .height(HeroPlayCapsuleHeight)
                .pressScale(playInteraction, PLAYER_PRESS_SCALE),
            shapes = PressMorphButtonShapes,
            contentPadding = PaddingValues(horizontal = HeroPlayCapsuleHorizontalPadding),
            interactionSource = playInteraction,
        ) {
            Image(
                painter = painterResource(R.drawable.player_ic_play),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.common_play),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        DetailActionRow(
            followed = followed,
            onMusic = onMusic,
            onCast = onCast,
            onFollow = onFollow,
            modifier = Modifier.padding(top = HeroButtonRowSpacing),
        )
    }
}

@Composable
internal fun DetailActionRow(
    followed: Boolean,
    onMusic: () -> Unit,
    onCast: () -> Unit,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HeroCircleButtonSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroCircleButton(
            iconRes = R.drawable.ic_detail_music_player,
            contentDescription = stringResource(R.string.detail_music_player),
            selected = false,
            onClick = onMusic,
        )
        HeroCircleButton(
            iconRes = R.drawable.ic_detail_cast,
            contentDescription = stringResource(R.string.common_cast),
            selected = false,
            onClick = onCast,
        )
        HeroCircleButton(
            iconRes = R.drawable.ic_tab_following,
            contentDescription = stringResource(R.string.tab_following),
            selected = followed,
            onClick = onFollow,
        )
    }
}

@Composable
private fun HeroCircleButton(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(HeroCircleButtonSize)
            .playerPressEffect(onTap = onClick)
            .detailGlass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun DetailHeroTitle(vm: DetailViewModel, title: String, altTitle: String?, year: Int,
                            widthFraction: Float, color: Color) {
    val epoch by TmdbImages.configEpoch.collectAsState()
    LaunchedEffect(vm, title, altTitle, year, epoch) {
        vm.ensureTmdbLogo(title, altTitle, year)
    }
    val logoPath by vm.tmdbLogoPath.collectAsState()
    var waitExpired by remember(title, altTitle, year, epoch) { mutableStateOf(false) }
    LaunchedEffect(title, altTitle, year, epoch, logoPath) {
        waitExpired = false
        if (logoPath == null) {
            delay(HERO_TMDB_LOGO_TIMEOUT_MS)
            waitExpired = true
        }
    }
    val logo = logoPath
    if (logo == null) {
        if (waitExpired) DetailHeroTitleText(title, color)
    } else if (logo.isEmpty()) {
        DetailHeroTitleText(title, color)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(HeroLogoHeight),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = TmdbImages.logoUrl(logo),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DetailHeroTitleText(title: String, color: Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight(800),
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DetailHeroRatingLine(rating: String?, tail: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rating != null) {
            Icon(
                painter = painterResource(R.drawable.ic_tab_collect_filled),
                contentDescription = null,
                tint = HeroRatingStarColor,
                modifier = Modifier.size(HeroRatingIconSize),
            )
            Text(
                text = rating,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (tail.isNotEmpty()) {
            Text(
                text = if (rating != null) MetaSeparator + tail else tail,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .marqueeOnOverflow(),
            )
        }
    }
}

@Composable
private fun DetailHeroDescription(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    color: Color,
    flat: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = color.copy(alpha = HeroDescriptionAlpha),
        textAlign = if (flat) TextAlign.Center else TextAlign.Start,
        maxLines = if (expanded) Int.MAX_VALUE else HeroDescriptionCollapsedLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth(if (flat) HeroFlatDescriptionWidthFraction else 1f)
            .padding(top = HeroDescriptionSpacing)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
    )
}

@Composable
private fun heroCaptionColor(): Color =
    if (AppThemeState.isDark(isSystemInDarkTheme())) {
        Color.White
    } else {
        Color.Black
    }

@Composable
internal fun DetailHeroPoster(
    vm: DetailViewModel,
    title: String,
    altTitle: String?,
    picture: String?,
    year: Int,
    onImage: (Image) -> Unit,
    onBackdropPic: (String) -> Unit,
    paused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val epoch by TmdbImages.configEpoch.collectAsState()
    val posterStyleCode by vm.tmdbPosterStyleCode.collectAsState()
    val style = remember(posterStyleCode) { TmdbPosterStyle.of(posterStyleCode) }
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    val sizes = rememberHeroImageSizes()
    val tmdbActive = TmdbImages.isActive()
    val source = heroImageSource(style, wide, tmdbActive)
    val heroKey = remember(title, altTitle, year, epoch) { listOf<Any?>(title, altTitle, year, epoch) }
    LaunchedEffect(vm, source, title, altTitle, year, epoch) {
        vm.ensureTmdbPosterPath(title, altTitle, year)
        when (source) {
            HeroImageSource.POSTER -> vm.ensureTmdbHeroImages(title, altTitle, year)
            HeroImageSource.BACKDROP -> vm.ensureTmdbHeroBackdrops(title, altTitle, year)
            HeroImageSource.NONE -> Unit
        }
    }
    val posterState by vm.tmdbHeroImages.collectAsState()
    val backdropState by vm.tmdbHeroBackdrops.collectAsState()
    val posterPathState by vm.tmdbPosterPath.collectAsState()
    val images = when (source) {
        HeroImageSource.POSTER -> posterState?.takeIf { it.key == heroKey }?.paths
        HeroImageSource.BACKDROP -> backdropState?.takeIf { it.key == heroKey }?.paths
        HeroImageSource.NONE -> null
    }
    val urls = remember(images, source, sizes) { heroUrls(images, source, sizes) }
    if (urls.isEmpty()) {
        val fixedPath = if (tmdbActive) posterPathState?.takeIf { it.key == heroKey }?.path else ""
        val resolving = (source != HeroImageSource.NONE && images == null) || fixedPath == null
        var waitExpired by remember(title, year, epoch) { mutableStateOf(false) }
        LaunchedEffect(title, year, epoch, resolving) {
            waitExpired = false
            if (resolving) {
                delay(HERO_TMDB_LOADING_TIMEOUT_MS)
                waitExpired = true
            }
        }
        val fixedUrl = fixedPath?.takeIf { it.isNotEmpty() }?.let { tmdbUrlOf(it, sizes.poster) }
        LaunchedEffect(fixedUrl, picture) {
            val url = fixedUrl ?: picture
            if (!url.isNullOrEmpty()) onBackdropPic(url)
        }
        if (resolving && !waitExpired) {
            DetailHeroPosterLoading(modifier)
            return
        }
        VodPoster(
            name = title,
            pic = picture,
            size = sizes.poster,
            year = year,
            altName = altTitle,
            tmdbPath = fixedPath,
            modifier = modifier,
            onImage = onImage,
        )
        return
    }
    val seedIndex = remember(title, urls) {
        if (style == TmdbPosterStyle.Random) Random.nextInt(urls.size) else 0
    }
    var index by remember(title, urls) { mutableIntStateOf(seedIndex) }
    if (style == TmdbPosterStyle.Roll && urls.size > 1 && !paused) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner, urls) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(HERO_ROLL_INTERVAL_MS)
                    index = (index + 1) % urls.size
                }
            }
        }
    }
    val context = LocalPlatformContext.current
    LaunchedEffect(index, urls) {
        if (urls.size > 1) {
            val request = ImageRequest.Builder(context).data(urls[(index + 1) % urls.size]).build()
            SingletonImageLoader.get(context).enqueue(request)
        }
    }
    LaunchedEffect(urls) { onBackdropPic(urls[seedIndex]) }
    var seedSent by remember(title, urls) { mutableStateOf(false) }
    AnimatedContent(
        targetState = urls[index],
        transitionSpec = {
            (slideInHorizontally(tween(HERO_ROLL_ANIM_MS)) { it } + fadeIn(tween(HERO_ROLL_ANIM_MS)))
                .togetherWith(
                    slideOutHorizontally(tween(HERO_ROLL_ANIM_MS)) { -it } + fadeOut(tween(HERO_ROLL_ANIM_MS)),
                )
        },
        label = "heroPosterRoll",
        modifier = modifier,
    ) { targetUrl ->
        VodPoster(
            name = title,
            pic = targetUrl,
            size = PosterSize.LARGE,
            resolveTmdb = false,
            modifier = Modifier.fillMaxSize(),
            onImage = { image ->
                if (!seedSent) {
                    seedSent = true
                    onImage(image)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailHeroPosterLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ContainedLoadingIndicator(Modifier.size(64.dp))
    }
}

private enum class HeroImageSource { NONE, POSTER, BACKDROP }

private fun heroImageSource(style: TmdbPosterStyle, wide: Boolean, active: Boolean): HeroImageSource = when {
    !active -> HeroImageSource.NONE
    wide -> HeroImageSource.BACKDROP
    style == TmdbPosterStyle.Random || style == TmdbPosterStyle.Roll -> HeroImageSource.POSTER
    else -> HeroImageSource.NONE
}

private fun heroUrls(images: List<String>?, source: HeroImageSource, sizes: HeroImageSizes): List<String> =
    images.orEmpty().map {
        tmdbUrlOf(it, if (source == HeroImageSource.BACKDROP) sizes.backdrop else sizes.poster)
    }
