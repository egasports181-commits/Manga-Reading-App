package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.KomaApplication
import com.example.R
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ChapterDownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return@withContext Result.failure()
        val app = appContext.applicationContext as KomaApplication
        val container = app.container
        val downloadDao = container.database.downloadDao()
        val chapterDao = container.database.chapterDao()
        val mangaDao = container.database.mangaDao()
        val prefs = container.preferences.preferences.first()

        val task = downloadDao.getTask(chapterId) ?: return@withContext Result.failure()
        val chapter = chapterDao.getChapterById(chapterId) ?: return@withContext Result.failure()
        val manga = mangaDao.getMangaById(task.mangaId) ?: return@withContext Result.failure()
        val source = container.sourceRegistry.getSourceById(manga.sourceId)
            ?: return@withContext Result.failure()

        setForegroundSafely(task.mangaTitle, task.chapterName, 0, 1)

        try {
            downloadDao.updateProgress(chapterId, "DOWNLOADING", 0f, 0, 0, null, 0L)
            val pages = source.getChapterPages(chapter.chapterKey)
            if (pages.isEmpty()) {
                downloadDao.updateProgress(chapterId, "FAILED", 0f, 0, 0, "No pages returned by source", 0L)
                return@withContext Result.failure()
            }

            val safeFolder = "${manga.sourceId}_${manga.mangaKey}_${chapter.chapterKey}"
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetDir = File(appContext.filesDir, "downloads/$safeFolder").apply { mkdirs() }

            var totalBytes = 0L
            pages.forEachIndexed { idx, page ->
                if (isStopped) {
                    downloadDao.updateProgress(
                        chapterId,
                        "PAUSED",
                        idx.toFloat() / pages.size,
                        idx,
                        pages.size,
                        null,
                        totalBytes
                    )
                    return@withContext Result.retry()
                }

                val outFile = File(targetDir, "page_${"%04d".format(idx)}.jpg")
                if (!outFile.exists() || outFile.length() == 0L) {
                    val tmpFile = File(targetDir, "page_${"%04d".format(idx)}.jpg.tmp")
                    if (tmpFile.exists()) tmpFile.delete()
                    try {
                        if (page.imageUrl.startsWith("http")) {
                            val req = Request.Builder()
                                .url(page.imageUrl)
                                .apply {
                                    if (page.headers.isNotEmpty()) {
                                        headers(page.headers.toHeaders())
                                    }
                                }
                                .get()
                                .build()
                            container.httpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                                resp.body?.byteStream()?.use { input ->
                                    FileOutputStream(tmpFile).use { output ->
                                        input.copyTo(output)
                                    }
                                } ?: throw IOException("Empty image body")
                            }
                        } else {
                            // Local / Demo URI rendered through Coil ImageLoader
                            val request = ImageRequest.Builder(appContext)
                                .data(Uri.parse(page.imageUrl))
                                .allowHardware(false)
                                .build()
                            val result = container.imageLoader.execute(request)
                            if (result is SuccessResult) {
                                val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                                    ?: Bitmap.createBitmap(
                                        result.drawable.intrinsicWidth.coerceAtLeast(1),
                                        result.drawable.intrinsicHeight.coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888
                                    ).also { b ->
                                        val c = Canvas(b)
                                        result.drawable.setBounds(0, 0, c.width, c.height)
                                        result.drawable.draw(c)
                                    }
                                FileOutputStream(tmpFile).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                            } else {
                                throw IOException("Unable to decode local page image")
                            }
                        }
                        if (tmpFile.exists() && tmpFile.length() > 0L) {
                            if (outFile.exists()) outFile.delete()
                            if (!tmpFile.renameTo(outFile)) {
                                tmpFile.copyTo(outFile, overwrite = true)
                                tmpFile.delete()
                            }
                        } else {
                            throw IOException("Downloaded page was empty")
                        }
                    } catch (e: Exception) {
                        tmpFile.delete()
                        throw e
                    }
                }
                totalBytes += outFile.length()
                val progress = (idx + 1).toFloat() / pages.size
                downloadDao.updateProgress(
                    chapterId = chapterId,
                    status = "DOWNLOADING",
                    progress = progress,
                    downloadedPages = idx + 1,
                    totalPages = pages.size,
                    error = null,
                    bytes = totalBytes
                )
                setForegroundSafely(task.mangaTitle, task.chapterName, idx + 1, pages.size)
            }

            chapterDao.updateDownloadStatus(
                chapterId = chapterId,
                downloaded = true,
                path = targetDir.absolutePath,
                at = System.currentTimeMillis(),
                bytes = totalBytes
            )
            downloadDao.updateProgress(
                chapterId = chapterId,
                status = "COMPLETED",
                progress = 1f,
                downloadedPages = pages.size,
                totalPages = pages.size,
                error = null,
                bytes = totalBytes
            )
            Result.success()
        } catch (e: Exception) {
            val friendly = NetworkErrorFormatter.describe(e)
            val maxRetries = prefs.downloadMaxRetries.coerceIn(0, 5)
            val canRetry = runAttemptCount < maxRetries && e is IOException
            if (canRetry) {
                downloadDao.upsertTask(
                    task.copy(
                        status = "QUEUED",
                        retryCount = runAttemptCount + 1,
                        errorMessage = "Retrying (${runAttemptCount + 1}/$maxRetries): $friendly"
                    )
                )
                Result.retry()
            } else {
                downloadDao.updateProgress(
                    chapterId = chapterId,
                    status = "FAILED",
                    progress = 0f,
                    downloadedPages = 0,
                    totalPages = 0,
                    error = friendly,
                    bytes = 0L
                )
                Result.failure()
            }
        }
    }

    private suspend fun setForegroundSafely(title: String, chapter: String, done: Int, total: Int) {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Chapter Downloads", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Downloading $title")
                .setContentText("$chapter ($done/$total)")
                .setProgress(total.coerceAtLeast(1), done, total <= 1)
                .setOngoing(true)
                .build()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(chapterNotificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(chapterNotificationId(), notification)
            }
            setForeground(info)
        }
    }

    private fun chapterNotificationId(): Int = id.hashCode()

    companion object {
        const val KEY_CHAPTER_ID = "chapter_id"
        const val CHANNEL_ID = "koma_downloads"
    }
}

class LibraryUpdateWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = appContext.applicationContext as KomaApplication
        return try {
            val newChapters = app.container.mangaRepository.checkAllLibraryUpdates()
            if (newChapters > 0) {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel("koma_updates", "Library Updates", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val notification = NotificationCompat.Builder(appContext, "koma_updates")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("New Chapters Available")
                    .setContentText("Found $newChapters new chapter(s) in your library.")
                    .setAutoCancel(true)
                    .build()
                nm.notify(2001, notification)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
