package com.example.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MangaEntity
import com.example.data.local.entity.TrackingEntryEntity
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MangaRepository
import com.example.data.repository.TrackingRepository
import com.example.domain.model.RemoteTrackerSearchResult
import com.example.domain.model.TrackerService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MangaDetailUiState(
    val manga: MangaEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val assignedCategoryIds: List<Long> = emptyList(),
    val trackingEntries: List<TrackingEntryEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val statusBanner: String? = null,
    val showUnreadOnly: Boolean = false,
    val ascendingChapters: Boolean = false,
    val trackerSearchResults: List<RemoteTrackerSearchResult> = emptyList(),
    val trackerSearchError: String? = null
)

class MangaDetailViewModel(
    private val mangaId: String,
    private val mangaRepo: MangaRepository,
    private val downloadRepo: DownloadRepository,
    private val trackingRepo: TrackingRepository
) : ViewModel() {

    private val localState = MutableStateFlow(LocalDetailControls())

    private data class LocalDetailControls(
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val banner: String? = null,
        val unreadOnly: Boolean = false,
        val ascending: Boolean = false,
        val trackerResults: List<RemoteTrackerSearchResult> = emptyList(),
        val trackerError: String? = null
    )

    val uiState: StateFlow<MangaDetailUiState> = combine(
        mangaRepo.observeManga(mangaId),
        mangaRepo.observeChapters(mangaId),
        mangaRepo.categories,
        mangaRepo.observeMangaCategoryIds(mangaId),
        trackingRepo.observeTrackingForManga(mangaId),
        localState
    ) { values ->
        val manga = values[0] as MangaEntity?
        @Suppress("UNCHECKED_CAST")
        val rawChapters = values[1] as List<ChapterEntity>
        @Suppress("UNCHECKED_CAST")
        val categories = values[2] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val assigned = values[3] as List<Long>
        @Suppress("UNCHECKED_CAST")
        val tracking = values[4] as List<TrackingEntryEntity>
        val local = values[5] as LocalDetailControls

        val filtered = rawChapters
            .filter { !local.unreadOnly || !it.isRead }
            .let { list -> if (local.ascending) list.sortedBy { it.chapterNumber } else list }

        MangaDetailUiState(
            manga = manga,
            chapters = filtered,
            categories = categories,
            assignedCategoryIds = assigned,
            trackingEntries = tracking,
            isRefreshing = local.isRefreshing,
            error = local.error,
            statusBanner = local.banner,
            showUnreadOnly = local.unreadOnly,
            ascendingChapters = local.ascending,
            trackerSearchResults = local.trackerResults,
            trackerSearchError = local.trackerError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MangaDetailUiState(isRefreshing = true))

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            localState.update { it.copy(isRefreshing = true, error = null) }
            val res = mangaRepo.refreshMangaAndChapters(mangaId)
            localState.update {
                if (res.isSuccess) it.copy(isRefreshing = false)
                else it.copy(isRefreshing = false, error = res.exceptionOrNull()?.message ?: "Refresh failed")
            }
        }
    }

    fun toggleLibrary() {
        viewModelScope.launch { mangaRepo.toggleLibrary(mangaId) }
    }

    fun toggleBookmark() {
        viewModelScope.launch { mangaRepo.toggleMangaBookmark(mangaId) }
    }

    fun setCategories(categoryIds: List<Long>) {
        viewModelScope.launch { mangaRepo.setMangaCategories(mangaId, categoryIds) }
    }

    fun toggleChapterRead(chapter: ChapterEntity) {
        viewModelScope.launch { mangaRepo.setChapterRead(chapter.id, mangaId, !chapter.isRead) }
    }

    fun toggleChapterBookmark(chapter: ChapterEntity) {
        viewModelScope.launch { mangaRepo.setChapterBookmarked(chapter.id, !chapter.isBookmarked) }
    }

    fun toggleUnreadFilter() {
        localState.update { it.copy(unreadOnly = !it.unreadOnly) }
    }

    fun toggleChapterSort() {
        localState.update { it.copy(ascending = !it.ascending) }
    }

    fun downloadChapter(chapter: ChapterEntity) {
        val manga = uiState.value.manga ?: return
        viewModelScope.launch {
            downloadRepo.enqueueChapter(manga, chapter)
            localState.update { it.copy(banner = "Queued ${chapter.name} for download.") }
        }
    }

    fun downloadUnreadChapters() {
        val manga = uiState.value.manga ?: return
        val unread = uiState.value.chapters.filter { !it.isRead && !it.isDownloaded }
        viewModelScope.launch {
            downloadRepo.enqueueChapters(manga, unread)
            localState.update { it.copy(banner = "Queued ${unread.size} unread chapter(s).") }
        }
    }

    fun downloadChapterRange(fromNumber: Double, toNumber: Double) {
        val manga = uiState.value.manga ?: return
        val min = minOf(fromNumber, toNumber)
        val max = maxOf(fromNumber, toNumber)
        val selected = uiState.value.chapters.filter { it.chapterNumber in min..max && !it.isDownloaded }
        viewModelScope.launch {
            downloadRepo.enqueueChapters(manga, selected)
            localState.update { it.copy(banner = "Queued ${selected.size} chapter(s) in range.") }
        }
    }

    fun deleteDownloadedChapter(chapter: ChapterEntity) {
        viewModelScope.launch { downloadRepo.deleteDownloadedChapter(chapter) }
    }

    fun getNextChapterToRead(): ChapterEntity? {
        val all = uiState.value.chapters.sortedBy { it.chapterNumber }
        return all.firstOrNull { !it.isRead } ?: all.lastOrNull()
    }

    fun searchTracker(service: TrackerService, query: String) {
        viewModelScope.launch {
            localState.update { it.copy(trackerError = null, trackerResults = emptyList()) }
            val res = trackingRepo.searchRemote(service, query)
            localState.update {
                if (res.isSuccess) it.copy(trackerResults = res.getOrDefault(emptyList()))
                else it.copy(trackerError = res.exceptionOrNull()?.message)
            }
        }
    }

    fun saveTracking(entry: TrackingEntryEntity) {
        viewModelScope.launch {
            val res = trackingRepo.saveLocalAndOptionalSync(entry)
            localState.update {
                it.copy(
                    banner = if (res.isSuccess) "Tracking saved (${res.getOrNull()?.syncState})."
                    else "Sync error: saved with conflict marker."
                )
            }
        }
    }

    fun resolveConflict(entry: TrackingEntryEntity) {
        viewModelScope.launch { trackingRepo.resolveConflictKeepLocal(entry) }
    }

    fun clearBanner() {
        localState.update { it.copy(banner = null) }
    }

    companion object {
        fun factory(
            mangaId: String,
            mangaRepo: MangaRepository,
            downloadRepo: DownloadRepository,
            trackingRepo: TrackingRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MangaDetailViewModel(mangaId, mangaRepo, downloadRepo, trackingRepo) as T
        }
    }
}
