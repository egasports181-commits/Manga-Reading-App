package com.example.data.repository

import android.content.Context
import androidx.work.*
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.DownloadDao
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.DownloadTaskEntity
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.SourcePage
import com.example.worker.ChapterDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

class DownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val chapterDao: ChapterDao,
    private val prefsRepo: AppPreferencesRepository
) {
    val downloadQueue: Flow<List<DownloadTaskEntity>> = downloadDao.observeDownloadQueue()
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueueChapter(manga: MangaEntity, chapter: ChapterEntity) {
        val task = DownloadTaskEntity(
            chapterId = chapter.id,
            mangaId = manga.id,
            mangaTitle = manga.title,
            chapterName = chapter.name,
            sourceId = manga.sourceId,
            chapterKey = chapter.chapterKey,
            status = "QUEUED"
        )
        downloadDao.upsertTask(task)
        scheduleWorker(chapter.id)
    }

    suspend fun enqueueChapters(manga: MangaEntity, chapters: List<ChapterEntity>) {
        chapters.filterNot { it.isDownloaded }.forEach { ch ->
            enqueueChapter(manga, ch)
        }
    }

    suspend fun pauseDownload(chapterId: String) {
        workManager.cancelUniqueWork(workName(chapterId))
        val task = downloadDao.getTask(chapterId) ?: return
        downloadDao.upsertTask(task.copy(status = "PAUSED"))
    }

    suspend fun resumeOrRetryDownload(chapterId: String) {
        val task = downloadDao.getTask(chapterId) ?: return
        downloadDao.upsertTask(task.copy(status = "QUEUED", errorMessage = null))
        scheduleWorker(chapterId)
    }

    suspend fun moveToTop(chapterId: String) {
        val queue = downloadDao.observeDownloadQueue().first()
        val maxPriority = queue.maxOfOrNull { it.priority } ?: 0
        downloadDao.updatePriority(chapterId, maxPriority + 1)
    }

    suspend fun cancelDownload(chapterId: String) {
        workManager.cancelUniqueWork(workName(chapterId))
        downloadDao.deleteTask(chapterId)
    }

    suspend fun deleteDownloadedChapter(chapter: ChapterEntity) {
        if (chapter.localDirPath.isNotBlank()) {
            runCatching { File(chapter.localDirPath).deleteRecursively() }
        }
        chapterDao.updateDownloadStatus(chapter.id, false, "", 0L, 0L)
    }

    suspend fun cleanupReadDownloads() {
        val downloaded = chapterDao.observeDownloadedChapters().first()
        downloaded.filter { it.isRead }.forEach { deleteDownloadedChapter(it) }
    }

    suspend fun clearCompletedQueue() = downloadDao.clearCompleted()

    fun getOfflinePages(chapter: ChapterEntity): List<SourcePage>? {
        if (!chapter.isDownloaded || chapter.localDirPath.isBlank()) return null
        val dir = File(chapter.localDirPath)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles()?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        return files.mapIndexed { idx, file ->
            SourcePage(index = idx, imageUrl = file.toURI().toString(), isLocalFile = true)
        }
    }

    private suspend fun scheduleWorker(chapterId: String) {
        val wifiOnly = prefsRepo.preferences.first().wifiOnlyDownloads
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER_ID to chapterId))
            .build()
        workManager.enqueueUniqueWork(workName(chapterId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(chapterId: String): String = "dl_${chapterId.hashCode()}"
}
