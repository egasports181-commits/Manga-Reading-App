package com.example.ui.screens.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import com.example.source.SourceRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ReaderUiState(
    val manga: MangaEntity? = null,
    val chapter: ChapterEntity? = null,
    val allChapters: List<ChapterEntity> = emptyList(),
    val pages: List<SourcePage> = emptyList(),
    val currentPage: Int = 0,
    val bookmarkedPages: Set<Int> = emptySet(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isOfflineChapter: Boolean = false,
    // Active Settings
    val readingMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val effectiveMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val scaleMode: ImageScaleMode = ImageScaleMode.FIT_SCREEN,
    val customZoom: Float = 1.0f,
    val background: ReaderBackground = ReaderBackground.BLACK,
    val tapZonesEnabled: Boolean = true,
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.SIDES,
    val pageMarginsDp: Int = 0,
    val autoHideMs: Int = 0,
    val transitionMode: ReaderTransitionMode = ReaderTransitionMode.SLIDE,
    val volumeNavEnabled: Boolean = false,
    val keepScreenAwake: Boolean = true,
    val hideSystemBars: Boolean = true,
    val brightness: Float = -1f,
    val orientationLock: OrientationLock = OrientationLock.FREE,
    val hasPrevChapter: Boolean = false,
    val hasNextChapter: Boolean = false
)

class ReaderViewModel(
    private val mangaId: String,
    initialChapterId: String,
    private val mangaRepo: MangaRepository,
    private val downloadRepo: DownloadRepository,
    private val sourceRegistry: SourceRegistry,
    private val prefsRepo: AppPreferencesRepository,
    private val imageLoader: ImageLoader,
    private val appContext: Context
) : ViewModel() {

    private val currentChapterId = MutableStateFlow(initialChapterId)
    private val loadState = MutableStateFlow(LoadCache())
    private var sessionStartMs = System.currentTimeMillis()

    private data class LoadCache(
        val pages: List<SourcePage> = emptyList(),
        val currentPage: Int = 0,
        val isLoading: Boolean = true,
        val error: String? = null,
        val isOffline: Boolean = false
    )

    val uiState: StateFlow<ReaderUiState> = combine(
        mangaRepo.observeManga(mangaId),
        mangaRepo.observeChapters(mangaId),
        currentChapterId,
        prefsRepo.preferences,
        loadState
    ) { manga, chaptersAscOrDesc, activeChId, prefs, cache ->
        val sortedAsc = chaptersAscOrDesc.sortedBy { it.chapterNumber }
        val idx = sortedAsc.indexOfFirst { it.id == activeChId }
        val activeChapter = if (idx >= 0) sortedAsc[idx] else sortedAsc.firstOrNull()
        val effective = resolveEffectiveMode(prefs.defaultReadingMode, manga)
        val bookmarks = activeChapter?.let { mangaRepo.parseBookmarkedPages(it.bookmarkedPagesJson) } ?: emptySet()

        ReaderUiState(
            manga = manga,
            chapter = activeChapter,
            allChapters = chaptersAscOrDesc,
            pages = cache.pages,
            currentPage = cache.currentPage,
            bookmarkedPages = bookmarks,
            isLoading = cache.isLoading,
            error = cache.error,
            isOfflineChapter = cache.isOffline,
            readingMode = prefs.defaultReadingMode,
            effectiveMode = effective,
            scaleMode = prefs.defaultScaleMode,
            customZoom = prefs.customZoomScale,
            background = prefs.readerBackground,
            tapZonesEnabled = prefs.tapZonesEnabled,
            tapZoneLayout = prefs.readerTapZoneLayout,
            pageMarginsDp = prefs.readerPageMarginsDp,
            autoHideMs = prefs.readerAutoHideMs,
            transitionMode = prefs.readerTransitionMode,
            volumeNavEnabled = prefs.volumeKeyNavigation,
            keepScreenAwake = prefs.keepScreenAwake,
            hideSystemBars = prefs.hideSystemBars,
            brightness = prefs.readerBrightness,
            orientationLock = prefs.orientationLock,
            hasPrevChapter = idx > 0,
            hasNextChapter = idx in 0 until sortedAsc.lastIndex
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderUiState())

    init {
        loadChapter(initialChapterId)
    }

    private fun resolveEffectiveMode(mode: ReadingMode, manga: MangaEntity?): ReadingMode {
        if (mode != ReadingMode.AUTO) return mode
        val genres = manga?.genres.orEmpty().lowercase()
        return if (genres.contains("webtoon") || genres.contains("long strip") || genres.contains("manhwa")) {
            ReadingMode.WEBTOON
        } else {
            ReadingMode.RIGHT_TO_LEFT
        }
    }

    fun loadChapter(chapterId: String) {
        currentChapterId.value = chapterId
        sessionStartMs = System.currentTimeMillis()
        viewModelScope.launch {
            loadState.value = LoadCache(isLoading = true)
            try {
                val chapters = mangaRepo.observeChapters(mangaId).first()
                val chapter = chapters.firstOrNull { it.id == chapterId }
                    ?: throw IllegalStateException("Chapter not found")
                val manga = mangaRepo.observeManga(mangaId).first()
                    ?: throw IllegalStateException("Manga not found")

                val offlinePages = downloadRepo.getOfflinePages(chapter)
                val pages = if (offlinePages != null) {
                    offlinePages
                } else {
                    val source = sourceRegistry.getSourceById(manga.sourceId)
                        ?: throw IllegalStateException("Source '${manga.sourceName}' unavailable")
                    source.getChapterPages(chapter.chapterKey)
                }

                val resumePage = chapter.lastPageRead.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                loadState.value = LoadCache(
                    pages = pages,
                    currentPage = resumePage,
                    isLoading = false,
                    error = if (pages.isEmpty()) "No pages available for this chapter." else null,
                    isOffline = offlinePages != null
                )
                prefetchAround(resumePage, pages)
            } catch (e: Exception) {
                loadState.value = LoadCache(
                    isLoading = false,
                    error = NetworkErrorFormatter.describe(e)
                )
            }
        }
    }

    fun onPageChanged(newPage: Int) {
        val pages = loadState.value.pages
        if (pages.isEmpty()) return
        val clamped = newPage.coerceIn(0, pages.lastIndex)
        if (clamped == loadState.value.currentPage) return
        loadState.update { it.copy(currentPage = clamped) }
        prefetchAround(clamped, pages)
        persistProgress(clamped, pages.size)
    }

    fun toggleCurrentPageBookmark() {
        val chId = currentChapterId.value
        val pageIdx = loadState.value.currentPage
        viewModelScope.launch {
            mangaRepo.togglePageBookmark(chId, pageIdx)
        }
    }

    fun persistCurrentSession() {
        val pages = loadState.value.pages
        if (pages.isNotEmpty()) {
            persistProgress(loadState.value.currentPage, pages.size)
        }
    }

    private fun persistProgress(pageIndex: Int, totalPages: Int) {
        val chId = currentChapterId.value
        val now = System.currentTimeMillis()
        val elapsed = (now - sessionStartMs).coerceIn(0L, 600_000L)
        sessionStartMs = now
        viewModelScope.launch {
            mangaRepo.saveReaderProgress(
                mangaId = mangaId,
                chapterId = chId,
                pageIndex = pageIndex,
                totalPages = totalPages,
                sessionDurationMs = elapsed
            )
        }
    }

    private fun prefetchAround(index: Int, pages: List<SourcePage>) {
        val start = (index - 1).coerceAtLeast(0)
        val end = (index + 4).coerceAtMost(pages.lastIndex)
        for (i in start..end) {
            if (i == index) continue
            val req = ImageRequest.Builder(appContext)
                .data(pages[i].imageUrl)
                .build()
            imageLoader.enqueue(req)
        }
    }

    fun goToPreviousChapter() {
        val sortedAsc = uiState.value.allChapters.sortedBy { it.chapterNumber }
        val idx = sortedAsc.indexOfFirst { it.id == currentChapterId.value }
        if (idx > 0) {
            persistCurrentSession()
            loadChapter(sortedAsc[idx - 1].id)
        }
    }

    fun goToNextChapter() {
        val sortedAsc = uiState.value.allChapters.sortedBy { it.chapterNumber }
        val idx = sortedAsc.indexOfFirst { it.id == currentChapterId.value }
        if (idx in 0 until sortedAsc.lastIndex) {
            persistCurrentSession()
            loadChapter(sortedAsc[idx + 1].id)
        }
    }

    // Reader Settings Mutators
    fun setReadingMode(mode: ReadingMode) = viewModelScope.launch { prefsRepo.setDefaultReadingMode(mode) }
    fun setScaleMode(mode: ImageScaleMode) = viewModelScope.launch { prefsRepo.setDefaultScaleMode(mode) }
    fun setCustomZoom(zoom: Float) = viewModelScope.launch { prefsRepo.setCustomZoom(zoom) }
    fun setBackground(bg: ReaderBackground) = viewModelScope.launch { prefsRepo.setReaderBackground(bg) }
    fun setTapZones(enabled: Boolean) = viewModelScope.launch { prefsRepo.setTapZones(enabled) }
    fun setTapZoneLayout(layout: TapZoneLayout) = viewModelScope.launch { prefsRepo.setReaderTapZoneLayout(layout) }
    fun setPageMarginsDp(dp: Int) = viewModelScope.launch { prefsRepo.setReaderPageMarginsDp(dp) }
    fun setAutoHideMs(ms: Int) = viewModelScope.launch { prefsRepo.setReaderAutoHideMs(ms) }
    fun setTransitionMode(mode: ReaderTransitionMode) = viewModelScope.launch { prefsRepo.setReaderTransitionMode(mode) }
    fun setVolumeNav(enabled: Boolean) = viewModelScope.launch { prefsRepo.setVolumeNavigation(enabled) }
    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch { prefsRepo.setKeepScreenAwake(enabled) }
    fun setHideSystemBars(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHideSystemBars(enabled) }
    fun setBrightness(value: Float) = viewModelScope.launch { prefsRepo.setReaderBrightness(value) }
    fun setOrientationLock(lock: OrientationLock) = viewModelScope.launch { prefsRepo.setOrientationLock(lock) }

    companion object {
        fun factory(
            mangaId: String,
            chapterId: String,
            mangaRepo: MangaRepository,
            downloadRepo: DownloadRepository,
            sourceRegistry: SourceRegistry,
            prefsRepo: AppPreferencesRepository,
            imageLoader: ImageLoader,
            appContext: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(
                mangaId, chapterId, mangaRepo, downloadRepo, sourceRegistry, prefsRepo, imageLoader, appContext
            ) as T
        }
    }
}
