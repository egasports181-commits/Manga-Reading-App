package com.example.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MangaCategoryCrossRef
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserPreferences
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.LibrarySort
import com.example.domain.model.MangaStatus
import com.example.ui.components.MangaCardUi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryFilterState(
    val searchQuery: String = "",
    val genre: String? = null,
    val status: MangaStatus? = null,
    val sourceName: String? = null,
    val language: String? = null,
    val unreadOnly: Boolean = false,
    val downloadedOnly: Boolean = false
)

data class LibraryUiState(
    val categories: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null, // null = All
    val mangaList: List<MangaCardUi> = emptyList(),
    val sort: LibrarySort = LibrarySort.LAST_READ,
    val sortAscending: Boolean = false,
    val filter: LibraryFilterState = LibraryFilterState(),
    val availableGenres: List<String> = emptyList(),
    val availableSources: List<String> = emptyList(),
    val availableLanguages: List<String> = emptyList(),
    val selectedMangaIds: Set<String> = emptySet(),
    val isGridLayout: Boolean = true,
    val statusMessage: String? = null
)

class LibraryViewModel(
    private val mangaRepo: MangaRepository,
    private val downloadRepo: DownloadRepository,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    private val localControls = MutableStateFlow(LocalControls())

    private data class LocalControls(
        val selectedCategoryId: Long? = null,
        val filter: LibraryFilterState = LibraryFilterState(),
        val selectedIds: Set<String> = emptySet(),
        val isGrid: Boolean = true,
        val statusMsg: String? = null
    )

    val uiState: StateFlow<LibraryUiState> = combine(
        mangaRepo.libraryManga,
        mangaRepo.categories,
        mangaRepo.categoryCrossRefs,
        mangaRepo.downloadedChapters,
        prefsRepo.preferences,
        localControls
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allManga = values[0] as List<MangaEntity>
        @Suppress("UNCHECKED_CAST")
        val categories = values[1] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val crossRefs = values[2] as List<MangaCategoryCrossRef>
        @Suppress("UNCHECKED_CAST")
        val downloadedChs = values[3] as List<ChapterEntity>
        val prefs = values[4] as UserPreferences
        val local = values[5] as LocalControls

        val downloadedMangaIds = downloadedChs.map { it.mangaId }.toSet()
        val mangaInCategory = if (local.selectedCategoryId == null) {
            allManga
        } else {
            val allowedIds = crossRefs.filter { it.categoryId == local.selectedCategoryId }.map { it.mangaId }.toSet()
            allManga.filter { it.id in allowedIds }
        }

        val f = local.filter
        val filtered = mangaInCategory.filter { m ->
            val matchQuery = f.searchQuery.isBlank() ||
                m.title.contains(f.searchQuery, true) ||
                m.altTitle.contains(f.searchQuery, true)
            val matchGenre = f.genre == null || m.genres.contains(f.genre, true)
            val matchStatus = f.status == null || MangaStatus.fromString(m.status) == f.status
            val matchSource = f.sourceName == null || m.sourceName == f.sourceName
            val matchLang = f.language == null || m.language.equals(f.language, true)
            val matchUnread = !f.unreadOnly || m.unreadCount > 0
            val matchDownloaded = !f.downloadedOnly || m.id in downloadedMangaIds
            matchQuery && matchGenre && matchStatus && matchSource && matchLang && matchUnread && matchDownloaded
        }

        val sorted = when (prefs.librarySort) {
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.DATE_ADDED -> filtered.sortedBy { it.dateAdded }
            LibrarySort.LAST_READ -> filtered.sortedBy { it.lastReadAt }
            LibrarySort.LATEST_UPDATE -> filtered.sortedBy { it.latestUpdateAt }
            LibrarySort.UNREAD_COUNT -> filtered.sortedBy { it.unreadCount }
        }.let { if (prefs.librarySortAscending) it else it.reversed() }

        val genres = allManga.flatMap { it.genres.split(",") }
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val sources = allManga.map { it.sourceName }.filter { it.isNotBlank() }.distinct().sorted()
        val langs = allManga.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()

        LibraryUiState(
            categories = categories,
            selectedCategoryId = local.selectedCategoryId,
            mangaList = sorted.map { m ->
                val ratio = if (m.totalChapters > 0) {
                    ((m.totalChapters - m.unreadCount).coerceAtLeast(0)).toFloat() / m.totalChapters.toFloat()
                } else null
                MangaCardUi(
                    id = m.id,
                    title = m.title,
                    altTitle = m.altTitle,
                    coverUrl = m.coverUrl,
                    latestChapter = m.latestChapterName,
                    sourceName = m.sourceName,
                    progressFraction = ratio,
                    unreadCount = m.unreadCount,
                    status = MangaStatus.fromString(m.status),
                    isBookmarked = m.isBookmarked,
                    isInLibrary = true,
                    isDemoData = m.isDemoData
                )
            },
            sort = prefs.librarySort,
            sortAscending = prefs.librarySortAscending,
            filter = f,
            availableGenres = genres,
            availableSources = sources,
            availableLanguages = langs,
            selectedMangaIds = local.selectedIds,
            isGridLayout = local.isGrid,
            statusMessage = local.statusMsg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun selectCategory(categoryId: Long?) {
        localControls.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun updateSearchQuery(q: String) {
        localControls.update { it.copy(filter = it.filter.copy(searchQuery = q)) }
    }

    fun updateFilter(filter: LibraryFilterState) {
        localControls.update { it.copy(filter = filter) }
    }

    fun setSort(sort: LibrarySort, ascending: Boolean) {
        viewModelScope.launch { prefsRepo.setLibrarySort(sort, ascending) }
    }

    fun toggleLayoutMode() {
        localControls.update { it.copy(isGrid = !it.isGrid) }
    }

    fun toggleSelection(mangaId: String) {
        localControls.update { cur ->
            val updated = cur.selectedIds.toMutableSet()
            if (mangaId in updated) updated.remove(mangaId) else updated.add(mangaId)
            cur.copy(selectedIds = updated)
        }
    }

    fun selectAllVisible() {
        val visibleIds = uiState.value.mangaList.map { it.id }.toSet()
        localControls.update { it.copy(selectedIds = visibleIds) }
    }

    fun clearSelection() {
        localControls.update { it.copy(selectedIds = emptySet()) }
    }

    fun clearStatusMessage() {
        localControls.update { it.copy(statusMsg = null) }
    }

    fun bulkMarkRead(isRead: Boolean) {
        val ids = localControls.value.selectedIds
        viewModelScope.launch {
            mangaRepo.bulkMarkRead(ids, isRead)
            localControls.update { it.copy(selectedIds = emptySet(), statusMsg = "Updated ${ids.size} series.") }
        }
    }

    fun bulkAssignCategories(categoryIds: List<Long>) {
        val ids = localControls.value.selectedIds
        viewModelScope.launch {
            mangaRepo.bulkAssignCategories(ids, categoryIds)
            localControls.update { it.copy(selectedIds = emptySet(), statusMsg = "Categories updated.") }
        }
    }

    fun bulkDownloadSelected() {
        val ids = localControls.value.selectedIds
        viewModelScope.launch {
            var count = 0
            for (id in ids) {
                val manga = mangaRepo.observeManga(id).first() ?: continue
                val chapters = mangaRepo.observeChapters(id).first().filterNot { it.isDownloaded }
                downloadRepo.enqueueChapters(manga, chapters)
                count += chapters.size
            }
            localControls.update {
                it.copy(selectedIds = emptySet(), statusMsg = "Queued $count chapter(s) for download.")
            }
        }
    }

    fun bulkRemove() {
        val ids = localControls.value.selectedIds
        viewModelScope.launch {
            mangaRepo.bulkRemoveFromLibrary(ids)
            localControls.update { it.copy(selectedIds = emptySet(), statusMsg = "Removed ${ids.size} series from library.") }
        }
    }

    fun createCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { mangaRepo.createCategory(name) }
    }

    fun renameCategory(cat: CategoryEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { mangaRepo.renameCategory(cat, newName) }
    }

    fun moveCategory(fromIdx: Int, toIdx: Int) {
        val list = uiState.value.categories.toMutableList()
        if (fromIdx in list.indices && toIdx in list.indices) {
            val moved = list.removeAt(fromIdx)
            list.add(toIdx, moved)
            viewModelScope.launch { mangaRepo.reorderCategories(list) }
        }
    }

    fun deleteCategory(catId: Long) {
        viewModelScope.launch {
            mangaRepo.deleteCategory(catId)
            if (localControls.value.selectedCategoryId == catId) {
                localControls.update { it.copy(selectedCategoryId = null) }
            }
        }
    }

    companion object {
        fun factory(
            mangaRepo: MangaRepository,
            downloadRepo: DownloadRepository,
            prefsRepo: AppPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(mangaRepo, downloadRepo, prefsRepo) as T
        }
    }
}
