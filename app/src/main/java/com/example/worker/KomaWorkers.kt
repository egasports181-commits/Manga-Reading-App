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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

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
                if (!outFile.exists()) {
                    if (page.imageUrl.startsWith("http")) {
                        val req = Request.Builder().url(page.imageUrl).get().build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                            resp.body?.byteStream()?.use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
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
                            FileOutputStream(outFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }
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
            downloadDao.updateProgress(
                chapterId = chapterId,
                status = "FAILED",
                progress = 0f,
                downloadedPages = 0,
                totalPages = 0,
                error = e.message ?: "Download failed",
                bytes = 0L
            )
            Result.failure()
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
