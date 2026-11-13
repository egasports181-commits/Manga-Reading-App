package com.example

import android.app.Application
import android.content.Context
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.db.KomaDatabase
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.*
import com.example.source.SourceRegistry
import com.example.util.CbzEntryFetcher
import com.example.util.DemoPageFetcher
import com.example.worker.LibraryUpdateWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val database: KomaDatabase = KomaDatabase.build(appContext)
    val preferences: AppPreferencesRepository = AppPreferencesRepository(appContext)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .okHttpClient(httpClient)
        .components {
            add(DemoPageFetcher.Factory(appContext))
            add(CbzEntryFetcher.Factory(appContext))
        }
        .memoryCache {
            MemoryCache.Builder(appContext)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("image_cache"))
                .maxSizePercent(0.05)
                .build()
        }
        .crossfade(true)
        .build()

    val sourceRegistry: SourceRegistry = SourceRegistry(
        context = appContext,
        httpClient = httpClient,
        prefsRepo = preferences,
        extensionDao = database.extensionDao(),
        sourcePrefDao = database.sourcePreferenceDao()
    )

    val extensionRepository: ExtensionRepository = ExtensionRepository(
        extensionDao = database.extensionDao(),
        httpClient = httpClient
    )

    val mangaRepository: MangaRepository = MangaRepository(
        mangaDao = database.mangaDao(),
        categoryDao = database.categoryDao(),
        chapterDao = database.chapterDao(),
        historyDao = database.historyDao(),
        updateDao = database.updateDao(),
        searchHistoryDao = database.searchHistoryDao(),
        sourceRegistry = sourceRegistry,
        prefsRepo = preferences
    )

    val downloadRepository: DownloadRepository = DownloadRepository(
        context = appContext,
        downloadDao = database.downloadDao(),
        chapterDao = database.chapterDao(),
        prefsRepo = preferences
    )

    val trackingRepository: TrackingRepository = TrackingRepository(
        trackingDao = database.trackingDao(),
        prefsRepo = preferences,
        httpClient = httpClient
    )

    val backupRepository: BackupRepository = BackupRepository(
        context = appContext,
        mangaDao = database.mangaDao(),
        categoryDao = database.categoryDao(),
        chapterDao = database.chapterDao(),
        trackingDao = database.trackingDao(),
        sourcePrefDao = database.sourcePreferenceDao(),
        prefsRepo = preferences
    )
}

class KomaApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        schedulePeriodicUpdates()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader

    private fun schedulePeriodicUpdates() {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "koma_library_updates",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
