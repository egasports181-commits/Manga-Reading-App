package com.example.ui.screens.more

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.DownloadTaskEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserPreferences
import com.example.data.repository.BackupRepository
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MangaRepository
import com.example.data.repository.TrackingRepository
import com.example.domain.model.*
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MoreUiState(
    val prefs: UserPreferences = UserPreferences(),
    val downloadQueue: List<DownloadTaskEntity> = emptyList(),
    val downloadedChapters: List<ChapterEntity> = emptyList(),
    val stats: ReadingStatsSummary = ReadingStatsSummary(),
    val imageCacheBytes: Long = 0L,
    val statusMessage: String? = null,
    val diagnosticsText: String? = null
)

class MoreViewModel(
    private val context: Context,
    private val prefsRepo: AppPreferencesRepository,
    private val downloadRepo: DownloadRepository,
    private val mangaRepo: MangaRepository,
    private val backupRepo: BackupRepository,
    private val trackingRepo: TrackingRepository,
    private val imageLoader: ImageLoader
) : ViewModel() {

    private val localInfo = MutableStateFlow(LocalInfo())

    private data class LocalInfo(
        val cacheBytes: Long = 0L,
        val statusMsg: String? = null,
        val diagnostics: String? = null
    )

    val uiState: StateFlow<MoreUiState> = combine(
        prefsRepo.preferences,
        downloadRepo.downloadQueue,
        mangaRepo.downloadedChapters,
        mangaRepo.observeStats(),
        localInfo
    ) { prefs, queue, downloaded, stats, info ->
        MoreUiState(
            prefs = prefs,
            downloadQueue = queue,
            downloadedChapters = downloaded,
            stats = stats,
            imageCacheBytes = info.cacheBytes,
            statusMessage = info.statusMsg,
            diagnosticsText = info.diagnostics
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MoreUiState())

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                folderSize(File(context.cacheDir, "image_cache"))
            }
            localInfo.update { it.copy(cacheBytes = size) }
        }
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageLoader.memoryCache?.clear()
                File(context.cacheDir, "image_cache").deleteRecursively()
            }
            refreshCacheSize()
            localInfo.update { it.copy(statusMsg = "Image cache cleared.") }
        }
    }

    fun clearReadingHistory() {
        viewModelScope.launch {
            mangaRepo.clearReadingHistory()
            localInfo.update { it.copy(statusMsg = "Reading history cleared.") }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            mangaRepo.clearSearchHistory()
            localInfo.update { it.copy(statusMsg = "Unsaved search history cleared.") }
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            val s = uiState.value
            val report = buildString {
                appendLine("=== Koma Diagnostics ===")
                appendLine("Theme: ${s.prefs.themeMode.label} (AMOLED=${s.prefs.amoledBlack}, Dynamic=${s.prefs.dynamicColor})")
                appendLine("Demo Mode: ${s.prefs.demoModeEnabled}")
                appendLine("Library Manga: ${s.stats.totalLibraryManga}")
                appendLine("Chapters Read: ${s.stats.totalChaptersRead}")
                appendLine("Downloaded Chapters: ${s.stats.downloadedChaptersCount} (${s.stats.downloadedBytes / 1024} KB)")
                appendLine("Image Cache: ${s.imageCacheBytes / 1024} KB")
                appendLine("Active Download Queue: ${s.downloadQueue.size}")
            }
            localInfo.update { it.copy(diagnostics = report) }
        }
    }

    fun dismissDiagnostics() {
        localInfo.update { it.copy(diagnostics = null) }
    }

    fun clearStatusMessage() {
        localInfo.update { it.copy(statusMsg = null) }
    }

    // Download Controls
    fun pauseDownload(chapterId: String) = viewModelScope.launch { downloadRepo.pauseDownload(chapterId) }
    fun resumeDownload(chapterId: String) = viewModelScope.launch { downloadRepo.resumeOrRetryDownload(chapterId) }
    fun cancelDownload(chapterId: String) = viewModelScope.launch { downloadRepo.cancelDownload(chapterId) }
    fun clearCompletedDownloads() = viewModelScope.launch { downloadRepo.clearCompletedQueue() }
    fun deleteDownloadedChapter(ch: ChapterEntity) = viewModelScope.launch { downloadRepo.deleteDownloadedChapter(ch) }
    fun cleanupReadDownloads() = viewModelScope.launch {
        downloadRepo.cleanupReadDownloads()
        localInfo.update { it.copy(statusMsg = "Cleaned up read chapter downloads.") }
    }

    // Backup & Restore
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val res = backupRepo.exportBackupToUri(uri)
            localInfo.update {
                if (res.isSuccess) it.copy(statusMsg = "Exported backup (${res.getOrNull()} series).")
                else it.copy(statusMsg = "Backup failed: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val res = backupRepo.importBackupFromUri(uri)
            localInfo.update {
                if (res.isSuccess) it.copy(statusMsg = "Restored ${res.getOrNull()} series from backup.")
                else it.copy(statusMsg = "Restore failed: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    // Settings Mutators
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefsRepo.updateTheme(mode) }
    fun setAmoledBlack(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAmoledBlack(enabled) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefsRepo.setDynamicColor(enabled) }
    fun setAccentPreset(preset: AccentPreset) = viewModelScope.launch { prefsRepo.setAccentPreset(preset) }
    fun setAppLanguage(lang: String) = viewModelScope.launch { prefsRepo.setAppLanguage(lang) }
    fun setDemoMode(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setDemoMode(enabled)
        if (!enabled) mangaRepo.removeDemoData()
    }
    fun setDefaultReadingMode(mode: ReadingMode) = viewModelScope.launch { prefsRepo.setDefaultReadingMode(mode) }
    fun setDefaultScaleMode(mode: ImageScaleMode) = viewModelScope.launch { prefsRepo.setDefaultScaleMode(mode) }
    fun setReaderBg(bg: ReaderBackground) = viewModelScope.launch { prefsRepo.setReaderBackground(bg) }
    fun setPageTransitions(enabled: Boolean) = viewModelScope.launch { prefsRepo.setPageTransitions(enabled) }
    fun setTapZones(enabled: Boolean) = viewModelScope.launch { prefsRepo.setTapZones(enabled) }
    fun setVolumeNav(enabled: Boolean) = viewModelScope.launch { prefsRepo.setVolumeNavigation(enabled) }
    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch { prefsRepo.setKeepScreenAwake(enabled) }
    fun setAutoUpdateHours(hours: Int) = viewModelScope.launch { prefsRepo.setAutoUpdateHours(hours) }
    fun setUpdateNotifs(enabled: Boolean) = viewModelScope.launch { prefsRepo.setUpdateNotifications(enabled) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { prefsRepo.setWifiOnlyDownloads(enabled) }
    fun setConcurrentDownloads(count: Int) = viewModelScope.launch { prefsRepo.setConcurrentDownloads(count) }
    fun setStorageLimitMb(mb: Int) = viewModelScope.launch { prefsRepo.setStorageLimitMb(mb) }
    fun setAutoDownloadNew(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAutoDownloadNew(enabled) }
    fun setAutoCleanupRead(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAutoCleanupRead(enabled) }
    fun setSafDownloadUri(uri: String) = viewModelScope.launch { prefsRepo.setSafDownloadUri(uri) }
    fun setAutoUpdateExtensions(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAutoUpdateExtensions(enabled) }
    fun setIncognitoMode(enabled: Boolean) = viewModelScope.launch { prefsRepo.setIncognitoMode(enabled) }
    fun setAutoBackupEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAutoBackupEnabled(enabled) }
    fun setTrackerToken(service: TrackerService, token: String) = viewModelScope.launch {
        prefsRepo.setTrackerToken(service, token.trim())
        localInfo.update { it.copy(statusMsg = "${service.displayName} token updated.") }
    }

    companion object {
        fun factory(
            context: Context,
            prefsRepo: AppPreferencesRepository,
            downloadRepo: DownloadRepository,
            mangaRepo: MangaRepository,
            backupRepo: BackupRepository,
            trackingRepo: TrackingRepository,
            imageLoader: ImageLoader
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MoreViewModel(
                context, prefsRepo, downloadRepo, mangaRepo, backupRepo, trackingRepo, imageLoader
            ) as T
        }
    }
}
