package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.*
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupRepository(
    private val context: Context,
    private val mangaDao: MangaDao,
    private val categoryDao: CategoryDao,
    private val chapterDao: ChapterDao,
    private val trackingDao: TrackingDao,
    private val sourcePrefDao: SourcePreferenceDao,
    private val prefsRepo: AppPreferencesRepository
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportBackupToUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val backup = buildBackupPayload()
            val encoded = json.encodeToString(backup)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open destination file"))
            Result.success(backup.manga.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importBackupFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read backup file"))

            val backup = json.decodeFromString<KomaBackupV1>(raw)
            val existingCategories = categoryDao.getCategoriesOnce().associateBy { it.name }.toMutableMap()
            backup.categories.forEach { cat ->
                if (cat.name !in existingCategories) {
                    val id = categoryDao.insertCategory(
                        CategoryEntity(name = cat.name, sortOrder = cat.sortOrder, isDefault = cat.isDefault)
                    )
                    existingCategories[cat.name] = CategoryEntity(id, cat.name, cat.sortOrder, cat.isDefault)
                }
            }

            backup.manga.forEach { bm ->
                val unread = bm.chapters.count { !it.isRead }
                val latest = bm.chapters.maxByOrNull { it.chapterNumber }
                mangaDao.upsertManga(
                    MangaEntity(
                        id = bm.id,
                        sourceId = bm.sourceId,
                        sourceName = bm.sourceId,
                        mangaKey = bm.mangaKey,
                        title = bm.title,
                        altTitle = bm.altTitle,
                        author = bm.author,
                        artist = bm.artist,
                        description = bm.description,
                        genres = bm.genres,
                        status = bm.status,
                        coverUrl = bm.coverUrl,
                        language = bm.language,
                        contentRating = bm.contentRating,
                        releaseYear = bm.releaseYear,
                        inLibrary = true,
                        dateAdded = bm.dateAdded,
                        lastReadAt = bm.lastReadAt,
                        isBookmarked = bm.isBookmarked,
                        totalReadingTimeMs = bm.totalReadingTimeMs,
                        unreadCount = unread,
                        totalChapters = bm.chapters.size,
                        latestChapterNumber = latest?.chapterNumber ?: 0.0,
                        latestChapterName = latest?.name ?: ""
                    )
                )
                bm.categoryNames.forEach { catName ->
                    existingCategories[catName]?.let { cat ->
                        categoryDao.assignMangaToCategory(MangaCategoryCrossRef(bm.id, cat.id))
                    }
                }
                val chEntities = bm.chapters.map { bc ->
                    ChapterEntity(
                        id = "${bm.id}::${bc.chapterKey}",
                        mangaId = bm.id,
                        chapterKey = bc.chapterKey,
                        name = bc.name,
                        chapterNumber = bc.chapterNumber,
                        isRead = bc.isRead,
                        isBookmarked = bc.isBookmarked,
                        lastPageRead = bc.lastPageRead,
                        totalPages = bc.totalPages
                    )
                }
                chapterDao.insertChaptersIfAbsent(chEntities)
            }

            backup.tracking.forEach { bt ->
                trackingDao.upsertTracking(
                    TrackingEntryEntity(
                        mangaId = bt.mangaId,
                        serviceId = bt.serviceId,
                        remoteMediaId = bt.remoteMediaId,
                        remoteTitle = bt.remoteTitle,
                        status = bt.status,
                        score = bt.score,
                        chaptersRead = bt.chaptersRead,
                        totalChapters = bt.totalChapters,
                        syncState = SyncState.LOCAL_AHEAD.name
                    )
                )
            }

            backup.sourcePreferences.forEach { sp ->
                sourcePrefDao.setPreference(SourcePreferenceEntity(sp.sourceId, sp.key, sp.value))
            }

            backup.settings?.let { s ->
                prefsRepo.updateTheme(ThemeMode.fromId(s.themeMode))
                prefsRepo.setAmoledBlack(s.amoledBlack)
                prefsRepo.setAccentPreset(AccentPreset.fromId(s.accentId))
                prefsRepo.setDefaultReadingMode(ReadingMode.fromId(s.defaultReadingMode))
                prefsRepo.setDefaultScaleMode(ImageScaleMode.fromId(s.defaultScaleMode))
                prefsRepo.setWifiOnlyDownloads(s.wifiOnlyDownloads)
                prefsRepo.setConcurrentDownloads(s.concurrentDownloads)
            }

            Result.success(backup.manga.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buildBackupPayload(): KomaBackupV1 {
        val cats = categoryDao.getCategoriesOnce()
        val catById = cats.associateBy { it.id }
        val crossRefs = categoryDao.observeAllCrossRefs().first()
        val library = mangaDao.getLibraryMangaOnce()
        val tracking = trackingDao.getAllTrackingOnce()
        val sourcePrefs = sourcePrefDao.getAllOnce()
        val userPrefs = prefsRepo.preferences.first()

        val backupManga = library.map { m ->
            val mCatNames = crossRefs.filter { it.mangaId == m.id }
                .mapNotNull { catById[it.categoryId]?.name }
            val chapters = chapterDao.getChaptersForMangaOnce(m.id).map { c ->
                BackupChapter(
                    chapterKey = c.chapterKey,
                    name = c.name,
                    chapterNumber = c.chapterNumber,
                    isRead = c.isRead,
                    isBookmarked = c.isBookmarked,
                    lastPageRead = c.lastPageRead,
                    totalPages = c.totalPages
                )
            }
            BackupManga(
                id = m.id,
                sourceId = m.sourceId,
                mangaKey = m.mangaKey,
                title = m.title,
                altTitle = m.altTitle,
                author = m.author,
                artist = m.artist,
                description = m.description,
                genres = m.genres,
                status = m.status,
                coverUrl = m.coverUrl,
                language = m.language,
                contentRating = m.contentRating,
                releaseYear = m.releaseYear,
                dateAdded = m.dateAdded,
                lastReadAt = m.lastReadAt,
                isBookmarked = m.isBookmarked,
                totalReadingTimeMs = m.totalReadingTimeMs,
                categoryNames = mCatNames,
                chapters = chapters
            )
        }

        return KomaBackupV1(
            categories = cats.map { BackupCategory(it.name, it.sortOrder, it.isDefault) },
            manga = backupManga,
            tracking = tracking.map {
                BackupTracking(
                    mangaId = it.mangaId,
                    serviceId = it.serviceId,
                    remoteMediaId = it.remoteMediaId,
                    remoteTitle = it.remoteTitle,
                    status = it.status,
                    score = it.score,
                    chaptersRead = it.chaptersRead,
                    totalChapters = it.totalChapters
                )
            },
            sourcePreferences = sourcePrefs.map { BackupSourcePref(it.sourceId, it.prefKey, it.prefValue) },
            settings = BackupSettings(
                themeMode = userPrefs.themeMode.id,
                amoledBlack = userPrefs.amoledBlack,
                accentId = userPrefs.accentPreset.id,
                defaultReadingMode = userPrefs.defaultReadingMode.id,
                defaultScaleMode = userPrefs.defaultScaleMode.id,
                wifiOnlyDownloads = userPrefs.wifiOnlyDownloads,
                concurrentDownloads = userPrefs.concurrentDownloads
            )
        )
    }
}
