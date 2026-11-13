package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserPreferences
import com.example.data.repository.MangaRepository
import com.example.domain.model.*
import com.example.source.SourceRegistry
import com.example.ui.components.MangaCardUi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoadingLiveFeeds: Boolean = false,
    val feedError: String? = null,
    val demoModeEnabled: Boolean = false,
    val sectionOrder: List<HomeSectionType> = HomeSectionType.defaultOrder(),
    val hiddenSections: Set<String> = emptySet(),
    val continueReading: List<MangaCardUi> = emptyList(),
    val recentlyUpdated: List<MangaCardUi> = emptyList(),
    val recentlyAdded: List<MangaCardUi> = emptyList(),
    val popularManga: List<MangaCardUi> = emptyList(),
    val trendingManga: List<MangaCardUi> = emptyList(),
    val latestChapters: List<MangaCardUi> = emptyList(),
    val recommendedSeries: List<MangaCardUi> = emptyList(),
    val recentlyDownloaded: List<MangaCardUi> = emptyList(),
    val readingHistory: List<MangaCardUi> = emptyList(),
    val personalized: List<MangaCardUi> = emptyList()
)

class HomeViewModel(
    private val mangaRepo: MangaRepository,
    private val sourceRegistry: SourceRegistry,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    private val liveFeedState = MutableStateFlow(LiveFeedCache())

    private data class LiveFeedCache(
        val isLoading: Boolean = false,
        val error: String? = null,
        val popular: List<MangaCardUi> = emptyList(),
        val trending: List<MangaCardUi> = emptyList(),
        val latest: List<MangaCardUi> = emptyList(),
        val recommended: List<MangaCardUi> = emptyList(),
        val personalized: List<MangaCardUi> = emptyList()
    )

    val uiState: StateFlow<HomeUiState> = combine(
        prefsRepo.preferences,
        mangaRepo.continueReading,
        mangaRepo.recentlyUpdated,
        mangaRepo.recentlyAdded,
        mangaRepo.downloadedChapters,
        mangaRepo.history,
        mangaRepo.libraryManga,
        liveFeedState
    ) { values ->
        val prefs = values[0] as UserPreferences
        @Suppress("UNCHECKED_CAST")
        val continueList = values[1] as List<MangaEntity>
        @Suppress("UNCHECKED_CAST")
        val updatedList = values[2] as List<MangaEntity>
        @Suppress("UNCHECKED_CAST")
        val addedList = values[3] as List<MangaEntity>
        @Suppress("UNCHECKED_CAST")
        val downloadedChapters = values[4] as List<com.example.data.local.entity.ChapterEntity>
        @Suppress("UNCHECKED_CAST")
        val historyList = values[5] as List<com.example.data.local.entity.ReadingHistoryEntity>
        @Suppress("UNCHECKED_CAST")
        val libraryList = values[6] as List<MangaEntity>
        val live = values[7] as LiveFeedCache

        val libById = libraryList.associateBy { it.id }
        val downloadedMangaCards = downloadedChapters
            .distinctBy { it.mangaId }
            .take(12)
            .mapNotNull { ch ->
                libById[ch.mangaId]?.toCardUi()?.copy(latestChapter = ch.name)
            }

        val historyCards = historyList
            .distinctBy { it.mangaId }
            .take(15)
            .map { h ->
                val m = libById[h.mangaId]
                MangaCardUi(
                    id = h.mangaId,
                    title = h.mangaTitle,
                    coverUrl = h.coverUrl,
                    latestChapter = h.chapterName,
                    sourceName = m?.sourceName ?: "History",
                    status = MangaStatus.fromString(m?.status),
                    isBookmarked = m?.isBookmarked ?: false,
                    isInLibrary = m != null,
                    isDemoData = m?.isDemoData ?: false
                )
            }

        val orderedTypes = prefs.homeSectionOrder.mapNotNull { id ->
            HomeSectionType.entries.firstOrNull { it.id == id }
        }.ifEmpty { HomeSectionType.defaultOrder() }

        HomeUiState(
            isLoadingLiveFeeds = live.isLoading,
            feedError = live.error,
            demoModeEnabled = prefs.demoModeEnabled,
            sectionOrder = orderedTypes,
            hiddenSections = prefs.hiddenHomeSections,
            continueReading = continueList.map { it.toCardUi() },
            recentlyUpdated = updatedList.map { it.toCardUi() },
            recentlyAdded = addedList.map { it.toCardUi() },
            popularManga = live.popular,
            trendingManga = live.trending,
            latestChapters = live.latest,
            recommendedSeries = live.recommended,
            recentlyDownloaded = downloadedMangaCards,
            readingHistory = historyCards,
            personalized = live.personalized
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState(isLoadingLiveFeeds = true))

    init {
        refreshLiveFeeds()
    }

    fun refreshLiveFeeds() {
        viewModelScope.launch {
            liveFeedState.update { it.copy(isLoading = true, error = null) }
            try {
                val prefs = prefsRepo.preferences.first()
                val sources = sourceRegistry.activeSources.first()
                val primarySource = if (prefs.demoModeEnabled) {
                    sourceRegistry.demoSample
                } else {
                    sources.firstOrNull { !it.isDemoSource && it.id != "local_saf" }
                }

                if (primarySource == null) {
                    liveFeedState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val popularRes = primarySource.getPopular(1)
                val latestRes = primarySource.getLatest(1)
                val popularCards = popularRes.items.map { it.toCardUi() }
                val latestCards = latestRes.items.map { it.toCardUi() }

                // Personalized picks based on user's top library genres
                val lib = mangaRepo.libraryManga.first()
                val topGenre = lib.flatMap { it.genres.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key

                val personalizedCards = if (topGenre != null) {
                    runCatching {
                        primarySource.search("", 1, SearchFilters(genres = setOf(topGenre))).items
                    }.getOrDefault(emptyList()).map { it.toCardUi() }
                } else {
                    popularCards.shuffled().take(8)
                }

                liveFeedState.update {
                    LiveFeedCache(
                        isLoading = false,
                        error = null,
                        popular = popularCards.take(12),
                        trending = popularCards.drop(4).take(10).ifEmpty { popularCards.take(6) },
                        latest = latestCards.take(12),
                        recommended = popularCards.asReversed().take(10),
                        personalized = personalizedCards.take(10)
                    )
                }
            } catch (e: Exception) {
                liveFeedState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Could not reach source catalog. Check connection or enable Demo Mode."
                    )
                }
            }
        }
    }

    fun toggleDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setDemoMode(enabled)
            if (!enabled) {
                mangaRepo.removeDemoData()
            }
            refreshLiveFeeds()
        }
    }

    fun openSourceManga(card: MangaCardUi, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val existing = mangaRepo.observeManga(card.id).first()
            if (existing != null) {
                onReady(existing.id)
                return@launch
            }
            val sourceId = card.id.substringBefore("::")
            val mangaKey = card.id.substringAfter("::")
            val adapter = sourceRegistry.getSourceById(sourceId)
            val detail = runCatching { adapter?.getMangaDetails(mangaKey) }.getOrNull()
                ?: SourceMangaItem(
                    sourceId = sourceId,
                    sourceName = card.sourceName,
                    mangaKey = mangaKey,
                    title = card.title,
                    altTitle = card.altTitle,
                    coverUrl = card.coverUrl,
                    status = card.status,
                    isDemoData = card.isDemoData
                )
            val cached = mangaRepo.cacheSourceManga(detail)
            onReady(cached.id)
        }
    }

    fun toggleSectionVisibility(sectionId: String) {
        viewModelScope.launch {
            val current = prefsRepo.preferences.first().hiddenHomeSections.toMutableSet()
            if (sectionId in current) current.remove(sectionId) else current.add(sectionId)
            prefsRepo.setHiddenHomeSections(current)
        }
    }

    fun moveSection(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val order = prefsRepo.preferences.first().homeSectionOrder.toMutableList()
            if (fromIndex in order.indices && toIndex in order.indices) {
                val item = order.removeAt(fromIndex)
                order.add(toIndex, item)
                prefsRepo.setHomeSectionOrder(order)
            }
        }
    }

    private fun MangaEntity.toCardUi(): MangaCardUi {
        val readRatio = if (totalChapters > 0) {
            ((totalChapters - unreadCount).coerceAtLeast(0)).toFloat() / totalChapters.toFloat()
        } else null
        return MangaCardUi(
            id = id,
            title = title,
            altTitle = altTitle,
            coverUrl = coverUrl,
            latestChapter = latestChapterName,
            sourceName = sourceName,
            progressFraction = readRatio,
            unreadCount = unreadCount,
            status = MangaStatus.fromString(status),
            isBookmarked = isBookmarked,
            isInLibrary = inLibrary,
            isDemoData = isDemoData
        )
    }

    private fun SourceMangaItem.toCardUi(): MangaCardUi = MangaCardUi(
        id = "$sourceId::$mangaKey",
        title = title,
        altTitle = altTitle,
        coverUrl = coverUrl,
        latestChapter = latestChapterName,
        sourceName = sourceName,
        status = status,
        isDemoData = isDemoData
    )

    companion object {
        fun factory(
            mangaRepo: MangaRepository,
            sourceRegistry: SourceRegistry,
            prefsRepo: AppPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(mangaRepo, sourceRegistry, prefsRepo) as T
        }
    }
}
