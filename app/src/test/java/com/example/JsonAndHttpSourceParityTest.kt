package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.MangaRepository
import com.example.data.repository.TrackingRepository
import com.example.domain.model.SearchFilters
import com.example.domain.model.TrackerService
import com.example.extension.ExtensionRepositoryManager
import com.example.extension.MihonRuntimeInitializer
import com.example.network.HttpResponseValidator
import com.example.network.KomaDns
import com.example.network.NetworkErrorFormatter
import com.example.network.ResponseFailureKind
import com.example.network.UnexpectedResponseFormatException
import com.example.source.DeclarativeJsonSourceAdapter
import com.example.source.MangaDexSourceAdapter
import com.example.source.MihonSourceAdapter
import com.example.source.SourceRegistry
import com.example.ui.screens.home.HomeViewModel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassRequiredException
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.network.parseAs as networkParseAs
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.parseAs as keiyoushiParseAs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tachiyomi.core.common.util.lang.parseAs as coreParseAs
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JsonAndHttpSourceParityTest {

    @Serializable
    data class DummyDto(val id: Int)

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

    private fun pumpMainLooperUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val mainShadow = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            mainShadow.idle()
            if (condition()) break
            Thread.sleep(20)
            mainShadow.idle()
        }
        mainShadow.idle()
    }

    @Test
    fun `repository refresh rejects HTML 200 Cloudflare 403 and 404 error pages cleanly while accepting gzipped protobuf v2`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)

        val validV2Bytes = buildSampleKeiyoushiV2IndexBytes()
        val gzippedV2Bytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(validV2Bytes) }
            bos.toByteArray()
        }

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when {
                    path.endsWith("/html-login") -> {
                        // Simulate redirect to an HTML login page with HTTP 200
                        val redirectedReq = req.newBuilder()
                            .url("https://auth.repo.example/login")
                            .build()
                        Response.Builder()
                            .request(redirectedReq)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("<!-- comment -->\n<!doctype html><html><head><title>Sign in</title></head><body>Please sign in</body></html>".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    path.endsWith("/cf-challenge") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .header("Server", "cloudflare")
                            .header("cf-mitigated", "challenge")
                            .body("<!doctype html><html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    path.endsWith("/not-found/index.min.json") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("<!doctype html><html><title>404 Not Found</title></html>".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    path.endsWith("/keiyoushi/index.pb") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(gzippedV2Bytes.toResponseBody("application/x-protobuf".toMediaType()))
                            .build()
                    }
                    path.endsWith("/keiyoushi/repo.json") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"meta":{"name":"Keiyoushi Extensions","website":"https://keiyoushi.github.io"}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val manager = ExtensionRepositoryManager(db.extensionDao(), mockClient)

        // 1. HTML returned with HTTP 200 after redirect
        val htmlRepo = ExtensionRepoEntity(
            id = 1L,
            name = "Misconfigured Repo",
            displayName = "Misconfigured Repo",
            indexUrl = "https://repo.example/html-login"
        )
        db.extensionDao().upsertRepo(htmlRepo)
        val htmlRes = manager.refreshRepository(htmlRepo)
        assertTrue(htmlRes.isFailure)
        val htmlCause = htmlRes.exceptionOrNull()?.cause as? UnexpectedResponseFormatException
        assertEquals(ResponseFailureKind.HTML_PAGE, htmlCause?.kind)
        val htmlMsg = htmlRes.exceptionOrNull()?.message.orEmpty()
        assertFalse(htmlMsg.contains("cannot be converted to JSONObject"))
        assertTrue("Expected redirected URL in message, got: $htmlMsg", htmlMsg.contains("auth.repo.example/login"))
        assertTrue("Expected HTML hint in message, got: $htmlMsg", htmlMsg.contains("HTML", ignoreCase = true))

        // 2. Cloudflare challenge HTTP 403
        val cfRepo = ExtensionRepoEntity(
            id = 2L,
            name = "CF Repo",
            displayName = "CF Repo",
            indexUrl = "https://repo.example/cf-challenge"
        )
        db.extensionDao().upsertRepo(cfRepo)
        val cfRes = manager.refreshRepository(cfRepo)
        assertTrue(cfRes.isFailure)
        assertTrue(cfRes.exceptionOrNull()?.cause is CloudflareBypassRequiredException)
        assertTrue(cfRes.exceptionOrNull()?.message?.contains("Cloudflare", ignoreCase = true) == true)

        // 3. 404 Not Found HTML
        val notFoundRepo = ExtensionRepoEntity(
            id = 3L,
            name = "404 Repo",
            displayName = "404 Repo",
            indexUrl = "https://repo.example/not-found/index.min.json"
        )
        db.extensionDao().upsertRepo(notFoundRepo)
        val err404Res = manager.refreshRepository(notFoundRepo)
        assertTrue(err404Res.isFailure)
        val cause404 = err404Res.exceptionOrNull()?.cause as? UnexpectedResponseFormatException
        assertEquals(ResponseFailureKind.HTTP_ERROR, cause404?.kind)
        assertEquals(404, cause404?.statusCode)
        assertTrue(err404Res.exceptionOrNull()?.message?.contains("404") == true)

        // 4. Valid gzipped Keiyoushi index.pb (v2) succeeds cleanly
        val okRepo = ExtensionRepoEntity(
            id = 4L,
            name = "Keiyoushi",
            displayName = "Keiyoushi",
            indexUrl = "https://repo.example/keiyoushi/index.pb"
        )
        db.extensionDao().upsertRepo(okRepo)
        val okRes = manager.refreshRepository(okRepo)
        assertTrue("Expected gzipped Keiyoushi v2 index.pb to succeed, got ${okRes.exceptionOrNull()}", okRes.isSuccess)
        assertEquals(1, okRes.getOrNull())
    }

    @Test
    fun `MangaDex DeclarativeJson Tracking and parseAs reject HTML doctype responses cleanly without Demo Mode error`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val prefsRepo = AppPreferencesRepository(context)
        prefsRepo.setDemoMode(false)
        prefsRepo.setTrackerToken(TrackerService.ANILIST, "dummy_token_for_test")

        val htmlEndpointClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<!doctype html><html><head><title>Portal Login</title></head><body>Captive Portal</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        // 1. MangaDexSourceAdapter rejects HTML before JSONObject(...)
        val mangaDex = MangaDexSourceAdapter(htmlEndpointClient, db.sourcePreferenceDao())
        val mdErr = runCatching { mangaDex.getPopular(1) }.exceptionOrNull()
        assertTrue("Expected UnexpectedResponseFormatException, got $mdErr", mdErr is UnexpectedResponseFormatException)
        assertFalse(mdErr?.message.orEmpty().contains("cannot be converted to JSONObject"))

        // 2. DeclarativeJsonSourceAdapter rejects HTML in getPopular and getMangaDetails
        val decl = DeclarativeJsonSourceAdapter(
            id = "decl_test",
            name = "DeclTest",
            language = "en",
            descriptor = JSONObject().apply {
                put("baseUrl", "https://api.decl.test")
                put("popularEndpoint", "/popular?page={page}")
                put("detailsEndpoint", "/manga/{id}")
            },
            httpClient = htmlEndpointClient
        )
        val declPopErr = runCatching { decl.getPopular(1) }.exceptionOrNull()
        assertTrue(declPopErr is UnexpectedResponseFormatException)
        val declDetErr = runCatching { decl.getMangaDetails("m1") }.exceptionOrNull()
        assertTrue(declDetErr is UnexpectedResponseFormatException)

        // 3. TrackingRepository rejects HTML cleanly
        val trackingRepo = TrackingRepository(db.trackingDao(), prefsRepo, htmlEndpointClient)
        val trackRes = trackingRepo.searchRemote(TrackerService.ANILIST, "Chainsaw")
        assertTrue(trackRes.isFailure)
        assertTrue(trackRes.exceptionOrNull() is UnexpectedResponseFormatException)

        // 4. Response.parseAs<T>() across all 3 extension packages rejects HTML cleanly
        val sampleResp = Response.Builder()
            .request(Request.Builder().url("https://ext.test/api.json").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("<!doctype html><html><title>Blocked</title></html>".toResponseBody("text/html".toMediaType()))
            .build()
        val p1 = runCatching { sampleResp.networkParseAs<DummyDto>() }.exceptionOrNull()
        assertTrue(p1 is UnexpectedResponseFormatException)

        val sampleResp2 = sampleResp.newBuilder()
            .body("<!doctype html><html><title>Blocked</title></html>".toResponseBody("text/html".toMediaType()))
            .build()
        val p2 = runCatching { sampleResp2.keiyoushiParseAs<DummyDto>() }.exceptionOrNull()
        assertTrue(p2 is UnexpectedResponseFormatException)

        val sampleResp3 = sampleResp.newBuilder()
            .body("<!doctype html><html><title>Blocked</title></html>".toResponseBody("text/html".toMediaType()))
            .build()
        val p3 = runCatching { sampleResp3.coreParseAs<DummyDto>() }.exceptionOrNull()
        assertTrue(p3 is UnexpectedResponseFormatException)

        // 5. NetworkErrorFormatter never leaks raw JSONException("Value <!doctype ...")
        val rawJsonExMsg = NetworkErrorFormatter.describe(
            JSONException("Value <!doctype of type java.lang.String cannot be converted to JSONObject"),
            "api.mangadex.org"
        )
        assertFalse(rawJsonExMsg.contains("Value <!doctype"))
        assertTrue(rawJsonExMsg.contains("invalid JSON or an unexpected HTML", ignoreCase = true))

        // 6. HomeViewModel surfaces categorized error WITHOUT "Check connection or enable Demo Mode."
        val registry = SourceRegistry(
            context = context,
            httpClient = htmlEndpointClient,
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
            sourceRegistry = registry,
            prefsRepo = prefsRepo
        )
        val homeVm = HomeViewModel(mangaRepo, registry, prefsRepo)
        val homeCollectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            homeVm.uiState.collect { }
        }
        pumpMainLooperUntil(5_000L) {
            homeVm.uiState.value.feedError != null
        }
        homeCollectJob.cancel()
        val feedErr = homeVm.uiState.value.feedError.orEmpty()
        assertTrue("Expected HTML/JSON error on Home, got: $feedErr", feedErr.contains("HTML", ignoreCase = true))
        assertFalse(feedErr.contains("Demo Mode", ignoreCase = true))
        assertFalse(feedErr.contains("cannot be converted to JSONObject"))
    }

    @Test
    fun `HttpSource exposes genuine Mihon setUrlWithoutDomain JVM signatures and executes Akuma-style ParsedHttpSource end to end`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Exact JVM binary descriptor checks matching Mihon extensions-lib:
        //    setUrlWithoutDomain(SManga, String)V and setUrlWithoutDomain(SChapter, String)V
        val mangaMethod = HttpSource::class.java.getMethod(
            "setUrlWithoutDomain",
            SManga::class.java,
            String::class.java
        )
        assertEquals(Void.TYPE, mangaMethod.returnType)
        assertTrue(Modifier.isPublic(mangaMethod.modifiers))

        val chapterMethod = HttpSource::class.java.getMethod(
            "setUrlWithoutDomain",
            SChapter::class.java,
            String::class.java
        )
        assertEquals(Void.TYPE, chapterMethod.returnType)
        assertTrue(Modifier.isPublic(chapterMethod.modifiers))

        val networkGetter = HttpSource::class.java.getMethod("getNetwork")
        assertTrue(Modifier.isPublic(networkGetter.modifiers))

        // Verify Page @JvmOverloads constructors work with 1, 2, 3, and 4 args
        assertEquals(0, Page(0).index)
        assertEquals("/p/1", Page(0, "/p/1").url)
        assertEquals("https://cdn.example/1.jpg", Page(0, "/p/1", "https://cdn.example/1.jpg").imageUrl)

        val offMainThread = AtomicBoolean(true)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor { chain ->
                if (Looper.getMainLooper().thread == Thread.currentThread()) {
                    offMainThread.set(false)
                }
                val path = chain.request().url.encodedPath
                val html = when {
                    path.startsWith("/browse") -> """
                        <html><body>
                          <div class="post">
                            <a href="https://akuma.example/g/12345/?page=2#top">Akuma Gallery One</a>
                            <img src="https://cdn.akuma.example/cover/12345.jpg"/>
                          </div>
                        </body></html>
                    """.trimIndent()
                    path.startsWith("/search") -> """
                        <html><body>
                          <div class="post">
                            <a href="https://akuma.example/g/67890/">Akuma Search Hit</a>
                            <img src="https://cdn.akuma.example/cover/67890.jpg"/>
                          </div>
                        </body></html>
                    """.trimIndent()
                    path.startsWith("/g/12345") -> """
                        <html><body>
                          <h1 class="title">Akuma Gallery One</h1>
                          <div class="artist">Artist A</div>
                          <ul class="chapters">
                            <li><a href="https://akuma.example/g/12345/read?ch=1#p1">Chapter 1</a></li>
                          </ul>
                          <div class="pages">
                            <img data-src="https://img.akuma.example/12345/01.webp"/>
                            <img data-src="https://img.akuma.example/12345/02.webp"/>
                          </div>
                        </body></html>
                    """.trimIndent()
                    else -> "<html><body>OK</body></html>"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        MihonRuntimeInitializer.ensureInitialized(context, mockClient, forceRebind = true)

        val akumaLike = AkumaLikeParsedSource()

        // Invoke HttpSource.setUrlWithoutDomain(SManga, String) directly via reflection (exact bytecode path)
        val reflectManga = SManga.create().apply { title = "Reflected" }
        mangaMethod.invoke(akumaLike, reflectManga, "https://akuma.example/g/999/?sort=new#sec")
        assertEquals("/g/999/?sort=new#sec", reflectManga.url)

        val reflectChapter = SChapter.create().apply { name = "Ch 1" }
        chapterMethod.invoke(akumaLike, reflectChapter, "https://akuma.example/g/999/c/1")
        assertEquals("/g/999/c/1", reflectChapter.url)

        // End-to-end MihonSourceAdapter flow
        val adapter = MihonSourceAdapter(
            mihonSource = akumaLike,
            extensionPackageName = "eu.kanade.tachiyomi.extension.all.akuma",
            httpClient = mockClient
        )

        val pop = adapter.getPopular(1)
        assertEquals(1, pop.items.size)
        assertEquals("/g/12345/?page=2#top", pop.items.first().mangaKey)
        assertEquals("Akuma Gallery One", pop.items.first().title)

        val search = adapter.search("akuma", 1, SearchFilters())
        assertEquals(1, search.items.size)
        assertEquals("/g/67890/", search.items.first().mangaKey)

        val details = adapter.getMangaDetails(pop.items.first().mangaKey)
        assertEquals("Artist A", details.artist)

        val chapters = adapter.getChapters(pop.items.first().mangaKey)
        assertEquals(1, chapters.size)
        assertEquals("/g/12345/read?ch=1#p1", chapters.first().chapterKey)

        val pages = adapter.getChapterPages(chapters.first().chapterKey)
        assertEquals(2, pages.size)
        assertEquals("https://img.akuma.example/12345/01.webp", pages[0].imageUrl)
        assertEquals("https://akuma.example", pages[0].headers["Referer"])
        assertTrue(offMainThread.get())
    }

    /**
     * Simulates an extension compiled against genuine Mihon `extensions-lib` calling
     * `HttpSource`'s member extensions `SManga.setUrlWithoutDomain(String)` and
     * `SChapter.setUrlWithoutDomain(String)`, plus `HttpUrl` request builders.
     */
    private class AkumaLikeParsedSource : ParsedHttpSource() {
        override val name: String = "Akuma"
        override val baseUrl: String = "https://akuma.example"
        override val lang: String = "all"
        override val supportsLatest: Boolean = true

        override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

        override fun popularMangaRequest(page: Int): Request =
            GET("$baseUrl/browse?page=$page".toHttpUrl(), headers)

        override fun popularMangaSelector(): String = "div.post"

        override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
            val link = element.selectFirst("a")!!
            // Force invoking HttpSource's member extension signature (SManga, String)
            this@AkumaLikeParsedSource.run { setUrlWithoutDomain(link.attr("href")) }
            title = link.text()
            thumbnail_url = element.selectFirst("img")?.attr("src")
        }

        override fun popularMangaNextPageSelector(): String? = null

        override fun latestUpdatesRequest(page: Int): Request =
            GET("$baseUrl/browse?sort=latest&page=$page", headers)

        override fun latestUpdatesSelector(): String = popularMangaSelector()
        override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
        override fun latestUpdatesNextPageSelector(): String? = null

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            POST("$baseUrl/search?q=$query".toHttpUrl(), headers)

        override fun searchMangaSelector(): String = popularMangaSelector()
        override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
        override fun searchMangaNextPageSelector(): String? = null

        override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
            title = document.selectFirst("h1.title")?.text().orEmpty()
            artist = document.selectFirst("div.artist")?.text()
            status = SManga.COMPLETED
        }

        override fun chapterListSelector(): String = "ul.chapters li"

        override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
            val link = element.selectFirst("a")!!
            this@AkumaLikeParsedSource.run { setUrlWithoutDomain(link.attr("href")) }
            name = link.text()
            chapter_number = 1f
        }

        override fun pageListParse(document: Document): List<Page> =
            document.select("div.pages img").mapIndexed { idx, el ->
                Page(idx, "", el.attr("data-src"))
            }

        override fun imageUrlParse(document: Document): String = ""
    }

    private fun buildSampleKeiyoushiV2IndexBytes(): ByteArray {
        val v2Source = ByteArrayOutputStream().apply {
            write(pbVarintField(1, 1001L))
            write(pbBytesField(2, "Keiyoushi Test".toByteArray()))
            write(pbBytesField(3, "en".toByteArray()))
            write(pbBytesField(4, "https://sample.example".toByteArray()))
        }.toByteArray()
        val v2Artifacts = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "https://cdn.example/apk/test.apk".toByteArray()))
            write(pbBytesField(2, "https://cdn.example/icon/test.png".toByteArray()))
        }.toByteArray()
        val v2Ext = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "Mihon: Test Ext".toByteArray()))
            write(pbBytesField(2, "eu.kanade.tachiyomi.extension.en.test".toByteArray()))
            write(pbBytesField(3, v2Artifacts))
            write(pbBytesField(4, "1.5".toByteArray()))
            write(pbVarintField(5, 10L))
            write(pbBytesField(6, "1.5.10".toByteArray()))
            write(pbVarintField(7, 1L))
            write(pbBytesField(8, v2Source))
        }.toByteArray()
        val wrapper = pbBytesField(1, v2Ext)
        return ByteArrayOutputStream().apply {
            write(pbBytesField(1, "Keiyoushi".toByteArray()))
            write(pbBytesField(2, "KEI".toByteArray()))
            write(pbBytesField(3, "b".repeat(64).toByteArray()))
            write(pbBytesField(101, wrapper))
        }.toByteArray()
    }

    private fun pbBytesField(field: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, (field shl 3) or 2)
        writeVarint(out, data.size)
        out.write(data)
        return out.toByteArray()
    }

    private fun pbVarintField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, field shl 3)
        writeVarintLong(out, value)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun writeVarintLong(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }
}
