package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.extension.ApkInspectionResult
import com.example.extension.ExtensionCompatibilityResolver
import com.example.extension.ExtensionIndexParser
import com.example.extension.ExtensionSecurityManager
import com.example.extension.MihonRuntimeInitializer
import com.example.network.KomaDns
import com.example.network.NetworkErrorFormatter
import eu.kanade.tachiyomi.extension.util.ChildFirstPathClassLoader
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.PATCH
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassRequiredException
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeSolver
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.network.parseAs as networkParseAs
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs as keiyoushiParseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tachiyomi.core.common.util.lang.parseAs as coreParseAs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MihonEngineParityTest {

    @Serializable
    data class SampleDto(
        val title: String,
        val count: Int = 0
    )

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `MihonRuntimeInitializer binds Json ProtoBuf XML JavaScriptEngine NetworkHelper and Context into Injekt`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bareClient = OkHttpClient()

        MihonRuntimeInitializer.ensureInitialized(context, bareClient, forceRebind = true)

        assertTrue(Injekt.hasFactory(NetworkHelper::class.java))
        assertTrue(Injekt.hasFactory(OkHttpClient::class.java))
        assertTrue(Injekt.hasFactory(Json::class.java))
        assertTrue(Injekt.hasFactory(ProtoBuf::class.java))
        assertTrue(Injekt.hasFactory(XML::class.java))
        assertTrue(Injekt.hasFactory(JavaScriptEngine::class.java))
        assertTrue(Injekt.hasFactory(Context::class.java))

        val boundClient: OkHttpClient = Injekt.get()
        assertTrue(boundClient.interceptors.getOrNull(0) is UncaughtExceptionInterceptor)
        assertTrue(boundClient.interceptors.getOrNull(1) is UserAgentInterceptor)

        // Safe JavaScriptEngine behavior on JVM (QuickJS native lib unavailable on host JVM)
        val jsEngine: JavaScriptEngine = Injekt.get()
        val jsResult = runCatching { jsEngine.evaluate<Int>("1 + 2") }
        if (jsResult.isSuccess) {
            assertEquals(3, jsResult.getOrNull())
        } else {
            val err = jsResult.exceptionOrNull()
            assertTrue(err is IllegalStateException)
            assertTrue(err?.cause is UnsatisfiedLinkError)
        }
    }

    @Test
    fun `parseAs across mihon keiyoushi and tachiyomi packages decodes json okio responses PATCH requests and asJsoup`() {
        val jsonPayload = """{"title":"Kiyomi Chapter","count":14,"ignoredField":"ok"}"""

        val dtoFromString: SampleDto = jsonPayload.keiyoushiParseAs()
        assertEquals("Kiyomi Chapter", dtoFromString.title)
        assertEquals(14, dtoFromString.count)

        val closedFlag = AtomicBoolean(false)
        val responseBody = object : okhttp3.ResponseBody() {
            private val delegate = jsonPayload.toResponseBody("application/json".toMediaType())
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun source() = delegate.source()
            override fun close() {
                closedFlag.set(true)
                delegate.close()
            }
        }
        val req = PATCH(
            url = "https://api.kiyomi.test/v1/item",
            headers = Headers.headersOf("X-Test", "1"),
            body = """{"update":true}""".toRequestBody("application/json".toMediaType())
        )
        assertEquals("PATCH", req.method)
        assertEquals("1", req.header("X-Test"))

        val resp = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()

        val dtoFromResponse: SampleDto = resp.networkParseAs()
        assertEquals("Kiyomi Chapter", dtoFromResponse.title)
        assertEquals(14, dtoFromResponse.count)
        assertTrue("parseAs must close Response body cleanly", closedFlag.get())

        val dtoFromStream: SampleDto = jsonPayload.byteInputStream().coreParseAs()
        assertEquals("Kiyomi Chapter", dtoFromStream.title)

        // Response.asJsoup() closes Response cleanly
        val htmlClosed = AtomicBoolean(false)
        val htmlBody = object : okhttp3.ResponseBody() {
            private val delegate = "<html><body><h1 id='h'>Kiyomi</h1></body></html>".toResponseBody("text/html".toMediaType())
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun source() = delegate.source()
            override fun close() {
                htmlClosed.set(true)
                delegate.close()
            }
        }
        val htmlResp = Response.Builder()
            .request(Request.Builder().url("https://kiyomi.test/page").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(htmlBody)
            .build()
        val doc = htmlResp.asJsoup()
        assertEquals("Kiyomi", doc.selectFirst("#h")?.text())
        assertTrue("asJsoup must close Response body cleanly", htmlClosed.get())
    }

    @Test
    fun `CloudflareInterceptor clears stale cf_clearance throws CloudflareBypassRequiredException and supports challenge solver`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetUrl = "https://cf-source.example/manga".toHttpUrl()
        val cookieJar = AndroidCookieJar()
        cookieJar.saveFromResponse(
            targetUrl,
            listOf(
                Cookie.Builder()
                    .name("cf_clearance")
                    .value("stale_token")
                    .domain("cf-source.example")
                    .path("/")
                    .expiresAt(System.currentTimeMillis() + 60_000L)
                    .build()
            )
        )
        assertEquals(1, cookieJar.get(targetUrl).size)

        // 1. Challenge without solver clears cf_clearance & throws CloudflareBypassRequiredException
        val cf403Client = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor(
                CloudflareInterceptor(
                    context = context,
                    cookieJar = cookieJar,
                    defaultUserAgentProvider = { KomaDns.USER_AGENT }
                )
            )
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Server", "cloudflare")
                    .header("cf-mitigated", "challenge")
                    .body("<html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val thrown = runCatching {
            cf403Client.newCall(Request.Builder().url(targetUrl).build()).execute()
        }.exceptionOrNull()
        assertTrue("Expected CloudflareBypassRequiredException, got $thrown", thrown is CloudflareBypassRequiredException)
        assertTrue("Stale cf_clearance must be cleared after failed challenge", cookieJar.get(targetUrl).isEmpty())

        val formattedCf = NetworkErrorFormatter.describe(thrown, targetUrl.host)
        assertTrue(formattedCf.contains("Cloudflare protection"))
        assertTrue(formattedCf.contains("403"))

        val formattedHttp = NetworkErrorFormatter.describe(HttpException(502), targetUrl.host)
        assertTrue(formattedHttp.contains("HTTP 502"))

        // 2. Challenge WITH solver saves fresh cf_clearance and retries request successfully
        val callCount = AtomicInteger(0)
        val solver = CloudflareChallengeSolver { _, req, jar ->
            jar.saveFromResponse(
                req.url,
                listOf(
                    Cookie.Builder()
                        .name("cf_clearance")
                        .value("fresh_solved_token")
                        .domain("cf-source.example")
                        .path("/")
                        .expiresAt(System.currentTimeMillis() + 60_000L)
                        .build()
                )
            )
            true
        }
        val solvedClient = OkHttpClient.Builder()
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor { KomaDns.USER_AGENT })
            .addInterceptor(
                CloudflareInterceptor(
                    context = context,
                    cookieJar = cookieJar,
                    defaultUserAgentProvider = { KomaDns.USER_AGENT },
                    challengeSolver = solver
                )
            )
            .addInterceptor { chain ->
                val attempt = callCount.incrementAndGet()
                val cookieHeader = chain.request().header("Cookie").orEmpty()
                if (attempt == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .header("Server", "cloudflare")
                        .body("<html>Checking browser _cf_chl_</html>".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    assertTrue(cookieHeader.contains("cf_clearance=fresh_solved_token"))
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        solvedClient.newCall(Request.Builder().url(targetUrl).build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(2, callCount.get())
    }

    @Test
    fun `ChildFirstPathClassLoader delegates core Mihon and OkHttp types parent-first while attempting child-first for extension classes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val emptyJar = File(context.cacheDir, "childfirst_test.jar").apply {
            writeText("")
        }
        val parent = HttpSource::class.java.classLoader!!

        val loader = ChildFirstPathClassLoader(emptyJar.absolutePath, null, parent)

        // Core host API classes MUST go parent-first so runtime types match host identically
        assertTrue(loader.shouldDelegateParentFirst(HttpSource::class.java.name))
        assertTrue(loader.shouldDelegateParentFirst(OkHttpClient::class.java.name))
        assertTrue(loader.shouldDelegateParentFirst(Injekt::class.java.name))
        assertTrue(loader.shouldDelegateParentFirst("keiyoushi.utils.JsonExtensionsKt"))
        assertTrue(loader.shouldDelegateParentFirst("tachiyomi.core.common.util.lang.JsonExtensionsKt"))
        assertTrue(loader.shouldDelegateParentFirst("nl.adaptivity.xmlutil.serialization.XML"))
        assertTrue(loader.shouldDelegateParentFirst("app.cash.quickjs.QuickJs"))
        assertSame(HttpSource::class.java, loader.loadClass(HttpSource::class.java.name))
        assertSame(OkHttpClient::class.java, loader.loadClass(OkHttpClient::class.java.name))
        assertSame(Injekt::class.java, loader.loadClass(Injekt::class.java.name))

        // Extension/custom class name MUST use child-first resolution before falling back to parent
        assertFalse(loader.shouldDelegateParentFirst("eu.kanade.tachiyomi.extension.en.custom.BundleSpecificHelper"))
        assertFalse(loader.shouldDelegateParentFirst("com.thirdparty.customparser.CustomHelper"))
        val customLookup = runCatching {
            loader.loadClass("eu.kanade.tachiyomi.extension.en.custom.BundleSpecificHelper")
        }.exceptionOrNull()
        assertTrue(customLookup is ClassNotFoundException)

        emptyJar.delete()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `tachiyomix extensionLib 1_6 compatibility and protobuf v2 plus legacy wireType 3 group skipping succeed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify PackageManager discovery extracts tachiyomix.extensionLib = "1.6" even when versionName is 1.0.5
        val pkgId = "eu.kanade.tachiyomi.extension.en.lib16sample"
        val appInfo = ApplicationInfo().apply {
            packageName = pkgId
            metaData = Bundle().apply {
                putString("tachiyomi.extension.class", ".Lib16Sample")
                putString("tachiyomix.extensionLib", "1.6")
            }
            sourceDir = ""
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkgId
            versionName = "1.0.5"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = 5L
            } else {
                versionCode = 5
            }
            applicationInfo = appInfo
            signatures = arrayOf(Signature("LIB16_CERT".toByteArray()))
            reqFeatures = arrayOf(FeatureInfo().apply { name = "tachiyomi.extension" })
        }
        Shadows.shadowOf(context.packageManager).installPackage(pkgInfo)

        val discovered = ExtensionSecurityManager.discoverInstalledExtensionPackages(context)
            .firstOrNull { it.packageName == pkgId }
        assertEquals("1.6", discovered?.libVersion)

        // Evaluate APK compatibility: 1.6 is supported, 1.8 triggers LIB_VERSION_INCOMPATIBLE
        val inspection16 = ApkInspectionResult(
            packageName = pkgId,
            versionCode = 5L,
            versionName = "1.0.5",
            extensionClass = ".Lib16Sample",
            isNsfw = false,
            hasReadme = false,
            signatureSha256 = "abcd1234",
            fileSha256 = "ef5678",
            isValidZipArchive = true,
            hasAndroidManifestEntry = true,
            hasDexBytecode = true,
            hasSigningBlockOrCert = true,
            isSignatureCryptographicallyVerified = true,
            packageManagerParsed = true,
            hasTachiyomiFeature = true,
            libVersion = "1.6"
        )
        val pkgMeta = ExtensionPackageEntity(pkgId = pkgId, name = "Lib16Sample", versionName = "1.0.5", versionCode = 5)
        val eval16 = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = pkgMeta,
            inspection = inspection16,
            isRepoTrusted = true,
            expectedFingerprint = "",
            isSignerTrusted = true,
            isInstalledOnDevice = true
        )
        assertEquals(ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE, eval16.status)
        assertEquals("1.6", eval16.libVersion)

        val eval18 = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = pkgMeta,
            inspection = inspection16.copy(libVersion = "1.8"),
            isRepoTrusted = true,
            expectedFingerprint = "",
            isSignerTrusted = true,
            isInstalledOnDevice = false
        )
        assertEquals(ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE, eval18.status)

        // 2. Verify ExtensionIndexParser decodes index_v2 with libVersion 1.6 & also skips wireType=3 START_GROUP cleanly in v1
        val v2Source = ByteArrayOutputStream().apply {
            write(pbVarintField(1, 55112233L))
            write(pbBytesField(2, "Keiyoushi Sample".toByteArray()))
            write(pbBytesField(3, "en".toByteArray()))
            write(pbBytesField(4, "https://sample.example".toByteArray()))
        }.toByteArray()
        val v2Artifacts = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "https://cdn.example/apk/sample.apk".toByteArray()))
            write(pbBytesField(2, "https://cdn.example/icon/sample.png".toByteArray()))
        }.toByteArray()
        val v2Ext = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "Mihon: Sample Source".toByteArray()))
            write(pbBytesField(2, pkgId.toByteArray()))
            write(pbBytesField(3, v2Artifacts))
            write(pbBytesField(4, "1.6".toByteArray()))
            write(pbVarintField(5, 18L))
            write(pbBytesField(6, "1.6.18".toByteArray()))
            write(pbVarintField(7, 3L)) // hasReadme + nsfw
            write(pbBytesField(8, v2Source))
        }.toByteArray()
        val v2Wrapper = pbBytesField(1, v2Ext)
        val v2Repo = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "Keiyoushi".toByteArray()))
            write(pbBytesField(2, "KEI".toByteArray()))
            write(pbBytesField(3, "a".repeat(64).toByteArray()))
            write(pbBytesField(101, v2Wrapper))
        }.toByteArray()

        val parsedV2 = ExtensionIndexParser.parseIndexBytes(
            repoId = 1L,
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            rawBytes = v2Repo
        )
        assertEquals("index.pb (v2)", parsedV2.formatLabel)
        assertEquals("Keiyoushi", parsedV2.repoName)
        assertEquals(1, parsedV2.packages.size)
        assertEquals("1.6", parsedV2.packages.first().libVersion)
        assertTrue(parsedV2.packages.first().isNsfw)
        assertTrue(parsedV2.packages.first().hasReadme)

        // Legacy index_v1 containing protobuf START_GROUP (wireType=3) & END_GROUP (wireType=4)
        val v1ExtWithGroup = ByteArrayOutputStream().apply {
            write(pbBytesField(1, "Tachiyomi: Group Legacy".toByteArray()))
            write(pbBytesField(2, "eu.kanade.tachiyomi.extension.en.grouplegacy".toByteArray()))
            write(pbBytesField(3, "tachiyomi-en.grouplegacy-v1.4.2.apk".toByteArray()))
            write(pbBytesField(4, "en".toByteArray()))
            write(pbVarintField(5, 2L))
            write(pbBytesField(6, "1.4.2".toByteArray()))
            // Unknown START_GROUP field 12 (wireType=3) -> varint inside -> END_GROUP field 12 (wireType=4)
            writeVarintTag(12, 3)
            write(pbVarintField(1, 99L))
            writeVarintTag(12, 4)
        }.toByteArray()
        val v1Root = pbBytesField(1, v1ExtWithGroup)
        val parsedV1 = ExtensionIndexParser.parseIndexBytes(
            repoId = 2L,
            indexUrl = "https://example.org/legacy/index.pb",
            rawBytes = v1Root
        )
        assertEquals("index.pb", parsedV1.formatLabel)
        assertEquals(1, parsedV1.packages.size)
        assertEquals("Group Legacy", parsedV1.packages.first().name)
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

    private fun ByteArrayOutputStream.writeVarintTag(field: Int, wireType: Int) {
        writeVarint(this, (field shl 3) or wireType)
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
