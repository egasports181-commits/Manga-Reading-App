package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY lastReadAt DESC, dateAdded DESC")
    fun observeLibraryManga(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE id = :mangaId")
    fun observeMangaById(mangaId: String): Flow<MangaEntity?>

    @Query("SELECT * FROM manga WHERE id = :mangaId")
    suspend fun getMangaById(mangaId: String): MangaEntity?

    @Query("SELECT * FROM manga WHERE inLibrary = 1")
    suspend fun getLibraryMangaOnce(): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT 20")
    fun observeContinueReading(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY dateAdded DESC LIMIT 20")
    fun observeRecentlyAdded(): Flow<List<MangaEntity>>

    @Query("SELECT * FROM manga WHERE inLibrary = 1 ORDER BY latestUpdateAt DESC LIMIT 20")
    fun observeRecentlyUpdated(): Flow<List<MangaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManga(manga: MangaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMangaIfAbsent(manga: MangaEntity)

    @Query("UPDATE manga SET inLibrary = :inLibrary, dateAdded = :dateAdded WHERE id = :mangaId")
    suspend fun setInLibrary(mangaId: String, inLibrary: Boolean, dateAdded: Long)

    @Query("UPDATE manga SET isBookmarked = :bookmarked WHERE id = :mangaId")
    suspend fun setBookmarked(mangaId: String, bookmarked: Boolean)

    @Query("UPDATE manga SET lastReadAt = :timestamp, totalReadingTimeMs = totalReadingTimeMs + :addedTimeMs WHERE id = :mangaId")
    suspend fun recordReadProgress(mangaId: String, timestamp: Long, addedTimeMs: Long)

    @Query("UPDATE manga SET unreadCount = :unreadCount, totalChapters = :totalChapters, latestChapterNumber = :latestNum, latestChapterName = :latestName, latestUpdateAt = :latestAt WHERE id = :mangaId")
    suspend fun updateChapterStats(
        mangaId: String,
        unreadCount: Int,
        totalChapters: Int,
        latestNum: Double,
        latestName: String,
        latestAt: Long
    )

    @Query("DELETE FROM manga WHERE isDemoData = 1")
    suspend fun deleteDemoManga()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getCategoriesOnce(): List<CategoryEntity>

    @Query("SELECT * FROM manga_category_cross_ref")
    fun observeAllCrossRefs(): Flow<List<MangaCategoryCrossRef>>

    @Query("SELECT categoryId FROM manga_category_cross_ref WHERE mangaId = :mangaId")
    fun observeCategoryIdsForManga(mangaId: String): Flow<List<Long>>

    @Query("SELECT categoryId FROM manga_category_cross_ref WHERE mangaId = :mangaId")
    suspend fun getCategoryIdsForMangaOnce(mangaId: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :categoryId AND isDefault = 0")
    suspend fun deleteCategory(categoryId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignMangaToCategory(crossRef: MangaCategoryCrossRef)

    @Query("DELETE FROM manga_category_cross_ref WHERE mangaId = :mangaId")
    suspend fun clearMangaCategories(mangaId: String)

    @Query("DELETE FROM manga_category_cross_ref WHERE mangaId = :mangaId AND categoryId = :categoryId")
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY chapterNumber DESC, uploadedAt DESC")
    fun observeChaptersForManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY chapterNumber ASC")
    suspend fun getChaptersForMangaOnce(mangaId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getChapterById(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE isDownloaded = 1 ORDER BY downloadedAt DESC")
    fun observeDownloadedChapters(): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapters WHERE isRead = 1")
    fun observeTotalChaptersRead(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chapters WHERE isDownloaded = 1")
    fun observeDownloadedChapterCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM chapters WHERE isDownloaded = 1")
    fun observeDownloadedBytes(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChaptersIfAbsent(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: ChapterEntity)

    @Query("UPDATE chapters SET isRead = :isRead WHERE id = :chapterId")
    suspend fun setChapterRead(chapterId: String, isRead: Boolean)

    @Query("UPDATE chapters SET isRead = :isRead WHERE mangaId = :mangaId")
    suspend fun setAllChaptersReadForManga(mangaId: String, isRead: Boolean)

    @Query("UPDATE chapters SET isBookmarked = :isBookmarked WHERE id = :chapterId")
    suspend fun setChapterBookmarked(chapterId: String, isBookmarked: Boolean)

    @Query("UPDATE chapters SET lastPageRead = :page, totalPages = :totalPages, isRead = :isRead WHERE id = :chapterId")
    suspend fun updateReadingPosition(chapterId: String, page: Int, totalPages: Int, isRead: Boolean)

    @Query("UPDATE chapters SET isDownloaded = :downloaded, localDirPath = :path, downloadedAt = :at, sizeBytes = :bytes WHERE id = :chapterId")
    suspend fun updateDownloadStatus(chapterId: String, downloaded: Boolean, path: String, at: Long, bytes: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM reading_history ORDER BY readAt DESC LIMIT 50")
    fun observeHistory(): Flow<List<ReadingHistoryEntity>>

    @Query("SELECT * FROM reading_history ORDER BY readAt DESC")
    suspend fun getAllHistoryOnce(): List<ReadingHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history")
    suspend fun clearHistory()
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extension_repos ORDER BY id ASC")
    fun observeRepos(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM extension_repos WHERE id = :repoId")
    suspend fun getRepoById(repoId: Long): ExtensionRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRepo(repo: ExtensionRepoEntity): Long

    @Query("DELETE FROM extension_repos WHERE id = :repoId")
    suspend fun deleteRepo(repoId: Long)

    @Query("DELETE FROM extension_packages WHERE repoId = :repoId AND isInstalled = 0")
    suspend fun deleteUninstalledFromRepo(repoId: Long)

    @Query("SELECT * FROM extension_packages ORDER BY popularity DESC, name ASC")
    fun observePackages(): Flow<List<ExtensionPackageEntity>>

    @Query("SELECT * FROM extension_packages WHERE isInstalled = 1 AND isEnabled = 1 AND format = 'koma_json_v1'")
    fun observeActiveKomaPackages(): Flow<List<ExtensionPackageEntity>>

    @Query("SELECT * FROM extension_packages WHERE pkgId = :pkgId")
    suspend fun getPackage(pkgId: String): ExtensionPackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackages(packages: List<ExtensionPackageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackage(pkg: ExtensionPackageEntity)

    @Query("UPDATE extension_packages SET isEnabled = :enabled WHERE pkgId = :pkgId")
    suspend fun setPackageEnabled(pkgId: String, enabled: Boolean)

    @Query("UPDATE extension_packages SET isInstalled = 0, hasUpdate = 0 WHERE pkgId = :pkgId")
    suspend fun uninstallPackage(pkgId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt ASC")
    fun observeDownloadQueue(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE chapterId = :chapterId")
    suspend fun getTask(chapterId: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, progress = :progress, downloadedPages = :downloadedPages, totalPages = :totalPages, errorMessage = :error, bytesDownloaded = :bytes WHERE chapterId = :chapterId")
    suspend fun updateProgress(
        chapterId: String,
        status: String,
        progress: Float,
        downloadedPages: Int,
        totalPages: Int,
        error: String?,
        bytes: Long
    )

    @Query("DELETE FROM download_tasks WHERE chapterId = :chapterId")
    suspend fun deleteTask(chapterId: String)

    @Query("DELETE FROM download_tasks WHERE status IN ('COMPLETED', 'CANCELLED')")
    suspend fun clearCompleted()
}

@Dao
interface UpdateDao {
    @Query("SELECT * FROM update_entries ORDER BY detectedAt DESC LIMIT 200")
    fun observeUpdates(): Flow<List<UpdateEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpdates(entries: List<UpdateEntryEntity>)

    @Query("UPDATE update_entries SET isSeen = 1 WHERE id = :id")
    suspend fun markSeen(id: Long)

    @Query("UPDATE update_entries SET isSeen = 1")
    suspend fun markAllSeen()

    @Query("DELETE FROM update_entries")
    suspend fun clearUpdates()
}

@Dao
interface TrackingDao {
    @Query("SELECT * FROM tracking_entries WHERE mangaId = :mangaId")
    fun observeTrackingForManga(mangaId: String): Flow<List<TrackingEntryEntity>>

    @Query("SELECT * FROM tracking_entries")
    suspend fun getAllTrackingOnce(): List<TrackingEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracking(entry: TrackingEntryEntity)

    @Query("DELETE FROM tracking_entries WHERE id = :id")
    suspend fun deleteTracking(id: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY isSaved DESC, createdAt DESC LIMIT 30")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(entry: SearchHistoryEntity)

    @Query("UPDATE search_history SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteSearch(id: Long)

    @Query("DELETE FROM search_history WHERE isSaved = 0")
    suspend fun clearUnsavedHistory()
}

@Dao
interface SourcePreferenceDao {
    @Query("SELECT * FROM source_preferences WHERE sourceId = :sourceId")
    suspend fun getForSource(sourceId: String): List<SourcePreferenceEntity>

    @Query("SELECT * FROM source_preferences")
    suspend fun getAllOnce(): List<SourcePreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPreference(pref: SourcePreferenceEntity)
}
