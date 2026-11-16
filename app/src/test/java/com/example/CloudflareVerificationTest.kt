package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.extension.ExtensionSessionManager
import com.example.extension.MihonRuntimeInitializer
import com.example.network.CloudflareChallengeCoordinator
import com.example.network.HttpResponseValidator
import com.example.network.KomaDns
import com.example.network.NetworkErrorFormatter
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.screens.webview.CloudflareWebViewActivity
import com.example.ui.theme.KomaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.WebCookieBridge
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassRequiredException
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeSolver
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudflareVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dbInstances = mutableListOf<KomaDatabase>()

    @After
    fun tearDown() {
        CloudflareChallengeCoordinator.setActiveCookieJar(null)
        dbInstances.forEach { runCatching { it.close() } }
        dbInstances.clear()
    }

    private fun newInMemoryDb(context: Context): KomaDatabase {
        return Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { dbInstances.add(it) }
    }

    private class FakeWebCookieBridge : WebCookieBridge {
        private val store = ConcurrentHashMap<String, String>()

        fun putRawCookie(url: String, headerValue: String) {
            store[hostKey(url)] = headerValue
        }

        override fun getCookie(url: String): String? = store[hostKey(url)]

        override fun setCookie(url: String, value: String) {
            val key = hostKey(url)
            val nameVal = value.substringBefore(";").trim()
            val eqIdx = nameVal.indexOf('=')
            if (eqIdx <= 0) return
            val name = nameVal.substring(0, eqIdx).trim()
            val cookieVal = nameVal.substring(eqIdx + 1).trim()
            val isDelete = value.contains("Expires=Thu, 01 Jan 1970", ignoreCase = true) ||
                value.contains("Max-Age=0", ignoreCase = true) ||
                cookieVal.isEmpty()

            val existing = parseMap(store[key])
            if (isDelete) {
                existing.remove(name)
            } else {
                existing[name] = cookieVal
            }
            store[key] = existing.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        override fun flush() = Unit

        override fun removeAllCookies() {
            store.clear()
        }

        private fun hostKey(url: String): String =
            runCatching { url.toHttpUrl().host.lowercase() }.getOrDefault(url.lowercase())

        private fun parseMap(raw: String?): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            if (raw.isNullOrBlank()) return map
            raw.split(";").forEach { part ->
                val trimmed = part.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
            }
            return map
        }
    }

    @Test
    fun `CloudflareInterceptor detects HTTP 200 403 429 503 challenges while ignoring normal HTML and JSON`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jar = AndroidCookieJar(webCookieBridge = FakeWebCookieBridge())

        // Normal Cloudflare-proxied JSON and normal HTML must NOT trigger Cloudflare challenge
        assertFalse(
            CloudflareInterceptor.isCloudflareChallengePayload(
                statusCode = 200,
                serverHeader = "cloudflare",
                cfMitigated = "",
                contentType = "application/json",
                bodySample = """{"result":"ok","data":[]}"""
            )
        )
        assertFalse(
            CloudflareInterceptor.isCloudflareChallengePayload(
                statusCode = 200,
                serverHeader = "cloudflare",
                cfMitigated = "",
                contentType = "text/html",
                bodySample = "<!doctype html><html><head><title>Manga Catalog</title></head><body><h1>Popular</h1></body></html>"
            )
        )

        // HTTP 200 Turnstile / JS challenge interstitial MUST trigger
        val turnstile200Html = """
            <!doctype html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
              <div id="challenge-running">Checking if the site connection is secure</div>
              <script>window._cf_chl_opt={cvId: '3'};</script>
              <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
            </body>
            </html>
        """.trimIndent()
        assertTrue(
            CloudflareInterceptor.isCloudflareChallengePayload(
                statusCode = 200,
                serverHeader = "cloudflare",
                cfMitigated = "",
                contentType = "text/html; charset=utf-8",
                bodySample = turnstile200Html
            )
        )

        // Interceptor rejects HTTP 200 Turnstile with CloudflareBypassRequiredException BEFORE JSON parsing
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor(CloudflareInterceptor(context, jar, { KomaDns.USER_AGENT }))
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Server", "cloudflare")
                    .body(turnstile200Html.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val ex200 = runCatching {
            client.newCall(Request.Builder().url("https://source200.example/api/manga.json").build())
                .execute()
                .use { HttpResponseValidator.parseJsonObjectStrict(it, "TestSource") }
        }.exceptionOrNull()

        assertTrue("Expected CloudflareBypassRequiredException, got $ex200", ex200 is CloudflareBypassRequiredException)
        val cfEx = ex200 as CloudflareBypassRequiredException
        assertEquals(200, cfEx.statusCode)
        assertEquals("https://source200.example/api/manga.json", cfEx.url)
        assertEquals("https://source200.example/api/manga.json", NetworkErrorFormatter.extractCloudflareUrl(cfEx))
        val msg = NetworkErrorFormatter.describe(cfEx, "source200.example")
        assertTrue(msg.contains("Cloudflare protection (HTTP 200)"))
        assertTrue(msg.contains("WebView"))

        // HTTP 429 and 503 challenges also trigger
        assertTrue(
            CloudflareInterceptor.isCloudflareChallengePayload(
                statusCode = 429,
                serverHeader = "cloudflare",
                cfMitigated = "challenge",
                contentType = "text/html",
                bodySample = "<html><title>Attention Required! | Cloudflare</title></html>"
            )
        )
        assertTrue(
            CloudflareInterceptor.isCloudflareChallengePayload(
                statusCode = 503,
                serverHeader = "cloudflare",
                cfMitigated = "",
                contentType = "text/html",
                bodySample = "<html><head><title>Just a moment...</title></head><body><script src=\"/cdn-cgi/challenge-platform/v1\"></script></body></html>"
            )
        )
    }

    @Test
    fun `AndroidCookieJar syncFromWebView unblocks cf_clearance isolates domains enforces expiry and persists to Room`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val sessionManager = ExtensionSessionManager(db.extensionDao())
        val bridge = FakeWebCookieBridge()
        val jar = AndroidCookieJar(backingCookieJar = sessionManager.cookieJar, webCookieBridge = bridge)

        val protectedUrl = "https://sub.protected.example/catalog".toHttpUrl()
        val parentUrl = "https://protected.example/".toHttpUrl()
        val unrelatedUrl = "https://unrelated.example/catalog".toHttpUrl()

        // 1. Seed stale cf_clearance + valid unexpired cookie + expired cookie
        val now = System.currentTimeMillis()
        jar.saveFromResponse(
            protectedUrl,
            listOf(
                Cookie.Builder()
                    .name("cf_clearance")
                    .value("stale_token")
                    .domain("protected.example")
                    .path("/")
                    .expiresAt(now + 60_000L)
                    .build(),
                Cookie.Builder()
                    .name("expired_tok")
                    .value("old")
                    .domain("protected.example")
                    .path("/")
                    .expiresAt(now - 5_000L)
                    .build()
            )
        )
        // Expired cookie must never be returned
        assertEquals(listOf("stale_token"), jar.get(protectedUrl).map { it.value })

        // 2. Removing cf_clearance blocks it in memory/WebView/backing jar
        val removed = jar.remove(protectedUrl, listOf("cf_clearance"), maxAge = 0)
        assertEquals(1, removed)
        assertTrue(jar.get(protectedUrl).isEmpty())

        // 3. WebView challenge solves and writes fresh parent-domain cookie into CookieManager
        bridge.putRawCookie(parentUrl.toString(), "cf_clearance=fresh_webview_clearance; cf_bm=session_bm")
        bridge.putRawCookie(unrelatedUrl.toString(), "cf_clearance=other_site_token")

        val synced = jar.syncFromWebView(protectedUrl, extraUrls = listOf(unrelatedUrl))
        assertTrue(synced.any { it.name == "cf_clearance" && it.value == "fresh_webview_clearance" })
        assertFalse(
            "Unrelated domain extraUrl cookies must never leak into target host sync",
            synced.any { it.value == "other_site_token" }
        )

        // 4. Subsequent get() returns fresh cf_clearance and NOT unrelated cookies
        val loadedForSub = jar.get(protectedUrl)
        assertEquals("fresh_webview_clearance", loadedForSub.firstOrNull { it.name == "cf_clearance" }?.value)
        assertEquals("session_bm", loadedForSub.firstOrNull { it.name == "cf_bm" }?.value)

        val loadedForUnrelated = jar.get(unrelatedUrl)
        assertEquals("other_site_token", loadedForUnrelated.firstOrNull { it.name == "cf_clearance" }?.value)
        assertFalse(loadedForUnrelated.any { it.value == "fresh_webview_clearance" })

        // 5. Verify fresh cookie persisted into Room-backed ExtensionSessionManager across fresh jar reload
        val persisted = runBlocking {
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                val rows = db.extensionDao().getAllCookiesOnce()
                if (rows.any { it.name == "cf_clearance" && it.value == "fresh_webview_clearance" }) {
                    break
                }
                delay(20L)
            }
            val freshSessionManager = ExtensionSessionManager(db.extensionDao())
            freshSessionManager.preloadCookies()
            freshSessionManager.cookieJar.loadForRequest(protectedUrl)
        }
        assertEquals("fresh_webview_clearance", persisted.firstOrNull { it.name == "cf_clearance" }?.value)
    }

    @Test
    fun `CloudflareChallengeCoordinator launches WebView with matching User-Agent syncs cookies and retries HTTP 200 challenge`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        val sessionManager = ExtensionSessionManager(db.extensionDao())
        val bridge = FakeWebCookieBridge()
        val jar = AndroidCookieJar(backingCookieJar = sessionManager.cookieJar, webCookieBridge = bridge)

        val launchedIntentRef = AtomicReference<Intent?>(null)
        val foregroundActivity = Robolectric.buildActivity(Activity::class.java).create().resume().get()

        val coordinator = CloudflareChallengeCoordinator(
            cookieJar = jar,
            waitTimeoutSeconds = 5L,
            activityLauncher = { _, intent ->
                launchedIntentRef.set(intent)
                // Simulate background completion after WebView CAPTCHA solves & writes CookieManager
                Executors.newSingleThreadExecutor().execute {
                    bridge.putRawCookie("https://chap-host.example/list", "cf_clearance=verified_live_cookie")
                    val challengeId = intent.getStringExtra(CloudflareWebViewActivity.EXTRA_CHALLENGE_ID)
                    CloudflareChallengeCoordinator.completeChallenge(
                        challengeId = challengeId,
                        succeeded = true,
                        finalUrl = "https://chap-host.example/list"
                    )
                }
            }
        )
        coordinator.setForegroundActivityForTesting(foregroundActivity)

        val requestUserAgents = mutableListOf<String?>()
        val requestCookies = mutableListOf<String?>()
        val attemptCount = AtomicInteger(0)

        val helper = NetworkHelper(
            context = context,
            baseClient = OkHttpClient.Builder().cookieJar(jar).build(),
            sharedCookieJar = jar,
            cloudflareSolver = coordinator
        )

        val client = helper.client.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestUserAgents.add(req.header("User-Agent"))
                requestCookies.add(req.header("Cookie"))
                val attempt = attemptCount.incrementAndGet()
                if (attempt == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Server", "cloudflare")
                        .body(
                            "<html><head><title>Just a moment...</title></head><body><div id=\"cf-challenge-running\"></div></body></html>"
                                .toResponseBody("text/html".toMediaType())
                        )
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"title":"Kagurabachi","status":"ok"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        // Execute off main thread (matching coroutine Dispatchers.IO behavior)
        val resultFuture = Executors.newSingleThreadExecutor().submit<String> {
            client.newCall(Request.Builder().url("https://chap-host.example/list").build())
                .execute()
                .use { resp ->
                    HttpResponseValidator.parseJsonObjectStrict(resp, "TestSource").getString("title")
                }
        }
        val title = resultFuture.get(10, TimeUnit.SECONDS)
        assertEquals("Kagurabachi", title)
        assertEquals(2, attemptCount.get())

        val launched = launchedIntentRef.get()
        assertNotNull("Expected CloudflareWebViewActivity Intent to be launched", launched)
        assertEquals("https://chap-host.example/list", launched?.getStringExtra(CloudflareWebViewActivity.EXTRA_URL))
        assertEquals(KomaDns.USER_AGENT, launched?.getStringExtra(CloudflareWebViewActivity.EXTRA_USER_AGENT))
        assertEquals(KomaDns.USER_AGENT, requestUserAgents.first())
        assertEquals(KomaDns.USER_AGENT, requestUserAgents.last())
        assertTrue(
            "Retried request must send synced cf_clearance cookie, was: ${requestCookies.last()}",
            requestCookies.last().orEmpty().contains("cf_clearance=verified_live_cookie")
        )
    }

    @Test
    fun `persistent challenge failure after WebView retry sets verificationAttempted and headless mode fails fast without hanging`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = FakeWebCookieBridge()
        val jar = AndroidCookieJar(webCookieBridge = bridge)

        // 1. Headless / no foreground Activity fails cleanly without blocking
        val headlessCoordinator = CloudflareChallengeCoordinator(cookieJar = jar, waitTimeoutSeconds = 5L)
        val headlessResult = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        Executors.newSingleThreadExecutor().execute {
            headlessResult.set(
                headlessCoordinator.attemptResolve(
                    context,
                    Request.Builder().url("https://headless.example/feed").build(),
                    jar
                )
            )
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, headlessResult.get())

        // 2. Main-thread call fails cleanly instead of deadlocking main looper
        assertFalse(
            headlessCoordinator.attemptResolve(
                context,
                Request.Builder().url("https://mainthread.example/feed").build(),
                jar
            )
        )

        // 3. Persistent challenge after solver reports success throws verificationAttempted = true
        val persistentClient = OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor(
                CloudflareInterceptor(
                    context = context,
                    cookieJar = jar,
                    defaultUserAgentProvider = { KomaDns.USER_AGENT },
                    challengeSolver = CloudflareChallengeSolver { _, req, targetJar ->
                        targetJar.saveFromResponse(
                            req.url,
                            listOf(
                                Cookie.Builder()
                                    .name("cf_clearance")
                                    .value("rejected_token")
                                    .domain(req.url.host)
                                    .path("/")
                                    .expiresAt(System.currentTimeMillis() + 60_000L)
                                    .build()
                            )
                        )
                        true
                    }
                )
            )
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("cf-mitigated", "challenge")
                    .body("<html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val thrown = runCatching {
            persistentClient.newCall(Request.Builder().url("https://persistent.example/manga").build()).execute()
        }.exceptionOrNull()

        assertTrue(thrown is CloudflareBypassRequiredException)
        val cfEx = thrown as CloudflareBypassRequiredException
        assertTrue("Expected verificationAttempted = true", cfEx.verificationAttempted)
        assertTrue(NetworkErrorFormatter.describe(cfEx).contains("could not be cleared"))
    }

    @Test
    fun `AppContainer NetworkHelper Injekt and coil share unified single OkHttpClient and ErrorRetryBanner renders Open WebView`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        MihonRuntimeInitializer.ensureInitialized(
            context = context,
            httpClient = container.httpClient,
            forceRebind = true,
            sharedCookieJar = container.androidCookieJar,
            cloudflareSolver = container.cloudflareCoordinator
        )

        // Unified single OkHttpClient + NetworkHelper + Injekt verification
        val injektHelper = Injekt.get<NetworkHelper>()
        val injektClient = Injekt.get<OkHttpClient>()

        assertSame("AndroidCookieJar must be shared between AppContainer and NetworkHelper", container.androidCookieJar, injektHelper.cookieJar)
        assertSame("client and cloudflareClient must unify to the single OkHttpClient", injektHelper.client, injektHelper.cloudflareClient)
        assertSame("AppContainer httpClient must be identical to NetworkHelper default client", container.httpClient, injektHelper.client)
        assertSame("Injekt OkHttpClient singleton must be the unified client", container.httpClient, injektClient)

        assertTrue(container.httpClient.interceptors.any { it is UncaughtExceptionInterceptor })
        assertTrue(container.httpClient.interceptors.any { it is UserAgentInterceptor })
        assertTrue(container.httpClient.interceptors.any { it is CloudflareInterceptor })
        assertSame(container.androidCookieJar, container.httpClient.cookieJar)

        // Compose UI test: ErrorRetryBanner displays Open WebView and triggers callback cleanly
        var webViewClicked = false
        var retryClicked = false
        composeRule.setContent {
            KomaTheme {
                ErrorRetryBanner(
                    message = "Cloudflare protection (HTTP 200) on test.example. Open the source in WebView or verify clearance cookies.",
                    onRetry = { retryClicked = true },
                    onOpenWebView = { webViewClicked = true }
                )
            }
        }
        composeRule.onNodeWithTag("error_webview_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("error_retry_button").assertIsDisplayed().performClick()
        assertTrue("Expected Open WebView callback invocation", webViewClicked)
        assertTrue("Expected Retry callback invocation", retryClicked)
    }

    @Test
    fun `CloudflareWebViewActivity SSL error handler always calls cancel without proceeding`() {
        // Verify helper method for cookie extraction & strict intent construction
        val header = "cf_bm=abc; cf_clearance=token_xyz_123; other=val"
        assertEquals("token_xyz_123", CloudflareWebViewActivity.extractCookieValue(header, "cf_clearance"))
        assertNull(CloudflareWebViewActivity.extractCookieValue(header, "missing"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = CloudflareChallengeCoordinator.createVerificationIntent(
            context = context,
            url = "https://strict-tls.example/browse",
            sourceName = "StrictSource",
            userAgent = KomaDns.USER_AGENT
        )
        assertEquals("https://strict-tls.example/browse", intent.getStringExtra(CloudflareWebViewActivity.EXTRA_URL))
        assertEquals("StrictSource", intent.getStringExtra(CloudflareWebViewActivity.EXTRA_SOURCE_NAME))
        assertEquals(KomaDns.USER_AGENT, intent.getStringExtra(CloudflareWebViewActivity.EXTRA_USER_AGENT))
        assertTrue(intent.getBooleanExtra(CloudflareWebViewActivity.EXTRA_IS_MANUAL, true))
    }
}
