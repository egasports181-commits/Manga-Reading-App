package com.example.data.repository

import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ReadingStatsSummary
import com.example.domain.model.SourceChapter
import com.example.domain.model.SourceMangaItem
import com.example.source.SourceRegistry
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

class MangaRepository(
    private val mangaDao: MangaDao,
    private val categoryDao: CategoryDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val updateDao: UpdateDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val sourceRegistry: SourceRegistry,
    private val prefsRepo: AppPreferencesRepository
) {
    val libraryManga: Flow<List<MangaEntity>> = mangaDao.observeLibraryManga()
    val continueReading: Flow<List<MangaEntity>> = mangaDao.observeContinueReading()
    val recentlyAdded: Flow<List<MangaEntity>> = mangaDao.observeRecentlyAdded()
    val recentlyUpdated: Flow<List<MangaEntity>> = mangaDao.observeRecentlyUpdated()
    val categories: Flow<List<CategoryEntity>> = categoryDao.observeCategories()
    val categoryCrossRefs: Flow<List<MangaCategoryCrossRef>> = categoryDao.observeAllCrossRefs()
    val history: Flow<List<ReadingHistoryEntity>> = historyDao.observeHistory()
    val updates: Flow<List<UpdateEntryEntity>> = updateDao.observeUpdates()
    val downloadedChapters: Flow<List<ChapterEntity>> = chapterDao.observeDownloadedChapters()
    val searchHistory: Flow<List<SearchHistoryEntity>> = searchHistoryDao.observeSearchHistory()

    fun observeManga(mangaId: String): Flow<MangaEntity?> = mangaDao.observeMangaById(mangaId)
    fun observeChapters(mangaId: String): Flow<List<ChapterEntity>> = chapterDao.observeChaptersForManga(mangaId)
    fun observeMangaCategoryIds(mangaId: String): Flow<List<Long>> = categoryDao.observeCategoryIdsForManga(mangaId)

    fun observeStats(): Flow<ReadingStatsSummary> = combine(
        mangaDao.observeLibraryManga(),
        chapterDao.observeTotalChaptersRead(),
        chapterDao.observeDownloadedChapterCount(),
        chapterDao.observeDownloadedBytes(),
        historyDao.observeHistory()
    ) { library, chaptersRead, dlCount, dlBytes, historyList ->
        val totalTime = library.sumOf { it.totalReadingTimeMs }
        val genreCounts = mutableMapOf<String, Int>()
        library.forEach { manga ->
            manga.genres.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { g ->
                genreCounts[g] = (genreCounts[g] ?: 0) + 1
            }
        }
        val favGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key to it.value }

        val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())
        val monthMap = linkedMapOf<String, Int>()
        historyList.sortedBy { it.readAt }.forEach { h ->
            val label = monthFormat.format(Date(h.readAt))
            monthMap[label] = (monthMap[label] ?: 0) + 1
        }

        val streak = calculateStreak(historyList.map { it.readAt })
        val mostRead = library
            .filter { it.totalReadingTimeMs > 0 }
            .sortedByDescending { it.totalReadingTimeMs }
            .take(5)
            .map { it.title to it.totalReadingTimeMs }

        ReadingStatsSummary(
            totalLibraryManga = library.size,
            totalChaptersRead = chaptersRead,
            totalReadingTimeMs = totalTime,
            downloadedChaptersCount = dlCount,
            downloadedBytes = dlBytes,
            dailyStreakDays = streak,
            favoriteGenres = favGenres,
            monthlyActivity = monthMap.entries.map { it.key to it.value },
            mostReadManga = mostRead
        )
    }

    private fun calculateStreak(timestamps: List<Long>): Int {
        if (timestamps.isEmpty()) return 0
        val cal = Calendar.getInstance()
        val days = timestamps.map { ts ->
            cal.timeInMillis = ts
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }.toSet()

        cal.timeInMillis = System.currentTimeMillis()
        var streak = 0
        while (true) {
            val dayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (dayKey in days) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else if (streak == 0) {
                // Allow yesterday if user hasn't read yet today
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val yestKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
                if (yestKey in days) {
                    streak++
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                } else break
            } else break
        }
        return streak
    }

    suspend fun cacheSourceManga(item: SourceMangaItem): MangaEntity {
        val mangaId = "${item.sourceId}::${item.mangaKey}"
        val existing = mangaDao.getMangaById(mangaId)
        val entity = existing?.copy(
            title = item.title,
            altTitle = item.altTitle.ifBlank { existing.altTitle },
            author = item.author.ifBlank { existing.author },
            artist = item.artist.ifBlank { existing.artist },
            description = item.description.ifBlank { existing.description },
            genres = if (item.genres.isNotEmpty()) item.genres.joinToString(", ") else existing.genres,
            status = item.status.name,
            coverUrl = item.coverUrl.ifBlank { existing.coverUrl },
            language = item.language,
            contentRating = item.contentRating,
            releaseYear = item.releaseYear ?: existing.releaseYear,
            latestChapterName = item.latestChapterName.ifBlank { existing.latestChapterName },
            latestChapterNumber = maxOf(item.latestChapterNumber, existing.latestChapterNumber)
        ) ?: MangaEntity(
            id = mangaId,
            sourceId = item.sourceId,
            sourceName = item.sourceName,
            mangaKey = item.mangaKey,
            title = item.title,
            altTitle = item.altTitle,
            author = item.author,
            artist = item.artist,
            description = item.description,
            genres = item.genres.joinToString(", "),
            status = item.status.name,
            coverUrl = item.coverUrl,
            language = item.language,
            contentRating = item.contentRating,
            releaseYear = item.releaseYear,
            latestChapterNumber = item.latestChapterNumber,
            latestChapterName = item.latestChapterName,
            latestUpdateAt = System.currentTimeMillis(),
            isDemoData = item.isDemoData
        )
        mangaDao.upsertManga(entity)
        return entity
    }

    suspend fun refreshMangaAndChapters(mangaId: String): Result<Unit> {
        return try {
            val existing = mangaDao.getMangaById(mangaId)
                ?: return Result.failure(IllegalStateException("Manga not found"))
            val adapter = sourceRegistry.getSourceById(existing.sourceId)
                ?: return Result.failure(IllegalStateException("Source '${existing.sourceName}' is not active"))

            val detail = adapter.getMangaDetails(existing.mangaKey)
            val chapters = adapter.getChapters(existing.mangaKey)
            cacheSourceManga(detail)
            syncChapters(mangaId, chapters, recordUpdates = existing.inLibrary)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncChapters(
        mangaId: String,
        remoteChapters: List<SourceChapter>,
        recordUpdates: Boolean
    ) {
        val currentMap = chapterDao.getChaptersForMangaOnce(mangaId).associateBy { it.id }
        val newEntities = mutableListOf<ChapterEntity>()
        val newUpdates = mutableListOf<UpdateEntryEntity>()
        val manga = mangaDao.getMangaById(mangaId)

        remoteChapters.forEach { rc ->
            val chId = "$mangaId::${rc.chapterKey}"
            if (chId !in currentMap) {
                val entity = ChapterEntity(
                    id = chId,
                    mangaId = mangaId,
                    chapterKey = rc.chapterKey,
                    name = rc.name,
                    chapterNumber = rc.chapterNumber,
                    volumeNumber = rc.volumeNumber,
                    scanlator = rc.scanlator,
                    uploadedAt = rc.uploadedAt
                )
                newEntities.add(entity)
                if (recordUpdates && currentMap.isNotEmpty() && manga != null) {
                    newUpdates.add(
                        UpdateEntryEntity(
                            mangaId = mangaId,
                            chapterId = chId,
                            chapterName = rc.name,
                            sourceId = manga.sourceId,
                            sourceName = manga.sourceName,
                            mangaTitle = manga.title,
                            coverUrl = manga.coverUrl,
                            detectedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        if (newEntities.isNotEmpty()) {
            chapterDao.insertChaptersIfAbsent(newEntities)
        }
        if (newUpdates.isNotEmpty()) {
            updateDao.insertUpdates(newUpdates)
        }

        val allChapters = chapterDao.getChaptersForMangaOnce(mangaId)
        val unread = allChapters.count { !it.isRead }
        val latest = allChapters.maxByOrNull { it.chapterNumber }
        mangaDao.updateChapterStats(
            mangaId = mangaId,
            unreadCount = unread,
            totalChapters = allChapters.size,
            latestNum = latest?.chapterNumber ?: 0.0,
            latestName = latest?.name ?: "",
            latestAt = latest?.uploadedAt ?: System.currentTimeMillis()
        )
    }

    suspend fun toggleLibrary(mangaId: String, categoryIds: List<Long> = emptyList()) {
        val manga = mangaDao.getMangaById(mangaId) ?: return
        val newInLib = !manga.inLibrary
        mangaDao.setInLibrary(mangaId, newInLib, if (newInLib) System.currentTimeMillis() else 0L)
        if (newInLib) {
            val cats = categoryIds.ifEmpty {
                categoryDao.getCategoriesOnce().firstOrNull { it.isDefault }?.let { listOf(it.id) } ?: emptyList()
            }
            cats.forEach { catId ->
                categoryDao.assignMangaToCategory(MangaCategoryCrossRef(mangaId, catId))
            }
        } else {
            categoryDao.clearMangaCategories(mangaId)
        }
    }

    suspend fun setMangaCategories(mangaId: String, categoryIds: List<Long>) {
        categoryDao.clearMangaCategories(mangaId)
        categoryIds.forEach { catId ->
            categoryDao.assignMangaToCategory(MangaCategoryCrossRef(mangaId, catId))
        }
    }

    suspend fun bulkAssignCategories(mangaIds: Set<String>, categoryIds: List<Long>) {
        mangaIds.forEach { id -> setMangaCategories(id, categoryIds) }
    }

    suspend fun bulkRemoveFromLibrary(mangaIds: Set<String>) {
        mangaIds.forEach { id ->
            mangaDao.setInLibrary(id, false, 0L)
            categoryDao.clearMangaCategories(id)
        }
    }

    suspend fun bulkMarkRead(mangaIds: Set<String>, isRead: Boolean) {
        mangaIds.forEach { id ->
            chapterDao.setAllChaptersReadForManga(id, isRead)
            recalcChapterStats(id)
        }
    }

    suspend fun toggleMangaBookmark(mangaId: String) {
        val manga = mangaDao.getMangaById(mangaId) ?: return
        mangaDao.setBookmarked(mangaId, !manga.isBookmarked)
    }

    suspend fun setChapterRead(chapterId: String, mangaId: String, isRead: Boolean) {
        chapterDao.setChapterRead(chapterId, isRead)
        recalcChapterStats(mangaId)
    }

    suspend fun setChapterBookmarked(chapterId: String, bookmarked: Boolean) {
        chapterDao.setChapterBookmarked(chapterId, bookmarked)
    }

    suspend fun saveReaderProgress(
        mangaId: String,
        chapterId: String,
        pageIndex: Int,
        totalPages: Int,
        sessionDurationMs: Long
    ) {
        val prefs = prefsRepo.preferences.first()
        if (prefs.incognitoMode) return

        val isFinished = totalPages > 0 && pageIndex >= totalPages - 1
        chapterDao.updateReadingPosition(chapterId, pageIndex, totalPages, isFinished)
        val now = System.currentTimeMillis()
        mangaDao.recordReadProgress(mangaId, now, sessionDurationMs)
        recalcChapterStats(mangaId)

        val manga = mangaDao.getMangaById(mangaId)
        val chapter = chapterDao.getChapterById(chapterId)
        if (manga != null && chapter != null) {
            historyDao.insertHistory(
                ReadingHistoryEntity(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    mangaTitle = manga.title,
                    chapterName = chapter.name,
                    coverUrl = manga.coverUrl,
                    readAt = now,
                    durationMs = sessionDurationMs,
                    pagesRead = pageIndex + 1
                )
            )
        }
    }

    private suspend fun recalcChapterStats(mangaId: String) {
        val allChapters = chapterDao.getChaptersForMangaOnce(mangaId)
        val unread = allChapters.count { !it.isRead }
        val latest = allChapters.maxByOrNull { it.chapterNumber }
        mangaDao.updateChapterStats(
            mangaId = mangaId,
            unreadCount = unread,
            totalChapters = allChapters.size,
            latestNum = latest?.chapterNumber ?: 0.0,
            latestName = latest?.name ?: "",
            latestAt = latest?.uploadedAt ?: System.currentTimeMillis()
        )
    }

    suspend fun createCategory(name: String) {
        val current = categoryDao.getCategoriesOnce()
        val nextOrder = (current.maxOfOrNull { it.sortOrder } ?: 0) + 1
        categoryDao.insertCategory(CategoryEntity(name = name.trim(), sortOrder = nextOrder))
    }

    suspend fun renameCategory(category: CategoryEntity, newName: String) {
        categoryDao.updateCategory(category.copy(name = newName.trim()))
    }

    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        ordered.forEachIndexed { idx, cat ->
            categoryDao.updateCategory(cat.copy(sortOrder = idx))
        }
    }

    suspend fun deleteCategory(categoryId: Long) = categoryDao.deleteCategory(categoryId)

    suspend fun checkAllLibraryUpdates(): Int {
        val lib = mangaDao.getLibraryMangaOnce()
        var newChaptersCount = 0
        for (manga in lib) {
            val beforeCount = chapterDao.getChaptersForMangaOnce(manga.id).size
            refreshMangaAndChapters(manga.id)
            val afterCount = chapterDao.getChaptersForMangaOnce(manga.id).size
            if (afterCount > beforeCount) {
                newChaptersCount += (afterCount - beforeCount)
            }
        }
        return newChaptersCount
    }

    suspend fun markUpdateSeen(id: Long) = updateDao.markSeen(id)
    suspend fun markAllUpdatesSeen() = updateDao.markAllSeen()

    suspend fun recordSearch(query: String, sourceFilter: String = "all") {
        if (query.isBlank()) return
        searchHistoryDao.insertSearch(SearchHistoryEntity(query = query.trim(), sourceFilter = sourceFilter))
    }

    suspend fun toggleSaveSearch(entry: SearchHistoryEntity) =
        searchHistoryDao.setSaved(entry.id, !entry.isSaved)

    suspend fun deleteSearch(id: Long) = searchHistoryDao.deleteSearch(id)
    suspend fun clearSearchHistory() = searchHistoryDao.clearUnsavedHistory()
    suspend fun clearReadingHistory() = historyDao.clearHistory()
    suspend fun removeDemoData() = mangaDao.deleteDemoManga()
}
