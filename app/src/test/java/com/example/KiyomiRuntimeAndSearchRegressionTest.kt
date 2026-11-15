package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.DownloadRepository
import com.example.data.repository.ExtensionRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.extension.ExtensionInstaller
import com.example.extension.ExtensionTrustManager
import com.example.extension.MihonExtensionLoader
import com.example.extension.MihonRuntimeInitializer
import com.example.source.MangaDexSourceAdapter
import com.example.source.MihonSourceAdapter
import com.example.source.SourceRegistry
import com.example.ui.components.MangaCardUi
import com.example.ui.screens.reader.ReaderViewModel
import com.example.ui.screens.sources.ExtensionStoreTab
import com.example.ui.screens.sources.SourcesScreen
import com.example.ui.screens.sources.SourcesViewModel
import com.example.ui.theme.KomaTheme
import com.example.network.KomaDns
import com.example.network.KomaNetworkInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KiyomiRuntimeAndSearchRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dbInstances = mutableListOf<KomaDatabase>()

    @After
    fun tearDown() {
        dbInstances.forEach { runCatching { it.close() } }
        dbInstances.clear()
    }

    private fun newInMemoryDb(context: Context): KomaDatabase {
        return Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { dbInstances.add(it) }
    }

    @Test
    fun `default httpClient and NetworkHelper register UncaughtExceptionInterceptor and UserAgentInterceptor once in order and preserve on derived builders`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)

        // AppContainer.httpClient must have UncaughtExceptionInterceptor (0), UserAgentInterceptor (1), KomaNetworkInterceptor (2)
        assertTrue(container.httpClient.interceptors.getOrNull(0) is UncaughtExceptionInterceptor)
        assertTrue(container.httpClient.interceptors.getOrNull(1) is UserAgentInterceptor)
        assertTrue(container.httpClient.interceptors.getOrNull(2) is KomaNetworkInterceptor)
        assertEquals(1, container.httpClient.interceptors.count { it is UncaughtExceptionInterceptor })
        assertEquals(1, container.httpClient.interceptors.count { it is UserAgentInterceptor })

        // NetworkHelper initialized with container.httpClient preserves canonical order without duplication
        val helperFromApp = NetworkHelper(context, container.httpClient)
        assertTrue(helperFromApp.client.interceptors.getOrNull(0) is UncaughtExceptionInterceptor)
        assertTrue(helperFromApp.client.interceptors.getOrNull(1) is UserAgentInterceptor)
        assertEquals(1, helperFromApp.client.interceptors.count { it is UncaughtExceptionInterceptor })
        assertEquals(1, helperFromApp.client.interceptors.count { it is UserAgentInterceptor })
        assertEquals(1, helperFromApp.cloudflareClient.interceptors.count { it is UncaughtExceptionInterceptor })
        assertEquals(1, helperFromApp.cloudflareClient.interceptors.count { it is UserAgentInterceptor })

        // NetworkHelper initialized with a bare OkHttpClient auto-prepends both interceptors once in order
        val helperFromBare = NetworkHelper(context, OkHttpClient())
        assertTrue(helperFromBare.client.interceptors.getOrNull(0) is UncaughtExceptionInterceptor)
        assertTrue(helperFromBare.client.interceptors.getOrNull(1) is UserAgentInterceptor)
        assertEquals(1, helperFromBare.client.interceptors.count { it is UncaughtExceptionInterceptor })
        assertEquals(1, helperFromBare.client.interceptors.count { it is UserAgentInterceptor })

        // Derived clients built via client.newBuilder() (like Mihon extensions do) retain both interceptors + custom ones
        val derived = helperFromApp.client.newBuilder()
            .rateLimit(3, 1, TimeUnit.SECONDS)
            .build()
        assertTrue(derived.interceptors.getOrNull(0) is UncaughtExceptionInterceptor)
        assertTrue(derived.interceptors.getOrNull(1) is UserAgentInterceptor)
        assertTrue(derived.interceptors.any { it is RateLimitInterceptor })
    }

    @Test
    fun `UserAgentInterceptor injects default User-Agent on actual HTTP requests preserves custom User-Agent and keeps MangaDex intact`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val capturedUserAgents = mutableListOf<String?>()
        val capturedAccepts = mutableListOf<String?>()

        val transportClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                capturedUserAgents.add(req.header("User-Agent"))
                capturedAccepts.add(req.header("Accept"))
                val body = when {
                    req.url.encodedPath == "/ping" -> "pong"
                    req.url.encodedPath.startsWith("/manga") -> """{"data":[],"total":0}"""
                    else -> """{"ok":true}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val helper = object : NetworkHelper(context, transportClient) {
            override fun defaultUserAgentProvider(): String = KomaDns.USER_AGENT
        }

        // 1. Request without User-Agent gets defaultUserAgentProvider() injected
        helper.client.newCall(Request.Builder().url("https://kiyomi.test/default-ua").build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(KomaDns.USER_AGENT, capturedUserAgents.last())

        // 2. Request with explicit custom User-Agent preserves custom header untouched
        helper.client.newCall(
            Request.Builder()
                .url("https://kiyomi.test/custom-ua")
                .header("User-Agent", "CustomExtensionAgent/3.1")
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals("CustomExtensionAgent/3.1", capturedUserAgents.last())

        // 3. MangaDexSourceAdapter requests via normalized default client preserve headers & succeed
        val mangaDex = MangaDexSourceAdapter(helper.client, db.sourcePreferenceDao())
        val mdHealth = mangaDex.checkStatus()
        assertTrue("MangaDex ping should succeed, got ${mdHealth.errorReason}", mdHealth.isOnline)
        assertEquals(KomaDns.USER_AGENT, capturedUserAgents.last())

        val mdPop = mangaDex.getPopular(1)
        assertEquals(0, mdPop.items.size)
        assertEquals(KomaDns.USER_AGENT, capturedUserAgents.last())
        assertEquals("application/json", capturedAccepts.last())
    }

    @Test
    fun `UncaughtExceptionInterceptor wraps unexpected runtime failures into IOException for async Call enqueue while preserving IOExceptions`() {
        val runtimeBoomClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor {
                throw IllegalStateException("Unexpected parser bug")
            }
            .build()

        val latch = CountDownLatch(1)
        val callbackError = AtomicReference<IOException?>(null)
        runtimeBoomClient.newCall(Request.Builder().url("https://kiyomi.test/runtime").build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callbackError.set(e)
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    latch.countDown()
                }
            })

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(callbackError.get())
        assertTrue(callbackError.get()?.cause is IllegalStateException)

        // CancellationException thrown inside an interceptor chain on async Call enqueue must also wrap into IOException
        val cancelBoomClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor {
                throw java.util.concurrent.CancellationException("Simulated inner cancel")
            }
            .build()
        val cancelLatch = CountDownLatch(1)
        val cancelError = AtomicReference<IOException?>(null)
        cancelBoomClient.newCall(Request.Builder().url("https://kiyomi.test/cancel").build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cancelError.set(e)
                    cancelLatch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    cancelLatch.countDown()
                }
            })
        assertTrue(cancelLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(cancelError.get())
        assertTrue(cancelError.get()?.cause is java.util.concurrent.CancellationException)

        // Genuine SocketTimeoutException passes through directly
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor {
                throw SocketTimeoutException("read timed out")
            }
            .build()

        val thrown = runCatching {
            timeoutClient.newCall(Request.Builder().url("https://kiyomi.test/timeout").build()).execute()
        }.exceptionOrNull()
        assertTrue(thrown is SocketTimeoutException)
    }

    @Test
    fun `strict extension checking UncaughtExceptionInterceptor in default client runs browse details chapters pages off main thread`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefsRepo = AppPreferencesRepository(context)
        val executedOnBackgroundThread = AtomicBoolean(true)

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor { chain ->
                if (Looper.getMainLooper().thread == Thread.currentThread()) {
                    executedOnBackgroundThread.set(false)
                }
                val path = chain.request().url.encodedPath
                val body = when {
                    path.startsWith("/popular") -> """{"items":[{"id":"m1","title":"Chronicle One"}]}"""
                    path.startsWith("/manga/") -> """{"id":"m1","title":"Chronicle One","author":"A. Sato"}"""
                    path.startsWith("/chapters/") -> """{"chapters":[{"id":"c1","name":"Chapter 1"}]}"""
                    path.startsWith("/pages/") -> """{"pages":["https://img.strict.test/p1.webp"]}"""
                    else -> """{"ok":true}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        MihonRuntimeInitializer.ensureInitialized(context, mockHttpClient, forceRebind = true)

        val strictExtension = StrictInterceptorCheckingHttpSource()
        val adapter = MihonSourceAdapter(
            mihonSource = strictExtension,
            extensionPackageName = "eu.kanade.tachiyomi.extension.en.strictcheck",
            httpClient = mockHttpClient
        )

        // Check health passes (no longer fails with "Offline uncaughtExceptionInterceptor must be present in default client")
        val health = adapter.checkStatus()
        assertTrue("Expected online health status, got error=${health.errorReason}", health.isOnline)

        // End-to-end extension calls succeed off the main thread
        val popular = adapter.getPopular(1)
        assertEquals(1, popular.items.size)
        assertEquals("Chronicle One", popular.items.first().title)

        val details = adapter.getMangaDetails("/manga/m1")
        assertEquals("A. Sato", details.author)

        val chapters = adapter.getChapters("/manga/m1")
        assertEquals(1, chapters.size)

        val pages = adapter.getChapterPages("/chapters/c1")
        assertEquals(1, pages.size)
        assertEquals("https://img.strict.test/p1.webp", pages.first().imageUrl)
        assertTrue("Extension network I/O must execute off the main thread", executedOnBackgroundThread.get())

        // Verify MangaRepository + ReaderViewModel complete cleanly on IO dispatcher without freezing
        val mangaDao = db.mangaDao()
        val sourceRegistry = SourceRegistry(
            context = context,
            httpClient = mockHttpClient,
            prefsRepo = prefsRepo,
            extensionDao = db.extensionDao(),
            sourcePrefDao = db.sourcePreferenceDao(),
            mihonLoader = null
        )
        val mangaRepo = MangaRepository(
            mangaDao = mangaDao,
            categoryDao = db.categoryDao(),
            chapterDao = db.chapterDao(),
            historyDao = db.historyDao(),
            updateDao = db.updateDao(),
            searchHistoryDao = db.searchHistoryDao(),
            sourceRegistry = sourceRegistry,
            prefsRepo = prefsRepo
        )
        val cached = mangaRepo.cacheSourceManga(popular.items.first())
        assertEquals("mihon_778899::/manga/m1", cached.id)

        // ReaderViewModel handles unknown source cleanly without crash
        val downloadRepo = DownloadRepository(context, db.downloadDao(), db.chapterDao(), prefsRepo)
        val readerVm = ReaderViewModel(
            mangaId = cached.id,
            initialChapterId = "missing_ch",
            mangaRepo = mangaRepo,
            downloadRepo = downloadRepo,
            sourceRegistry = sourceRegistry,
            prefsRepo = prefsRepo,
            imageLoader = coil.ImageLoader(context),
            appContext = context
        )
        delay(200)
        val readerState = readerVm.uiState.first { !it.isLoading }
        assertNotNull(readerState.error)
    }

    @Test
    fun `broken extension throwing LinkageError or Timeout surfaces clean error without crashing app`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefsRepo = AppPreferencesRepository(context)

        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor {
                throw SocketTimeoutException("connect timed out")
            }
            .build()
        MihonRuntimeInitializer.ensureInitialized(context, timeoutClient, forceRebind = true)

        val crashingAdapter = MihonSourceAdapter(
            mihonSource = LinkageBoomHttpSource(),
            extensionPackageName = "eu.kanade.tachiyomi.extension.en.boom",
            httpClient = timeoutClient
        )

        // Health check catches Error/LinkageError safely instead of crashing
        val health = crashingAdapter.checkStatus()
        assertFalse(health.isOnline)
        assertTrue(health.errorReason?.isNotBlank() == true)

        // Calling getPopular converts NoSuchMethodError into catchable IllegalStateException (never crashes thread)
        val popularErr = runCatching { crashingAdapter.getPopular(1) }.exceptionOrNull()
        assertTrue(popularErr is IllegalStateException)
        assertTrue(popularErr?.message?.contains("BoomSource") == true)

        // Also check timeout path
        val timedOutSource = StrictInterceptorCheckingHttpSource()
        val timedOutAdapter = MihonSourceAdapter(
            mihonSource = timedOutSource,
            extensionPackageName = "eu.kanade.tachiyomi.extension.en.slow",
            httpClient = timeoutClient
        )
        val timeoutErr = runCatching { timedOutAdapter.getPopular(1) }.exceptionOrNull()
        assertTrue(timeoutErr is SocketTimeoutException)

        // SourcesViewModel.openSourceManga returns immediately without blocking on missing/failing adapter
        val extRepo = ExtensionRepository(
            extensionDao = db.extensionDao(),
            httpClient = timeoutClient,
            context = context,
            trustManager = ExtensionTrustManager(context),
            installer = ExtensionInstaller(context),
            mihonLoader = null
        )
        val sourceRegistry = SourceRegistry(
            context = context,
            httpClient = timeoutClient,
            prefsRepo = prefsRepo,
            extensionDao = db.extensionDao(),
            sourcePrefDao = db.sourcePreferenceDao(),
            mihonLoader = null
        )
        val mangaRepo = MangaRepository(
            mangaDao = db.mangaDao(),
            categoryDao = db.categoryDao(),
            chapterDao = db.chapterDao(),
            historyDao = db.historyDao(),
            updateDao = db.updateDao(),
            searchHistoryDao = db.searchHistoryDao(),
            sourceRegistry = sourceRegistry,
            prefsRepo = prefsRepo
        )
        val vm = SourcesViewModel(sourceRegistry, extRepo, mangaRepo, prefsRepo)

        val navigatedId = AtomicReference<String?>(null)
        vm.openSourceManga(
            MangaCardUi(
                id = "${timedOutAdapter.id}::/manga/42",
                title = "Fast Open Title",
                coverUrl = "",
                sourceName = "StrictCheck"
            )
        ) { id ->
            navigatedId.set(id)
        }
        val deadline = System.currentTimeMillis() + 3000L
        while (navigatedId.get() == null && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertEquals("${timedOutAdapter.id}::/manga/42", navigatedId.get())
    }

    @Test
    fun `repository extension search matches name pkgId language live without network calls and filters All Installed NotInstalled Updates`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefsRepo = AppPreferencesRepository(context)
        val networkCallsDuringSearch = AtomicInteger(0)

        val spyClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor { chain ->
                networkCallsDuringSearch.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        // Seed repo + 4 extensions covering installed, not installed, update available, multiple languages
        val repoId = db.extensionDao().upsertRepo(
            ExtensionRepoEntity(
                name = "Keiyoushi",
                displayName = "Keiyoushi Extensions",
                indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
                isEnabled = true,
                isTrusted = true
            )
        )
        db.extensionDao().upsertPackages(
            listOf(
                ExtensionPackageEntity(
                    pkgId = "eu.kanade.tachiyomi.extension.en.mangadex",
                    repoId = repoId,
                    name = "MangaDex",
                    versionName = "1.4.210",
                    versionCode = 210,
                    latestVersionName = "1.4.212",
                    latestVersionCode = 212,
                    installedVersionCode = 210,
                    hasUpdate = true,
                    isInstalled = true,
                    isEnabled = true,
                    language = "en",
                    author = "Keiyoushi",
                    format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
                    compatibilityStatus = ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE.name
                ),
                ExtensionPackageEntity(
                    pkgId = "eu.kanade.tachiyomi.extension.en.mangasee",
                    repoId = repoId,
                    name = "MangaSee",
                    versionName = "1.4.40",
                    versionCode = 40,
                    latestVersionName = "1.4.40",
                    latestVersionCode = 40,
                    installedVersionCode = 40,
                    hasUpdate = false,
                    isInstalled = true,
                    isEnabled = true,
                    language = "en",
                    author = "Keiyoushi",
                    format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
                    compatibilityStatus = ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE.name
                ),
                ExtensionPackageEntity(
                    pkgId = "eu.kanade.tachiyomi.extension.ja.rawkuma",
                    repoId = repoId,
                    name = "Rawkuma",
                    versionName = "1.4.12",
                    versionCode = 12,
                    latestVersionName = "1.4.12",
                    latestVersionCode = 12,
                    isInstalled = false,
                    language = "ja",
                    author = "Keiyoushi",
                    format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
                    compatibilityStatus = ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA.name
                ),
                ExtensionPackageEntity(
                    pkgId = "eu.kanade.tachiyomi.extension.all.comick",
                    repoId = repoId,
                    name = "ComicK",
                    versionName = "1.4.85",
                    versionCode = 85,
                    latestVersionName = "1.4.85",
                    latestVersionCode = 85,
                    isInstalled = false,
                    language = "all",
                    author = "Keiyoushi",
                    format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
                    compatibilityStatus = ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA.name
                )
            )
        )

        val extRepo = ExtensionRepository(
            extensionDao = db.extensionDao(),
            httpClient = spyClient,
            context = context,
            trustManager = ExtensionTrustManager(context),
            installer = ExtensionInstaller(context),
            mihonLoader = null
        )
        val sourceRegistry = SourceRegistry(
            context = context,
            httpClient = spyClient,
            prefsRepo = prefsRepo,
            extensionDao = db.extensionDao(),
            sourcePrefDao = db.sourcePreferenceDao(),
            mihonLoader = null
        )
        val mangaRepo = MangaRepository(
            mangaDao = db.mangaDao(),
            categoryDao = db.categoryDao(),
            chapterDao = db.chapterDao(),
            historyDao = db.historyDao(),
            updateDao = db.updateDao(),
            searchHistoryDao = db.searchHistoryDao(),
            sourceRegistry = sourceRegistry,
            prefsRepo = prefsRepo
        )
        val vm = SourcesViewModel(sourceRegistry, extRepo, mangaRepo, prefsRepo)

        // Reset spy counter after any initial source availability check
        delay(200)
        networkCallsDuringSearch.set(0)

        // 1. All extensions present
        val initial = vm.uiState.first { it.packages.size == 4 }
        assertEquals(4, initial.totalIndexedPackagesCount)
        assertEquals(2, initial.installedPackagesCount)
        assertEquals(2, initial.notInstalledPackagesCount)
        assertEquals(1, initial.updatesPackagesCount)

        // 2. Search by extension name (case-insensitive)
        vm.updateExtSearch("MANGADEX")
        val byName = vm.uiState.first { it.extSearchQuery == "MANGADEX" && it.packages.size == 1 }
        assertEquals("MangaDex", byName.packages.first().name)

        // 3. Search by package ID
        vm.updateExtSearch("extension.all.comick")
        val byPkg = vm.uiState.first { it.extSearchQuery == "extension.all.comick" && it.packages.size == 1 }
        assertEquals("ComicK", byPkg.packages.first().name)

        // 4. Search by language
        vm.updateExtSearch("ja")
        val byLang = vm.uiState.first { it.extSearchQuery == "ja" && it.packages.size == 1 }
        assertEquals("Rawkuma", byLang.packages.first().name)

        // 5. Clear search restores all
        vm.updateExtSearch("")
        vm.uiState.first { it.extSearchQuery.isEmpty() && it.packages.size == 4 }

        // 6. Filter sub-tabs: Installed / Not installed / Updates / All
        vm.selectExtSubTab(ExtensionStoreTab.INSTALLED)
        val installed = vm.uiState.first { it.extSubTab == ExtensionStoreTab.INSTALLED && it.packages.size == 2 }
        assertTrue(installed.packages.all { it.isInstalled })

        vm.selectExtSubTab(ExtensionStoreTab.NOT_INSTALLED)
        val notInstalled = vm.uiState.first { it.extSubTab == ExtensionStoreTab.NOT_INSTALLED && it.packages.size == 2 }
        assertTrue(notInstalled.packages.none { it.isInstalled })

        vm.selectExtSubTab(ExtensionStoreTab.UPDATES)
        val updates = vm.uiState.first { it.extSubTab == ExtensionStoreTab.UPDATES && it.packages.size == 1 }
        assertEquals("MangaDex", updates.packages.first().name)

        // Verify zero network requests occurred during local extension search/filtering
        assertEquals(0, networkCallsDuringSearch.get())

        // 7. Compose UI test: live typing, clear button, and tailored empty state
        vm.selectExtSubTab(ExtensionStoreTab.ALL)
        composeRule.setContent {
            KomaTheme {
                SourcesScreen(
                    viewModel = vm,
                    onOpenSource = {},
                    onOpenManga = {}
                )
            }
        }
        composeRule.onNodeWithTag("sources_tab_2").performClick()
        composeRule.waitForIdle()

        // Search input is pinned & visible
        composeRule.onNodeWithTag("ext_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("ext_search_input").performTextInput("ComicK")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ComicK").assertIsDisplayed()

        // Clear button resets query
        composeRule.onNodeWithTag("ext_search_clear").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MangaDex").assertIsDisplayed()

        // Empty state on non-matching query + Clear Search & Filters button
        composeRule.onNodeWithTag("ext_search_input").performTextInput("zzzz_nonexistent_xyz")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No Extensions Match \"zzzz_nonexistent_xyz\"").assertIsDisplayed()
        composeRule.onNodeWithText("Clear Search & Filters").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rawkuma").assertIsDisplayed()

        // Final check: Not installed filter chip works in UI
        composeRule.onNodeWithTag("ext_subtab_not_installed").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rawkuma").assertIsDisplayed()
    }

    // ---- Test Mihon Extensions ----

    /**
     * Simulates real Keiyoushi extensions (e.g., MangaDex, lib/randomua) that explicitly assert
     * BOTH `UncaughtExceptionInterceptor` and `UserAgentInterceptor` are registered in `network.client`
     * and `network.cloudflareClient`.
     */
    private class StrictInterceptorCheckingHttpSource : HttpSource() {
        override val id: Long = 778899L
        override val name: String = "StrictCheck"
        override val baseUrl: String = "https://strict.example"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override val client: OkHttpClient
            get() {
                check(network.client.interceptors.any { it is UncaughtExceptionInterceptor }) {
                    "uncaughtExceptionInterceptor must be present in default client"
                }
                check(network.client.interceptors.any { it is UserAgentInterceptor }) {
                    "UserAgentInterceptor must be present in default client"
                }
                check(network.cloudflareClient.interceptors.any { it is UncaughtExceptionInterceptor }) {
                    "uncaughtExceptionInterceptor must be present in cloudflare client"
                }
                check(network.cloudflareClient.interceptors.any { it is UserAgentInterceptor }) {
                    "UserAgentInterceptor must be present in cloudflare client"
                }
                return network.client.newBuilder().build()
            }

        override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/$page", headers)

        override fun popularMangaParse(response: Response): MangasPage {
            response.close()
            return MangasPage(
                mangas = listOf(
                    SManga.create().apply {
                        url = "/manga/m1"
                        title = "Chronicle One"
                    }
                ),
                hasNextPage = false
            )
        }

        override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
        override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            GET("$baseUrl/popular/1?q=$query", headers)

        override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

        override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

        override fun mangaDetailsParse(response: Response): SManga {
            response.close()
            return SManga.create().apply {
                url = "/manga/m1"
                title = "Chronicle One"
                author = "A. Sato"
            }
        }

        override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/chapters/m1", headers)

        override fun chapterListParse(response: Response): List<SChapter> {
            response.close()
            return listOf(
                SChapter.create().apply {
                    url = "/chapters/c1"
                    name = "Chapter 1"
                    chapter_number = 1f
                }
            )
        }

        override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/pages/c1", headers)

        override fun pageListParse(response: Response): List<Page> {
            response.close()
            return listOf(Page(0, "", "https://img.strict.test/p1.webp"))
        }

        override fun imageUrlParse(response: Response): String = ""
    }

    private class LinkageBoomHttpSource : HttpSource() {
        override val id: Long = 991122L
        override val name: String = "BoomSource"
        override val baseUrl: String = "https://boom.example"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override fun popularMangaRequest(page: Int): Request {
            throw NoSuchMethodError("Simulated ABI method mismatch inside extension")
        }

        override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
        override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
        override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
        override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
        override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
        override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
        override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
        override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    }
}
