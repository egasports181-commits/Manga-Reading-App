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
import com.example.extension.ExtensionSessionManager
import com.example.network.KomaDns
import com.example.network.KomaNetworkEventListener
import com.example.network.KomaNetworkInterceptor
import com.example.network.NetworkDiagnosticsTracker
import com.example.source.SourceRegistry
import com.example.util.CbzEntryFetcher
import com.example.util.DemoPageFetcher
import com.example.worker.LibraryUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: KomaDatabase = KomaDatabase.build(appContext)
    val preferences: AppPreferencesRepository = AppPreferencesRepository(appContext)

    val networkDiagnostics = NetworkDiagnosticsTracker(appContext)
    val komaDns = KomaDns(networkDiagnostics)
    val sessionManager = ExtensionSessionManager(database.extensionDao())
    val networkEventListener = KomaNetworkEventListener(networkDiagnostics, komaDns)
    val networkInterceptor = KomaNetworkInterceptor(networkDiagnostics, komaDns, networkEventListener)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(komaDns)
        .cookieJar(sessionManager.cookieJar)
        .eventListenerFactory { networkEventListener }
        .addInterceptor(networkInterceptor)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .cache(Cache(File(appContext.cacheDir, "http_cache"), 50L * 1024L * 1024L))
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
        httpClient = httpClient,
        context = appContext
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

    init {
        appScope.launch {
            sessionManager.preloadCookies()
            extensionRepository.seedDefaultRepositoriesIfMissing(forceRestore = false)
        }
        appScope.launch {
            preferences.preferences.collect { prefs ->
                komaDns.mode = prefs.dnsMode
                komaDns.preferIpv4 = prefs.preferIpv4
            }
        }
    }
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
