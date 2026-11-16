package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserPreferences
import com.example.data.repository.MangaRepository
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import com.example.source.SourceRegistry
import com.example.ui.components.MangaCardUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FavoriteSourceQuickItem(
    val id: String,
    val name: String,
    val language: String,
    val isDemo: Boolean
)

data class HomeUiState(
    val isLoadingLiveFeeds: Boolean = false,
    val feedError: String? = null,
    val feedCloudflareUrl: String? = null,
    val demoModeEnabled: Boolean = false,
    val compactCards: Boolean = false,
    val sectionOrder: List<HomeSectionType> = HomeSectionType.defaultOrder(),
    val hiddenSections: Set<String> = emptySet(),
    val favoriteSources: List<FavoriteSourceQuickItem> = emptyList(),
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
        val cloudflareUrl: String? = null,
        val popular: List<MangaCardUi> = emptyList(),
        val trending: List<MangaCardUi> = emptyList(),
        val latest: List<MangaCardUi> = emptyList(),
        val recommended: List<MangaCardUi> = emptyList(),
        val personalized: List<MangaCardUi> = emptyList()
    )

    private val dbSectionFlows = combine(
        mangaRepo.continueReading,
        mangaRepo.recentlyUpdated,
        mangaRepo.recentlyAdded,
        mangaRepo.downloadedChapters,
        mangaRepo.history
    ) { cont, upd, add, dl, hist ->
        HomeDbSnapshot(cont, upd, add, dl, hist)
    }

    private data class HomeDbSnapshot(
        val continueList: List<MangaEntity>,
        val updatedList: List<MangaEntity>,
        val addedList: List<MangaEntity>,
        val downloadedChapters: List<com.example.data.local.entity.ChapterEntity>,
        val historyList: List<com.example.data.local.entity.ReadingHistoryEntity>
    )

    val uiState: StateFlow<HomeUiState> = combine(
        prefsRepo.preferences,
        dbSectionFlows,
        mangaRepo.libraryManga,
        sourceRegistry.activeSources,
        liveFeedState
    ) { prefs, db, libraryList, activeAdapters, live ->
        val libById = libraryList.associateBy { it.id }
        val downloadedMangaCards = db.downloadedChapters
            .distinctBy { it.mangaId }
            .take(12)
            .mapNotNull { ch ->
                libById[ch.mangaId]?.toCardUi()?.copy(latestChapter = ch.name)
            }

        val historyCards = db.historyList
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

        val favSources = activeAdapters.map {
            FavoriteSourceQuickItem(
                id = it.id,
                name = it.name,
                language = it.language,
                isDemo = it.isDemoSource
            )
        }

        HomeUiState(
            isLoadingLiveFeeds = live.isLoading,
            feedError = live.error,
            feedCloudflareUrl = live.cloudflareUrl,
            demoModeEnabled = prefs.demoModeEnabled,
            compactCards = prefs.homeCompactCards,
            sectionOrder = orderedTypes,
            hiddenSections = prefs.hiddenHomeSections,
            favoriteSources = favSources,
            continueReading = db.continueList.map { it.toCardUi() },
            recentlyUpdated = db.updatedList.map { it.toCardUi() },
            recentlyAdded = db.addedList.map { it.toCardUi() },
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
            liveFeedState.update { it.copy(isLoading = true, error = null, cloudflareUrl = null) }
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                liveFeedState.update {
                    it.copy(
                        isLoading = false,
                        error = NetworkErrorFormatter.describe(t),
                        cloudflareUrl = NetworkErrorFormatter.extractCloudflareUrl(t)
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

    fun toggleCompactCards(compact: Boolean) {
        viewModelScope.launch {
            prefsRepo.setHomeCompactCards(compact)
        }
    }

    fun openSourceManga(card: MangaCardUi, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val existing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                mangaRepo.observeManga(card.id).first()
            }
            if (existing != null) {
                onReady(existing.id)
                return@launch
            }
            val sourceId = card.id.substringBefore("::")
            val mangaKey = card.id.substringAfter("::")
            val summary = SourceMangaItem(
                sourceId = sourceId,
                sourceName = card.sourceName,
                mangaKey = mangaKey,
                title = card.title,
                altTitle = card.altTitle,
                coverUrl = card.coverUrl,
                latestChapterName = card.latestChapter,
                status = card.status,
                isDemoData = card.isDemoData
            )
            val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                mangaRepo.cacheSourceManga(summary)
            }
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
