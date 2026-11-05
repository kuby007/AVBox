package com.github.avbox.osc.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.event.UiChange
import com.github.avbox.osc.event.UiEvents
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.HistoryWriter
import com.github.avbox.osc.data.VodFollow
import com.github.avbox.osc.net.SearchHelper
import com.github.avbox.osc.player.PlaybackService
import com.github.avbox.osc.player.PlaybackSession
import com.github.avbox.osc.sourcedata.SourceViewModel
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.api.TmdbApi
import com.github.avbox.osc.api.TmdbPoster
import com.github.avbox.osc.bean.TmdbDetail
import com.github.avbox.osc.bean.TmdbEpisode
import com.github.avbox.osc.bean.TmdbPerson
import com.github.avbox.osc.bean.TmdbPosterCache
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.TmdbImages
import com.github.avbox.osc.util.DetailIntent
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.sourcedata.Thunder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.avbox.osc.ui.components.toVodInfo

class DetailViewModel : ViewModel(), TmdbPosterCache {

    val tmdbPosterStyleCode: StateFlow<Int> = TmdbImages.configEpoch
        .map { KV.get(KVKeys.TMDB_POSTER_STYLE, 0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, KV.get(KVKeys.TMDB_POSTER_STYLE, 0))

    override fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String? =
        TmdbPoster.cachedPoster(name, year, altName)

    private fun str(resId: Int, vararg args: Any): String {
        val context = AppContextHolder.context() ?: return ""
        return LanguageManager.localized(context).getString(resId, *args)
    }

    sealed interface PageState {
        data object Loading : PageState
        data class Empty(val msg: String? = null) : PageState
        data object Ready : PageState
    }

    data class SourceChip(val key: String, val name: String)

    private val _pageState = MutableStateFlow<PageState>(PageState.Loading)
    val pageState = _pageState.asStateFlow()

    data class VodUiSnapshot(val rev: Int, val info: VodInfo)

    private val _vodUi = MutableStateFlow<VodUiSnapshot?>(null)
    val vodUi: StateFlow<VodUiSnapshot?> = _vodUi.asStateFlow()
    private val _fullScreen = MutableStateFlow(false)
    val fullScreen = _fullScreen.asStateFlow()
    private val _rotating = MutableStateFlow(false)
    val rotating: StateFlow<Boolean> = _rotating.asStateFlow()
    private val _enteringFullscreen = MutableStateFlow(false)
    val enteringFullscreen = _enteringFullscreen.asStateFlow()
    private val _exitingFullscreen = MutableStateFlow(false)
    val exitingFullscreen = _exitingFullscreen.asStateFlow()
    private val _playSignal = MutableStateFlow(0)
    val playSignal = _playSignal.asStateFlow()
    /** 带播放的详情页：播放窗内嵌在列表里，不进全屏 */
    private val _embeddedPlay = MutableStateFlow(false)
    val embeddedPlay = _embeddedPlay.asStateFlow()
    private var autoPlayOnOpen = false
    private var autoPlayConsumed = false
    private val _collected = MutableStateFlow(false)
    val collected = _collected.asStateFlow()
    private val _follow = MutableStateFlow<VodFollow?>(null)
    val follow = _follow.asStateFlow()

    private val _tmdbDetail = MutableStateFlow<TmdbDetail?>(null)
    val tmdbDetail = _tmdbDetail.asStateFlow()
    private val _tmdbLogoPath = MutableStateFlow<String?>(null)
    val tmdbLogoPath = _tmdbLogoPath.asStateFlow()
    private val _tmdbEpisodeMeta = MutableStateFlow<List<TmdbEpisode>?>(null)
    val tmdbEpisodeMeta = _tmdbEpisodeMeta.asStateFlow()
    private val _tmdbHeroImages = MutableStateFlow<TmdbImagesState?>(null)
    val tmdbHeroImages = _tmdbHeroImages.asStateFlow()
    private val _tmdbHeroBackdrops = MutableStateFlow<TmdbImagesState?>(null)
    val tmdbHeroBackdrops = _tmdbHeroBackdrops.asStateFlow()
    private val _tmdbPerson = MutableStateFlow<TmdbPersonState?>(null)
    val tmdbPerson = _tmdbPerson.asStateFlow()
    private val _tmdbPosterPath = MutableStateFlow<TmdbPosterPathState?>(null)
    val tmdbPosterPath = _tmdbPosterPath.asStateFlow()

    fun seasonHintFor(name: String?, flag: String?): Int? =
        TmdbApi.parseSeasonHint(name) ?: TmdbApi.parseSeasonHint(flag)

    fun ensureTmdbDetail(name: String?, altName: String?, year: Int) {
        if (name.isNullOrBlank()) {
            _tmdbDetail.value = null
            tmdbDetailKey = null
            return
        }
        val epoch = TmdbImages.configEpoch.value
        val key = listOf(name, altName, year, epoch)
        if (tmdbDetailKey == key) return
        tmdbDetailKey = key
        val cached = TmdbPoster.cachedDetail(name, year, altName)
        _tmdbDetail.value = cached
        if (cached == null) {
            viewModelScope.launch {
                val resolved = TmdbPoster.resolveDetail(name, year, altName)
                if (tmdbDetailKey == key) _tmdbDetail.value = resolved
            }
        }
    }

    fun ensureTmdbLogo(title: String?, altTitle: String?, year: Int) {
        if (title.isNullOrBlank()) {
            _tmdbLogoPath.value = null
            tmdbLogoKey = null
            return
        }
        val epoch = TmdbImages.configEpoch.value
        val key = listOf(title, altTitle, year, epoch)
        if (tmdbLogoKey == key) return
        tmdbLogoKey = key
        val cached = TmdbPoster.cachedLogo(title, year, altTitle)
        _tmdbLogoPath.value = cached
        if (cached == null) {
            viewModelScope.launch {
                val resolved = TmdbPoster.resolveLogo(title, year, altTitle)
                if (tmdbLogoKey == key) _tmdbLogoPath.value = resolved
            }
        }
    }

    fun ensureTmdbEpisodeMeta(
        name: String?,
        altName: String?,
        year: Int,
        episodeCount: Int,
        seasonHint: Int?,
        sourceOrder: List<Int>,
    ) {
        if (name.isNullOrBlank() || episodeCount <= 0) {
            _tmdbEpisodeMeta.value = null
            tmdbEpisodeMetaKey = null
            return
        }
        val epoch = TmdbImages.configEpoch.value
        val key = listOf(name, altName, year, episodeCount, seasonHint, sourceOrder, epoch)
        if (tmdbEpisodeMetaKey == key) return
        tmdbEpisodeMetaKey = key
        val cached = TmdbPoster.cachedEpisodeMeta(name, episodeCount, seasonHint, sourceOrder, altName)
        _tmdbEpisodeMeta.value = cached
        if (cached == null) {
            viewModelScope.launch {
                val resolved = TmdbPoster.resolveEpisodeMeta(
                    name, year, episodeCount, seasonHint, sourceOrder, altName,
                )
                if (tmdbEpisodeMetaKey == key) _tmdbEpisodeMeta.value = resolved
            }
        }
    }

    fun ensureTmdbHeroImages(title: String?, altTitle: String?, year: Int) {
        ensureTmdbImages(
            title, altTitle, year, _tmdbHeroImages,
            cached = { name, y, alt -> TmdbPoster.cachedImages(name, y, alt) },
            resolve = { name, y, alt -> TmdbPoster.resolveImages(name, y, alt) },
        )
    }

    fun ensureTmdbHeroBackdrops(title: String?, altTitle: String?, year: Int) {
        ensureTmdbImages(
            title, altTitle, year, _tmdbHeroBackdrops,
            cached = { name, y, alt -> TmdbPoster.cachedBackdrops(name, y, alt) },
            resolve = { name, y, alt -> TmdbPoster.resolveBackdrops(name, y, alt) },
        )
    }

    private fun ensureTmdbImages(
        title: String?,
        altTitle: String?,
        year: Int,
        target: MutableStateFlow<TmdbImagesState?>,
        cached: (String, Int, String?) -> List<String>?,
        resolve: suspend (String, Int, String?) -> List<String>,
    ) {
        if (title.isNullOrBlank()) {
            target.value = null
            return
        }
        val key = listOf(title, altTitle, year, TmdbImages.configEpoch.value)
        if (target.value?.key == key) return
        val cachedPaths = cached(title, year, altTitle)
        target.value = TmdbImagesState(key, cachedPaths)
        if (cachedPaths == null) {
            viewModelScope.launch {
                val resolved = resolve(title, year, altTitle)
                if (target.value?.key == key) target.value = TmdbImagesState(key, resolved)
            }
        }
    }

    fun ensureTmdbPerson(id: Int) {
        if (id <= 0) {
            _tmdbPerson.value = null
            return
        }
        val epoch = TmdbImages.configEpoch.value
        val existing = _tmdbPerson.value
        if (existing?.id == id && existing.epoch == epoch) return
        val cached = TmdbPoster.cachedPerson(id)
        _tmdbPerson.value = TmdbPersonState(id, epoch, cached, cached == null)
        if (cached == null) {
            viewModelScope.launch {
                val resolved = TmdbPoster.resolvePerson(id)
                val current = _tmdbPerson.value
                if (current?.id == id && current.epoch == epoch) {
                    _tmdbPerson.value = TmdbPersonState(id, epoch, resolved, false)
                }
            }
        }
    }

    data class TmdbImagesState(val key: List<Any?>, val paths: List<String>?)

    data class TmdbPersonState(val id: Int, val epoch: Int, val person: TmdbPerson?, val loading: Boolean)

    data class TmdbPosterPathState(val key: List<Any?>, val path: String?)

    fun ensureTmdbPosterPath(title: String?, altTitle: String?, year: Int) {
        val key = listOf(title, altTitle, year, TmdbImages.configEpoch.value)
        if (title.isNullOrBlank() || !TmdbImages.isActive()) {
            _tmdbPosterPath.value = TmdbPosterPathState(key, "")
            return
        }
        if (_tmdbPosterPath.value?.key == key) return
        val cached = TmdbPoster.cachedPoster(title, year, altTitle) ?: ""
        _tmdbPosterPath.value = TmdbPosterPathState(key, cached)
        if (cached.isEmpty()) {
            viewModelScope.launch {
                val resolved = TmdbPoster.resolvePoster(title, year, altTitle) ?: ""
                if (_tmdbPosterPath.value?.key == key) {
                    _tmdbPosterPath.value = TmdbPosterPathState(key, resolved)
                }
            }
        }
    }

    private var tmdbDetailKey: List<Any?>? = null
    private var tmdbLogoKey: List<Any?>? = null
    private var tmdbEpisodeMetaKey: List<Any?>? = null
    private val _qualityOptions = MutableStateFlow<List<String>>(emptyList())
    val qualityOptions = _qualityOptions.asStateFlow()
    private val _qualitySelected = MutableStateFlow(0)
    val qualitySelected = _qualitySelected.asStateFlow()
    private val _sourceChips = MutableStateFlow<List<SourceChip>>(emptyList())
    val sourceChips = _sourceChips.asStateFlow()
    private val _sourcesSearching = MutableStateFlow(false)
    val sourcesSearching = _sourcesSearching.asStateFlow()
    private val _episodeSheet = MutableStateFlow(false)
    val episodeSheet = _episodeSheet.asStateFlow()
    private val _toastEvent = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val _finishEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val finishEvent: SharedFlow<Unit> = _finishEvent.asSharedFlow()

    private val playbackCommandChannel = Channel<PlaybackCommand>(Channel.BUFFERED)
    val playbackCommands: Flow<PlaybackCommand> = playbackCommandChannel.receiveAsFlow()

    var vodInfo: VodInfo? = null; private set
    var vodId = ""; private set
    var sourceKey = ""; private set
    var firstsourceKey = ""; private set

    private var manualLineSwitchPending = false

    private var manualLineSwitchTargetIndex = -1

    private var vodName = ""
    private var vodPicture = ""
    private var fromCollect = false

    val pendingPicture: String get() = vodPicture

    private val sourceViewModel = SourceViewModel()
    private val navStack = DetailNavStack()
    private var searchJob: Job? = null
    private var detailBuildToken = 0

    private var detailRequestToken = 0

    private var detailRetryTarget: String? = null
    private var detailRetryJob: Job? = null

    private val fallbackCandidates = ArrayList<Movie.Video>()
    private val candidateKeys = HashSet<String>()
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()
    private val semaphore = Semaphore(SOURCE_SEARCH_CONCURRENCY)
    private val pendingSearchDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val searchCaller = SourceViewModel()
    private var fallbackKeepCurrentDetail = false
    private var fallbackLoadingCandidate = false
    private var fallbackActive = false
    private var fallbackAutoSwitch = false
    private var fallbackEpisode: VodInfo.VodSeries? = null
    private var fallbackEpisodeIndex = -1
    private var detailTimeoutScheduled = false
    private var detailTimeoutJob: Job? = null

    private class SwitchSnapshot(
        val vodInfo: VodInfo,
        val vodId: String,
        val sourceKey: String,
        val firstsourceKey: String,
        val vodName: String,
        val vodPicture: String,
        val wasPlaying: Boolean,
    )

    private var switchSnapshot: SwitchSnapshot? = null

    private var searchToken = 0
    private var searchTitle = ""

    init {
        viewModelScope.launch {
            sourceViewModel.detailResult.flow.collect { data ->
                if (DetailResponseGuard.isCurrent(detailRequestToken, data?.detailToken)) onDetailResult(data)
            }
        }
        viewModelScope.launch {
            sourceViewModel.searchResult.flow.collect { onSearchResult(it) }
        }
        observeUiEvents()
        observeEngineState()
    }

    private var engineStateJob: Job? = null

    private fun observeEngineState() {
        if (engineStateJob?.isActive == true) return
        engineStateJob = viewModelScope.launch {
            while (isActive) {
                val engine = PlaybackService.peek()
                val controller = engine?.controller()
                if (engine == null || controller == null) {
                    delay(250)
                    continue
                }
                val subscriptions = launch {
                    launch {
                        controller.selection.collect {
                            val playing = controller.vod() ?: return@collect
                            syncPlayingVodInfo(playing)
                        }
                    }
                    launch {
                        controller.quality.collect { result ->
                            val playing = controller.vod() ?: return@collect
                            val info = vodInfo ?: return@collect
                            if (playing.id != info.id || playing.sourceKey != info.sourceKey) return@collect
                            updateQualityOptions(result)
                        }
                    }
                    launch {
                        controller.persistedPlayerCfg.collect { cfg ->
                            val playing = controller.vod() ?: return@collect
                            val info = vodInfo ?: return@collect
                            if (playing.id != info.id || playing.sourceKey != info.sourceKey) return@collect
                            if (cfg != null && info.playerCfg != cfg) {
                                commitVodInfo(info.copy(playerCfg = cfg))
                                insertVod()
                            }
                        }
                    }
                }
                while (isActive && PlaybackService.peek() === engine) delay(500)
                subscriptions.cancel()
            }
        }
    }

    fun initFromIntent(intent: Intent?) {
        if (vodId.isNotEmpty()) return
        val target = parseTarget(intent) ?: return
        autoPlayOnOpen = intent?.getBooleanExtra("detail_auto_play", false) == true
        autoPlayConsumed = false
        navStack.push(target)
        applyTarget(target)
    }

    fun pushTargetFromIntent(intent: Intent?) {
        val target = parseTarget(intent) ?: return
        if (!navStack.push(target)) return
        applyTarget(target)
    }

    fun backToPreviousTarget(): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        return true
    }

    private fun parseTarget(intent: Intent?): DetailNavStack.Target? {
        val bundle = intent?.extras ?: return null
        // 走 DetailIntent 兼容层：认 key/id/name/pic 等多种别名，
        // 第三方 jar、py 脚本按影视TV 约定塞的 extra 也能读出来
        return DetailNavStack.Target(
            vodId = DetailIntent.id(bundle),
            sourceKey = DetailIntent.sourceKey(bundle),
            title = DetailIntent.title(bundle),
            picture = DetailIntent.picture(bundle),
            fromCollect = bundle.getBoolean("collect", false),
        )
    }

    private fun applyTarget(target: DetailNavStack.Target) {
        cancelInFlightContent()
        resetContentState()
        sendCommand(PlaybackCommand.StopForContentSwitch)
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
        fromCollect = target.fromCollect
        vodName = target.title
        vodPicture = target.picture
        loadDetail(target.vodId, target.sourceKey)
        AppLog.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        if (vodName.isNotEmpty()) startSourceSearch()
    }

    private fun cancelInFlightContent() {
        searchJob?.cancel()
        searchJob = null
        pendingSearchDone.values.forEach { it.complete(Unit) }
        pendingSearchDone.clear()
        cancelDetailTimeout()
        searchToken = SEARCH_SEQ.incrementAndGet()
    }

    private fun resetContentState() {
        publishVodInfo(null)
        switchSnapshot = null
        firstsourceKey = ""
        searchTitle = ""
        manualLineSwitchPending = false
        _collected.value = false
        _follow.value = null
        _sourcesSearching.value = false
        _qualityOptions.value = emptyList()
        _qualitySelected.value = 0
        _episodeSheet.value = false
        _pageState.value = PageState.Loading
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        usedSourceKeys.clear()
        detailRetryTarget = null
        resetEngineState(keepChips = false)
    }

    fun onFullScreenToggleRequested(requested: Boolean, facts: DetailPlaybackFacts) {
        if (requested) {
            val reason = DetailFullScreenGate.refusalReason(
                _pageState.value,
                loadingText = { str(R.string.detail_content_not_ready) },
                emptyText = { str(R.string.detail_empty_source) },
            )
            if (reason != null) {
                _toastEvent.tryEmit(reason)
                return
            }
            enterFullscreen()
            return
        }
        val next = DetailFullScreenSlide.exit(slideState()) ?: return
        applySlideState(next)
        Thunder.cancelPendingParse()
        _rotating.value = DetailPlaybackCommands.exitFullScreenState(facts).second
    }

    fun settleRotation() {
        if (!_rotating.value) return
        _rotating.value = false
    }

    fun bumpRevision() {
        _vodUi.update { it?.copy(rev = it.rev + 1) }
    }

    private fun commitVodInfo(info: VodInfo) {
        vodInfo = info
        _vodUi.update { it?.copy(info = info, rev = it.rev + 1) }
    }

    private fun publishVodInfo(info: VodInfo?) {
        vodInfo = info
        _vodUi.value = info?.let { VodUiSnapshot(0, it) }
    }

    internal fun onPlayRequested(entry: DetailPlaybackEntry) {
        val list = vodInfo?.seriesMap?.get(vodInfo?.playFlag)
        if (list.isNullOrEmpty()) {
            _toastEvent.tryEmit(str(R.string.detail_no_playable_content))
            return
        }
        applyPlaybackEntry(entry)
    }

    /** 点宫格直接进带播放的详情页：播放窗内嵌、不进全屏 */
    internal fun startEmbeddedPlay() {
        _embeddedPlay.value = true
        applyPlaybackEntry(DetailPlaybackEntry.EmbeddedPlay)
    }

    internal fun applyPlaybackEntry(entry: DetailPlaybackEntry, resumable: Boolean = false) {
        val plan = DetailPlaybackPolicy.plan(entry, resumable)
        if (plan.enterFullScreen) {
            // 播放器同一时刻只能挂在一个父容器里，进全屏前先退出内嵌态
            _embeddedPlay.value = false
            enterFullscreen()
        }
        if (plan.startPlayback) _playSignal.value += 1
    }

    private fun enterFullscreen() {
        val next = DetailFullScreenSlide.enter(slideState()) ?: return
        applySlideState(next)
    }

    fun onEntrySlideFinished() {
        val next = DetailFullScreenSlide.entryFinished(slideState()) ?: return
        applySlideState(next)
    }

    fun onExitSlideFinished() {
        applySlideState(DetailFullScreenSlide.exitFinished(slideState()))
    }

    fun cancelEntrySlide() {
        val next = DetailFullScreenSlide.cancelEntry(slideState()) ?: return
        applySlideState(next)
    }

    private fun slideState() = DetailFullScreenSlideState(
        fullScreen = _fullScreen.value,
        entering = _enteringFullscreen.value,
        exiting = _exitingFullscreen.value,
    )

    private fun applySlideState(state: DetailFullScreenSlideState) {
        _fullScreen.value = state.fullScreen
        _enteringFullscreen.value = state.entering
        _exitingFullscreen.value = state.exiting
    }

    private fun consumeManualLineSwitch(): Boolean {
        val pending = manualLineSwitchPending
        manualLineSwitchPending = false
        return pending
    }

    fun showEpisodeSheet() {
        _episodeSheet.value = true
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(true))
    }

    fun dismissEpisodeSheet() {
        _episodeSheet.value = false
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
    }

    private fun retryDetailOnce(reason: String): Boolean {
        val key = sourceKey
        val id = vodId
        if (id.isEmpty()) return false
        val target = key + "|" + id
        if (detailRetryTarget == target) return false
        detailRetryTarget = target
        AppLog.i("echo-detail-retry key=$key id=$id reason=$reason")
        detailRetryJob?.cancel()
        detailRetryJob = viewModelScope.launch {
            delay(DETAIL_RETRY_DELAY_MS)
            if (sourceKey == key && vodId == id) loadDetail(id, key)
        }
        return true
    }

    private fun loadDetail(vid: String, key: String) {
        val requestToken = nextDetailRequestToken()
        vodId = vid.orEmpty()
        sourceKey = key.orEmpty()
        firstsourceKey = sourceKey
        usedSourceKeys.add(firstsourceKey)
        refreshCollectState(sourceKey, vodId)
        if (DetailResponseGuard.isUnloadableTarget(vodId, ApiConfig.get().getSource(sourceKey) == null)) {
            onDetailUnavailable()
            return
        }
        _pageState.value = PageState.Loading
        sourceViewModel.getDetail(sourceKey, vodId, false, requestToken)
    }

    private fun nextDetailRequestToken(): Int = ++detailRequestToken

    private fun refreshCollectState(key: String, vid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val isCollected = try {
                AppGraph.collectRepository.isVodCollect(key, vid)
            } catch (th: Throwable) {
                AppLog.e("DetailViewModel", "collect state read failed: " + th, th)
                return@launch
            }
            val followRecord = try {
                AppGraph.followRepository.find(key, vid)
            } catch (th: Throwable) {
                AppLog.e("DetailViewModel", "follow state read failed: " + th, th)
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (sourceKey != key || vodId != vid) return@withContext
                _collected.value = isCollected
                _follow.value = followRecord
            }
        }
    }

    fun retry() {
        if (vodId.isEmpty()) return
        detailRetryTarget = null
        loadDetail(vodId, sourceKey)
        if (searchTitle.isNotEmpty() && !_sourcesSearching.value) startSourceSearch()
    }

    private fun onDetailUnavailable() {
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
            return
        }
        if (vodName.isNotEmpty() && !_sourcesSearching.value) startSourceSearch()
        val staleSource = ApiConfig.get().getSource(sourceKey) == null
        if (!(staleSource && startFallbackIfNeeded(auto = true))) {
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    fun onDetailResult(absXml: AbsXml?) {
        if (fallbackActive && !fallbackLoadingCandidate) return
        if (absXml != null && !absXml.sourceKey.isNullOrEmpty() && absXml.sourceKey != sourceKey) return
        val videoList = absXml?.movie?.videoList
        val detailToken = ++detailBuildToken
        if (videoList != null && videoList.isNotEmpty()) {
            val wasFallback = fallbackLoadingCandidate
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
            }
            if (wasFallback) {
                val fallbackSource = ApiConfig.get().getSource(sourceKey)
                _toastEvent.tryEmit(str(R.string.detail_switch_site, fallbackSource?.name ?: sourceKey))
            }
            if (isSourceErrorMsg(absXml.msg)) {
                if (!rollbackManualSwitch(absXml.msg)) {
                    absXml.msg?.let { _toastEvent.tryEmit(it) }
                    enterEmpty(absXml.msg)
                }
                return
            }
            val detailVideo = videoList[0]
            detailVideo.id = vodId
            if (detailVideo.name.isNullOrEmpty()) detailVideo.name = vodName
            if (detailVideo.name.isNullOrEmpty()) detailVideo.name = "TVBox"
            if (detailVideo.pic.isNullOrEmpty() && vodPicture.isNotEmpty()) {
                detailVideo.pic = vodPicture
            }
            val keep = vodInfo?.takeIf { it.sourceKey == detailVideo.sourceKey }
            val keepFlag = keep?.playFlag
            val keepIndex = keep?.playIndex
            var info = VodInfo.fromVideo(detailVideo)
            info = info.copy(sourceKey = detailVideo.sourceKey)
            sourceKey = detailVideo.sourceKey ?: sourceKey

            val recordKey = sourceKey
            val recordId = vodId
            viewModelScope.launch {
                val record = withContext(Dispatchers.IO) {
                    if (HistoryHelper.isIncognito()) null else AppGraph.historyRepository.getVodInfo(recordKey, recordId)
                }
                if (detailToken != detailBuildToken || sourceKey != recordKey || vodId != recordId) return@launch
                info = if (record != null) {
                    info.copy(
                        playIndex = maxOf(record.playIndex, 0),
                        playFlag = record.playFlag,
                        playerCfg = record.playerCfg,
                        reverseSort = record.reverseSort,
                    )
                } else {
                    info.copy(playIndex = 0, playFlag = null, playerCfg = "", reverseSort = false)
                }
                if (info.reverseSort) info.reverse()
                if (info.playFlag == null || info.seriesMap?.containsKey(info.playFlag) != true) {
                    info = info.copy(playFlag = info.seriesMap?.keys?.firstOrNull())
                }
                if (keepFlag != null && info.seriesMap?.containsKey(keepFlag) == true) {
                    info = info.copy(
                        playFlag = keepFlag,
                        playIndex = (keepIndex ?: 0).coerceIn(0, (info.seriesMap?.get(keepFlag)?.size ?: 1) - 1),
                    )
                }
                val restored = restoredSelection(info)
                info = info.copy(playFlag = restored.first, playIndex = restored.second)
                resetEngineState(keepChips = true)
                val playingList = info.seriesMap?.get(info.playFlag)
                if (!playingList.isNullOrEmpty()) {
                    info = info.copy(playIndex = info.playIndex.coerceIn(0, playingList.size - 1))
                }
                publishVodInfo(info)
                if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                    searchTitle = info.name.orEmpty().trim()
                    startSourceSearch()
                }
                vodName = detailVideo.name ?: vodName
                if (!playingList.isNullOrEmpty()) switchSnapshot = null
                _pageState.value = PageState.Ready
                bumpRevision()
                // 「详情页播放」开关开启时，宫格点击直接得到带播放窗的详情页
                if (autoPlayOnOpen && !autoPlayConsumed && !playingList.isNullOrEmpty()) {
                    autoPlayConsumed = true
                    startEmbeddedPlay()
                }
                if (playingList.isNullOrEmpty()) {
                    if (retryDetailOnce("no-line")) return@launch
                    AppLog.i("echo-detail-no-line key=$sourceKey id=$vodId name=${info.name}")
                    _toastEvent.tryEmit(str(R.string.detail_no_playable_content))
                }
            }
        } else {
            if (fallbackLoadingCandidate) {
                if (retryDetailOnce("empty-candidate")) return
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
                loadNextFallbackCandidate()
                return
            }
            handleEmptyDetail(absXml)
        }
    }

    private fun handleEmptyDetail(data: AbsXml?) {
        val msg = data?.msg.orEmpty()
        if (isSourceErrorMsg(msg)) {
            if (rollbackManualSwitch(msg)) return
            if (fallbackToPreviousTarget(msg)) return
            AppLog.i("echo-detail-finish reason=source-msg msg=$msg key=$sourceKey id=$vodId")
            resetEngineState(keepChips = false)
            _toastEvent.tryEmit(msg)
            _finishEvent.tryEmit(Unit)
            return
        }
        if (retryDetailOnce("empty-detail")) return
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
        } else {
            AppLog.i("echo-detail-empty key=$sourceKey id=$vodId name=$vodName")
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    private fun fallbackToPreviousTarget(reason: String?): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        if (!reason.isNullOrEmpty()) _toastEvent.tryEmit(reason)
        return true
    }

    private fun startSourceSearch() {
        val title = searchTitle.ifEmpty { vodName.trim() }
        if (title.isEmpty()) return
        if (_sourcesSearching.value && searchTitle == title) return
        searchTitle = title
        searchToken = SEARCH_SEQ.incrementAndGet()
        val myToken = searchToken
        val tokenStr = "detail_$myToken"
        val checked = SearchHelper.getSourcesForSearch()
        val home = ApiConfig.get().homeSourceBean()
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && it.isQuickSearch() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        _sourcesSearching.value = sources.isNotEmpty()
        if (sources.isEmpty()) return
        searchJob = viewModelScope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            val done = CompletableDeferred<Unit>()
                            pendingSearchDone.put(bean.key.orEmpty(), done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                    searchCaller.getSearch(bean.key, title, tokenStr)
                                    done.await()
                                }
                            } finally {
                                pendingSearchDone.remove(bean.key)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (tokenStr == currentTokenStr()) {
                _sourcesSearching.value = false
                if (fallbackActive && fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
            }
        }
    }

    private fun onSearchResult(data: AbsXml?) {
        val info = data ?: return
        if (info.searchToken != currentTokenStr()) return
        pendingSearchDone.remove(info.sourceKey)?.complete(Unit)
        val videos = info.movie?.videoList.orEmpty()
        val fresh = videos.filter {
            !it.id.isNullOrEmpty() && it.name?.trim() == searchTitle
                    && !usedSourceKeys.contains(it.sourceKey)
                    && it.sourceKey != sourceKey
        }.filter { candidateKeys.add(candidateKey(it)) }
        if (fresh.isNotEmpty()) {
            synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
            publishSourceChips()
            if (fallbackActive && fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
        }
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

    private fun publishSourceChips() {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
        _sourceChips.value = candidates
            .filter { !usedSourceKeys.contains(it.sourceKey) && it.sourceKey != sourceKey }
            .map { video ->
                val key = video.sourceKey.orEmpty()
                SourceChip(key, ApiConfig.get().getSource(key)?.name ?: key)
            }
            .distinctBy { it.key }
    }

    fun candidateForKey(key: String): Movie.Video? =
        synchronized(fallbackCandidates) { fallbackCandidates.firstOrNull { it.sourceKey == key } }

    fun switchSource(video: Movie.Video) {
        stopPlaybackForSwitch()
        captureFallbackEpisode()
        detailRetryTarget = null
        usedSourceKeys.add(video.sourceKey.orEmpty())
        vodName = video.name ?: vodName
        vodPicture = video.pic ?: vodPicture
        resetEngineState(keepChips = true)
        loadDetail(video.id.orEmpty(), video.sourceKey.orEmpty())
    }

    private fun stopPlaybackForSwitch() {
        val info = vodInfo ?: return
        if (switchSnapshot == null) {
            switchSnapshot = SwitchSnapshot(
                info,
                vodId,
                sourceKey,
                firstsourceKey,
                vodName,
                vodPicture,
                wasPlaying = _fullScreen.value,
            )
        }
        sendCommand(PlaybackCommand.StopForSourceSwitch(str(R.string.detail_switching_source)))
    }

    private fun rollbackManualSwitch(reason: String? = null): Boolean {
        val snapshot = switchSnapshot ?: return false
        if (snapshot.vodInfo.seriesMap?.get(snapshot.vodInfo.playFlag).isNullOrEmpty()) return false
        switchSnapshot = null
        nextDetailRequestToken()
        publishVodInfo(snapshot.vodInfo)
        vodId = snapshot.vodId
        sourceKey = snapshot.sourceKey
        firstsourceKey = snapshot.firstsourceKey
        vodName = snapshot.vodName
        vodPicture = snapshot.vodPicture
        resetEngineState(keepChips = true)
        _toastEvent.tryEmit(
            if (reason.isNullOrEmpty()) {
                str(R.string.detail_switch_failed)
            } else {
                str(R.string.detail_switch_failed_reason, reason)
            },
        )
        _pageState.value = PageState.Ready
        bumpRevision()
        applyPlaybackEntry(DetailPlaybackEntry.SwitchRollback, resumable = snapshot.wasPlaying)
        return true
    }

    private fun enterEmpty(msg: String? = null) {
        sendCommand(PlaybackCommand.ClearSourceSwitchTip)
        AppLog.i("echo-detail-empty-state msg=$msg key=$sourceKey id=$vodId")
        _pageState.value = PageState.Empty(msg)
    }

    fun startFallbackAfterLinesExhausted(): Boolean = startFallbackIfNeeded(auto = true, fromLinesExhausted = true)

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        val currentSource = ApiConfig.get().getSource(sourceKey)
        if (currentSource != null && !currentSource.isChangeable()) return false
        if (fallbackActive) return true
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return false
        fallbackKeepCurrentDetail = fromLinesExhausted && vodInfo != null && !vodInfo?.seriesMap.isNullOrEmpty()
        captureFallbackEpisode()
        searchTitle = title
        usedSourceKeys.add(sourceKey)
        fallbackActive = true
        fallbackAutoSwitch = auto
        triedKeys.add(candidateKey(sourceKey, vodId))
        loadNextFallbackCandidate()
        return fallbackActive
    }

    private fun loadNextFallbackCandidate() {
        while (true) {
            val video = synchronized(fallbackCandidates) {
                val index = fallbackCandidates.indexOfFirst { isAutoSwitchable(it.sourceKey.orEmpty()) }
                if (index < 0) null else fallbackCandidates.removeAt(index)
            } ?: break
            val cKey = candidateKey(video)
            if (usedSourceKeys.contains(video.sourceKey) || !triedKeys.add(cKey)) continue
            fallbackLoadingCandidate = true
            fallbackActive = true
            fallbackAutoSwitch = true
            usedSourceKeys.add(video.sourceKey.orEmpty())
            vodName = video.name ?: vodName
            vodPicture = video.pic ?: vodPicture
            publishSourceChips()
            scheduleDetailTimeout()
            loadDetailInternal(video.id.orEmpty(), video.sourceKey.orEmpty(), detailRequestToken)
            return
        }
        publishSourceChips()
        if (!_sourcesSearching.value) finishFallbackWithoutResult()
    }

    private fun isAutoSwitchable(key: String): Boolean {
        if (key.isEmpty()) return false
        val source = ApiConfig.get().getSource(key) ?: return false
        return source.isChangeable()
    }

    private fun loadDetailInternal(vid: String, key: String, requestToken: Int) {
        vodId = vid
        sourceKey = key
        firstsourceKey = key
        refreshCollectState(sourceKey, vodId)
        sourceViewModel.getDetail(sourceKey, vodId, true, requestToken)
    }

    private fun finishFallbackWithoutResult() {
        val keep = fallbackKeepCurrentDetail
        resetEngineState(keepChips = true)
        if (!keep && rollbackManualSwitch()) return
        if (!keep && _pageState.value != PageState.Ready) {
            enterEmpty()
        }
    }

    private fun scheduleDetailTimeout() {
        if (detailTimeoutScheduled) return
        detailTimeoutScheduled = true
        detailTimeoutJob = viewModelScope.launch {
            delay(DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
            detailTimeoutScheduled = false
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                sourceViewModel.cancelDetail()
                loadNextFallbackCandidate()
            }
        }
    }

    private fun cancelDetailTimeout() {
        detailTimeoutScheduled = false
        detailTimeoutJob?.cancel()
        detailTimeoutJob = null
        detailRetryJob?.cancel()
        detailRetryJob = null
    }

    private fun captureFallbackEpisode() {
        val info = vodInfo
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        if (info?.seriesMap == null || info.playFlag.isNullOrEmpty()) return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.isEmpty()) return
        fallbackEpisodeIndex = info.playIndex.coerceIn(0, list.size - 1)
        fallbackEpisode = list[fallbackEpisodeIndex]
    }

    private fun restoredSelection(info: VodInfo): Pair<String?, Int> {
        val episode = fallbackEpisode
        if (episode == null || fallbackEpisodeIndex < 0 || info.seriesMap == null) {
            return info.playFlag to info.playIndex
        }
        val preferredFlag = info.playFlag
        val preferredList = info.seriesMap?.get(preferredFlag)
        var matched = findMatchingEpisodeIndex(episode, preferredList)
        if (matched >= 0) {
            return preferredFlag to matched
        }
        for (flag in info.seriesFlags.orEmpty()) {
            if (flag.name.isNullOrEmpty() || flag.name == preferredFlag) continue
            matched = findMatchingEpisodeIndex(episode, info.seriesMap?.get(flag.name))
            if (matched >= 0) {
                return flag.name to matched
            }
        }
        if (preferredList != null && preferredList.isNotEmpty()) {
            return preferredFlag to fallbackEpisodeIndex.coerceIn(0, preferredList.size - 1)
        }
        return preferredFlag to info.playIndex
    }

    private fun resetEngineState(keepChips: Boolean) {
        fallbackActive = false
        fallbackAutoSwitch = false
        fallbackKeepCurrentDetail = false
        fallbackLoadingCandidate = false
        detailTimeoutScheduled = false
        cancelDetailTimeout()
        triedKeys.clear()
        if (!keepChips) {
            synchronized(fallbackCandidates) { fallbackCandidates.clear() }
            candidateKeys.clear()
        }
        publishSourceChips()
    }

    fun destroyEngine() {
        cancelDetailTimeout()
        sourceViewModel.cancelDetail()
        searchCaller.cancelSearch()
        Thunder.cancelPendingParse()
    }

    private fun candidateKey(video: Movie.Video): String =
        (video.sourceKey ?: "") + "|" + (video.id ?: "")

    private fun candidateKey(key: String, id: String): String = "$key|$id"

    fun onEpisodeClick(position: Int) {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (position < 0 || position >= list.size) return
        if (position != manualLineSwitchTargetIndex) manualLineSwitchPending = false
        commitVodInfo(info.copy(playIndex = position))
        onPlayRequested(DetailPlaybackEntry.Episode)
    }

    fun onFlagClick(flagName: String) {
        val info = vodInfo ?: return
        if (info.playFlag == flagName) return
        val oldList = info.seriesMap?.get(info.playFlag)
        val currentIndex = info.playIndex.coerceAtLeast(0)
        val currentSeries = oldList?.getOrNull(currentIndex)
        val newList = info.seriesMap?.get(flagName)
        val newIndex = if (newList != null && newList.isNotEmpty()) {
            findSameEpisodeIndex(currentSeries, newList, currentIndex)
        } else {
            info.playIndex
        }
        manualLineSwitchPending = true
        manualLineSwitchTargetIndex = newIndex
        _qualityOptions.value = emptyList()
        _qualitySelected.value = 0
        commitVodInfo(info.copy(playFlag = flagName, playIndex = newIndex))
    }

    fun toggleReverse() {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.size <= 1) return
        info.reverse()
        commitVodInfo(info.copy(reverseSort = !info.reverseSort, playIndex = (list.size - 1) - info.playIndex))
    }

    fun toggleCollect() {
        val info = vodInfo ?: return
        val key = sourceKey
        if (_collected.value) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.deleteVodCollect(key, info)
                } catch (th: Throwable) {
                    AppLog.e("DetailViewModel", "collect delete failed: " + th, th)
                }
            }
            _toastEvent.tryEmit(str(R.string.toast_removed_from_collect))
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    AppGraph.collectRepository.insertVodCollect(key, info)
                } catch (th: Throwable) {
                    AppLog.e("DetailViewModel", "collect insert failed: " + th, th)
                }
            }
            _toastEvent.tryEmit(str(R.string.toast_added_to_collect))
        }
        _collected.value = !_collected.value
    }

    fun saveFollow(days: Set<Int>, hour: Int) {
        val info = vodInfo ?: return
        val key = sourceKey
        val vid = vodId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppGraph.followRepository.upsert(key, info, days, hour)
                val record = AppGraph.followRepository.find(key, vid)
                withContext(Dispatchers.Main) { _follow.value = record }
            } catch (th: Throwable) {
                AppLog.e("DetailViewModel", "follow save failed: " + th, th)
            }
        }
        _toastEvent.tryEmit(str(R.string.toast_follow_saved))
    }

    private fun updateQualityOptions(result: org.json.JSONObject?) {
        val options = ArrayList<String>()
        try {
            val value = result?.opt("url")
            val urls = when (value) {
                is org.json.JSONArray -> value
                is String -> org.json.JSONArray(value)
                else -> null
            }
            if (urls != null) {
                var i = 0
                while (i + 1 < urls.length()) {
                    options.add(urls.optString(i))
                    i += 2
                }
            }
        } catch (ignored: Throwable) {
            AppLog.d("DetailViewModel", "quality url list parse failed, keep empty options")
        }
        if (options == _qualityOptions.value) return
        _qualityOptions.value = options
        _qualitySelected.value = 0
    }

    fun switchQuality(position: Int) {
        sendCommand(PlaybackCommand.SelectQuality(position))
    }

    fun onQualitySelectionAccepted(position: Int) {
        _qualitySelected.value = position
    }

    private fun sendCommand(command: PlaybackCommand) {
        playbackCommandChannel.trySend(command)
    }

    private fun observeUiEvents() {
        viewModelScope.launch {
            UiEvents.changes.collect { change ->
                if (change is UiChange.PlaybackStarted) onPlaybackStarted()
            }
        }
    }

    private fun onPlaybackStarted() {
        val info = vodInfo ?: return
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        if (playing.playFlag != info.playFlag || playing.playIndex != info.playIndex) return
        val controller = PlaybackService.peek()?.controller() ?: return
        if (!controller.isSameStartedContent()) return
        AppLog.i("echo-history insert on playback-started key=" + sourceKey + "|" + vodId)
        insertVod()
    }

    fun syncEpisodeAfterMusicReturn() {
        val info = vodInfo ?: return
        val playing = PlaybackService.peek()?.controller()?.vod() ?: return
        if (playing.id != info.id) return
        if (playing.playFlag == info.playFlag && playing.playIndex == info.playIndex) return
        syncPlayingVodInfo(playing)
    }

    suspend fun isVideoCollected(video: Movie.Video): Boolean = withContext(Dispatchers.IO) {
        AppGraph.collectRepository.isVodCollect(video.sourceKey, video.id)
    }

    fun toggleVideoCollect(video: Movie.Video, toCollect: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (toCollect) {
                AppGraph.collectRepository.insertVodCollect(video.sourceKey, toVodInfo(video))
            } else {
                AppGraph.collectRepository.deleteVodCollect(video.sourceKey, toVodInfo(video))
            }
        }
    }

    private fun syncPlayingVodInfo(playing: VodInfo) {
        val info = vodInfo ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        val newFlag = playing.playFlag
        if (newFlag.isNullOrEmpty() || info.seriesMap?.containsKey(newFlag) != true) return
        val newList = info.seriesMap?.get(newFlag) ?: return
        if (newList.isEmpty()) return
        val playingList = playing.seriesMap?.get(newFlag)
        val playingSeries = playingList?.getOrNull(playing.playIndex.coerceIn(0, playingList.size - 1))
        val newIndex = findSameEpisodeIndex(playingSeries, newList, playing.playIndex)
        val cfg = playing.playerCfg ?: info.playerCfg
        commitVodInfo(
            info.copy(
                playFlag = newFlag,
                playIndex = newIndex,
                playerCfg = cfg,
                playNote = playNoteOf(info, newFlag, newIndex),
            ),
        )
        AppLog.i("echo-detail sync -> $newFlag/$newIndex")
    }

    private fun insertVod() {
        val info = vodInfo ?: return
        EpisodeTotals.putFromVod(info)
        HistoryWriter.write(firstsourceKey, info.copy(playNote = playNoteOf(info, info.playFlag, info.playIndex)))
    }

    private fun playNoteOf(info: VodInfo, flag: String?, index: Int): String {
        return try {
            info.seriesMap?.get(flag)?.get(index)?.name ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun preparePlaySession(): PlaybackSession? {
        val info = vodInfo ?: return null
        val list = info.seriesMap?.get(info.playFlag) ?: return null
        if (list.isEmpty()) return null
        val preview = info.copy(
            playNote = playNoteOf(info, info.playFlag, info.playIndex),
            seriesFlags = copySeriesFlags(info.seriesFlags),
            seriesMap = copySeriesMap(info.seriesMap),
        )
        return PlaybackSession(preview, sourceKey, consumeManualLineSwitch())
    }

    private fun copySeriesFlags(flags: ArrayList<VodInfo.VodSeriesFlag>?): ArrayList<VodInfo.VodSeriesFlag>? {
        if (flags == null) return null
        val copy = ArrayList<VodInfo.VodSeriesFlag>(flags.size)
        for (flag in flags) {
            copy.add(VodInfo.VodSeriesFlag(flag.name))
        }
        return copy
    }

    private fun copySeriesMap(
        map: LinkedHashMap<String?, MutableList<VodInfo.VodSeries>>?,
    ): LinkedHashMap<String?, MutableList<VodInfo.VodSeries>>? {
        if (map == null) return null
        val copy = LinkedHashMap<String?, MutableList<VodInfo.VodSeries>>()
        for ((flag, list) in map) {
            val items = ArrayList<VodInfo.VodSeries>(list.size)
            for (series in list) {
                items.add(VodInfo.VodSeries(series.name, series.url))
            }
            copy[flag] = items
        }
        return copy
    }

    private fun findSameEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>, fallback: Int): Int {
        if (target.isEmpty()) return 0
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) {
            return fallback.coerceIn(0, target.size - 1)
        }
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return if (matched >= 0) matched else fallback.coerceIn(0, target.size - 1)
    }

    private fun findMatchingEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>?): Int {
        if (target.isNullOrEmpty()) return -1
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) return -1
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return matched
    }

    private fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) return 0
        if (targetName.equals(currentName, ignoreCase = true)) return 100
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) return 80
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) return 70
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) return 60
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (name.isNullOrEmpty()) return -1
        return try {
            var text = name.replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.lowercase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")

        val matcher = Regex("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})").find(text) // i18n: keep(选集序号匹配关键字)
            if (matcher != null) {
                matcher.groupValues[1].toInt()
            } else {
                val number = text.replace(Regex("\\D+"), "")
                if (number.isNotEmpty()) number.toInt() else -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    override fun onCleared() {
        destroyEngine()
        super.onCleared()
    }

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L
        private const val DETAIL_RETRY_DELAY_MS = 700L
        private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
        private const val SOURCE_SEARCH_CONCURRENCY = 6

        private const val SOURCE_EMPTY_MSG = "数据列表" // i18n: keep(源错误消息判据值,只比较不展示)

        internal fun isSourceErrorMsg(msg: String?): Boolean =
            !msg.isNullOrEmpty() && msg != SOURCE_EMPTY_MSG
    }
}
