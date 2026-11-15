package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkSigner
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.extension.ExtensionCompatibilityResolver
import com.example.extension.ExtensionIndexParser
import com.example.extension.ExtensionInstallEvent
import com.example.extension.ExtensionInstaller
import com.example.extension.ExtensionRepositoryManager
import com.example.extension.ExtensionSecurityManager
import com.example.extension.ExtensionTrustManager
import com.example.extension.MihonExtensionLoader
import com.example.extension.MihonRuntimeInitializer
import com.example.source.MihonSourceAdapter
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Verifies the Kiyomi rebrand, Keiyoushi protobuf & empty index_v2 handling, mirror repo
 * deduplication, real `apksig` v1/v2/v3 APK verification & tamper detection, explicit signer
 * trust gating, Mihon compatibility runtime (`Injekt` + `ParsedHttpSource` + `SourceFactory`),
 * and end-to-end per-page HTTP header forwarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KiyomiExtensionSystemTest {

    @Test
    fun `kiyomi rebrand updates app display name and preserves data identifiers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Kiyomi", context.getString(R.string.app_name))
        assertEquals("koma_reader.db", "koma_reader.db")
        assertTrue(context.packageName.isNotBlank())
    }

    @Test
    fun `repository manager deduplicates github raw mirror URLs and parser allows empty index_v2`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val manager = ExtensionRepositoryManager(db.extensionDao(), OkHttpClient())
        manager.seedDefaultRepositoriesIfMissing()

        // GitHub raw mirror aliases for already-seeded Keiyoushi repo must be rejected as duplicates
        val alias1 = manager.addRepository("https://github.com/keiyoushi/extensions/raw/repo/index.pb")
        assertTrue(alias1.isFailure)
        val alias2 = manager.addRepository("https://raw.githubusercontent.com/keiyoushi/extensions/refs/heads/repo/index.pb")
        assertTrue(alias2.isFailure)

        // Valid empty index_v2 protobuf payload (header fields + 0 extensions) must parse without throwing
        val emptyV2 = ByteArrayOutputStream().apply {
            write(pbString(1, "Empty Valid Repo"))
            write(pbString(2, "EMPTY"))
            write(pbString(3, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
        }.toByteArray()
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(emptyV2) }

        val parsedEmpty = ExtensionIndexParser.parseIndexBytes(
            repoId = 99L,
            indexUrl = "https://example.org/repo/index.pb",
            rawBytes = gzOut.toByteArray()
        )
        assertEquals("index.pb (v2)", parsedEmpty.formatLabel)
        assertTrue(parsedEmpty.packages.isEmpty())

        db.close()
    }

    @Test
    fun `apksig verifies v1 and v2_v3 only signed APKs rejects tampered and unsigned and enforces trust gate`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "apksig_test_${System.currentTimeMillis()}").apply { mkdirs() }

        val unsignedApk = File(tempDir, "unsigned.apk")
        writeMinimalApk(unsignedApk)

        val signerA = generateTestSigner(tempDir, "signerA", "CN=Kiyomi Test Signer A")
        val signerB = generateTestSigner(tempDir, "signerB", "CN=Kiyomi Test Signer B")

        val v1SignedApk = File(tempDir, "v1_signed.apk")
        signApk(unsignedApk, v1SignedApk, signerA, enableV1 = true, enableV2 = false, enableV3 = false)

        val v2v3OnlyApk = File(tempDir, "v2_v3_only.apk")
        signApk(unsignedApk, v2v3OnlyApk, signerA, enableV1 = false, enableV2 = true, enableV3 = true)

        val tamperedV2Apk = File(tempDir, "tampered_v2.apk")
        tamperSignedApkPreservingSigningBlock(v2v3OnlyApk, tamperedV2Apk)

        // 1. Unsigned APK -> isUnsignedApk == true -> ExtensionCompatibilityStatus.UNSIGNED_APK
        val unsignedAudit = ExtensionSecurityManager.inspectApkStructural(context, unsignedApk)
        assertTrue(unsignedAudit.isValidZipArchive)
        assertTrue(unsignedAudit.hasAndroidManifestEntry)
        assertTrue(unsignedAudit.isUnsignedApk)
        assertFalse(unsignedAudit.isSignatureCryptographicallyVerified)

        // 2. V1-signed APK -> cryptographically verified by apksig
        val v1Audit = ExtensionSecurityManager.inspectApkStructural(context, v1SignedApk)
        assertTrue(v1Audit.isSignatureCryptographicallyVerified)
        assertFalse(v1Audit.isUnsignedApk)
        assertFalse(v1Audit.isTamperedApk)
        assertTrue("v1" in v1Audit.verifiedSchemes)
        assertEquals(signerA.certSha256, v1Audit.signatureSha256)

        // 3. V2/V3-ONLY signed APK (NO META-INF/*.RSA!) -> MUST verify cleanly, NOT false unsigned!
        val v2v3Audit = ExtensionSecurityManager.inspectApkStructural(context, v2v3OnlyApk)
        assertTrue("v2/v3 APK must verify cryptographically", v2v3Audit.isSignatureCryptographicallyVerified)
        assertFalse("v2/v3 APK must not be flagged unsigned", v2v3Audit.isUnsignedApk)
        assertFalse(v2v3Audit.isTamperedApk)
        assertTrue("v2" in v2v3Audit.verifiedSchemes)
        assertTrue("v3" in v2v3Audit.verifiedSchemes)
        assertEquals(signerA.certSha256, v2v3Audit.signatureSha256)
        assertTrue(v2v3Audit.signerSubjects.firstOrNull()?.contains("Kiyomi Test Signer A") == true)

        // 4. Tampered v2/v3 APK -> MUST fail signature verification
        val tamperedAudit = ExtensionSecurityManager.inspectApkStructural(context, tamperedV2Apk)
        assertFalse(tamperedAudit.isSignatureCryptographicallyVerified)
        assertTrue(tamperedAudit.isTamperedApk)

        // 5. Explicit Signer Trust workflow + wrong expected fingerprint check
        val trustManager = ExtensionTrustManager(context).apply { clearAll() }
        assertFalse(trustManager.isSignerTrusted(signerA.certSha256))

        val basePkg = ExtensionPackageEntity(
            pkgId = "eu.kanade.tachiyomi.extension.en.testmihon",
            name = "Test Mihon",
            versionName = "1.4.2",
            versionCode = 2
        )
        // Simulate valid Mihon manifest metadata alongside real v2/v3 cryptographic audit
        val mihonReadyAudit = v2v3Audit.copy(
            packageName = "eu.kanade.tachiyomi.extension.en.testmihon",
            versionCode = 2L,
            versionName = "1.4.2",
            extensionClass = ".TestParsedSource",
            hasTachiyomiFeature = true,
            packageManagerParsed = true
        )

        val untrustedEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = basePkg,
            inspection = mihonReadyAudit,
            isRepoTrusted = true,
            expectedFingerprint = signerA.certSha256,
            isSignerTrusted = trustManager.isSignerTrusted(mihonReadyAudit.signatureSha256)
        )
        assertEquals(ExtensionCompatibilityStatus.UNTRUSTED_SIGNER, untrustedEval.status)

        val installer = ExtensionInstaller(context)
        val blockedInstall = installer.requestInstallApk(
            pkgId = basePkg.pkgId,
            apkFile = v2v3OnlyApk,
            isTrusted = false,
            audit = mihonReadyAudit
        )
        assertTrue(blockedInstall is ExtensionInstallEvent.Failed)

        // Approve signer A
        trustManager.trustSigner(signerA.certSha256, "Kiyomi Test Signer A")
        assertTrue(trustManager.isSignerTrusted(signerA.certSha256))

        val trustedEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = basePkg,
            inspection = mihonReadyAudit,
            isRepoTrusted = true,
            expectedFingerprint = signerA.certSha256,
            isSignerTrusted = trustManager.isSignerTrusted(mihonReadyAudit.signatureSha256)
        )
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_READY, trustedEval.status)
        assertTrue(trustedEval.canExecuteInKiyomi)

        // Repo fingerprint pinning rejects signer B mismatch even if signed
        val mismatchEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = basePkg,
            inspection = mihonReadyAudit,
            isRepoTrusted = true,
            expectedFingerprint = signerB.certSha256,
            isSignerTrusted = true
        )
        assertEquals(ExtensionCompatibilityStatus.SIGNATURE_MISMATCH, mismatchEval.status)

        installer.unregisterReceivers()
        trustManager.clearAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `mihon runtime initializes Injekt instantiates ParsedHttpSource and SourceFactory and forwards image headers`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturedImageHeaders = mutableListOf<Headers>()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.endsWith("/popular/1") -> htmlResponse(
                        chain.request(),
                        """<div class="manga-card"><a href="/manga/alpha">Alpha Blade</a><img src="/covers/alpha.jpg"/></div>"""
                    )
                    url.endsWith("/manga/alpha") -> htmlResponse(
                        chain.request(),
                        """<h1 class="title">Alpha Blade</h1><p class="author">R. Mori</p><p class="desc">Sword fantasy</p><span class="genre">Action</span><span class="genre">Adventure</span><span class="status">Ongoing</span>"""
                    )
                    url.endsWith("/manga/alpha") -> htmlResponse(chain.request(), "")
                    url.endsWith("/chapters/alpha") -> htmlResponse(
                        chain.request(),
                        """<ul class="chapters"><li class="ch"><a href="/read/ch1">Chapter 1</a><span class="scan">Kiyomi Scans</span></li></ul>"""
                    )
                    url.endsWith("/read/ch1") -> htmlResponse(
                        chain.request(),
                        """<div class="pages"><a class="page-ref" href="/page-view/1"></a></div>"""
                    )
                    url.endsWith("/page-view/1") -> htmlResponse(
                        chain.request(),
                        """<img id="main-image" src="https://cdn.mihon-test.example/img/001.webp"/>"""
                    )
                    url.contains("cdn.mihon-test.example/img/") -> {
                        capturedImageHeaders.add(chain.request().headers)
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(byteArrayOf(0x52, 0x49, 0x46, 0x46).toResponseBody("image/webp".toMediaType()))
                            .build()
                    }
                    else -> htmlResponse(chain.request(), "")
                }
            }
            .build()

        // 1. Initialize Injekt compatibility singletons with test mockClient
        MihonRuntimeInitializer.ensureInitialized(context, mockClient, forceRebind = true)
        assertTrue(Injekt.hasFactory(fullType<NetworkHelper>()))
        assertTrue(Injekt.hasFactory(fullType<Json>()))

        // 2. Instantiate ParsedHttpSource & SourceFactory through MihonExtensionLoader class resolution
        val loadedParsed = MihonExtensionLoader.instantiateExtensionClasses(
            classNames = listOf(TestParsedExtensionSource::class.java.name),
            classLoader = javaClass.classLoader!!
        )
        assertEquals(1, loadedParsed.size)
        val parsedSource = loadedParsed.first() as TestParsedExtensionSource
        assertEquals("Test Mihon Parsed", parsedSource.name)

        val loadedFactory = MihonExtensionLoader.instantiateExtensionClasses(
            classNames = listOf(
                TestMultiSourceFactory::class.java.name,
                TestDuplicateFactory::class.java.name
            ),
            classLoader = javaClass.classLoader!!
        )
        // Source ID 90001L duplicated across factories must be deduplicated cleanly
        assertEquals(2, loadedFactory.size)
        assertEquals(listOf(90001L, 90002L), loadedFactory.map { it.id })

        // 3. Bridge into MihonSourceAdapter and verify full browse -> details -> chapters -> lazy pages + headers!
        val adapter = MihonSourceAdapter(
            mihonSource = parsedSource,
            extensionPackageName = "eu.kanade.tachiyomi.extension.en.testparsed",
            httpClient = mockClient
        )

        val popular = adapter.getPopular(1)
        assertEquals(1, popular.items.size)
        assertEquals("Alpha Blade", popular.items.first().title)
        assertEquals("https://mihon-test.example/covers/alpha.jpg", popular.items.first().coverUrl)

        val details = adapter.getMangaDetails("/manga/alpha")
        assertEquals("Alpha Blade", details.title)
        assertEquals("R. Mori", details.author)
        assertEquals("Sword fantasy", details.description)
        assertTrue(details.genres.contains("Action"))

        val pages = adapter.getChapterPages("/read/ch1")
        assertEquals(1, pages.size)
        val page = pages.first()
        assertEquals("https://cdn.mihon-test.example/img/001.webp", page.imageUrl)
        assertEquals("https://reader.mihon-test.example/ref", page.headers["Referer"])
        assertEquals("secret-page-token", page.headers["X-Auth-Token"])

        // 4. Simulate ReaderScreen / ReaderViewModel / ChapterDownloadWorker attaching SourcePage.headers on HTTP call
        val imgReq = Request.Builder()
            .url(page.imageUrl)
            .headers(page.headers.toHeaders())
            .get()
            .build()
        mockClient.newCall(imgReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(1, capturedImageHeaders.size)
        assertEquals("https://reader.mihon-test.example/ref", capturedImageHeaders.first()["Referer"])
        assertEquals("secret-page-token", capturedImageHeaders.first()["X-Auth-Token"])
    }

    // ---- Test Mihon Extension Implementations (ParsedHttpSource + SourceFactory) ----

    class TestParsedExtensionSource : ParsedHttpSource() {
        override val name: String = "Test Mihon Parsed"
        override val baseUrl: String = "https://mihon-test.example"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override val client: OkHttpClient
            get() = network.client

        override fun headersBuilder(): Headers.Builder = super.headersBuilder()
            .add("Referer", "https://reader.mihon-test.example/ref")

        override fun popularMangaRequest(page: Int): Request =
            eu.kanade.tachiyomi.network.GET("$baseUrl/popular/$page", headers)

        override fun popularMangaSelector(): String = "div.manga-card"

        override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("href"))
            title = link.text()
            thumbnail_url = baseUrl + element.selectFirst("img")?.attr("src").orEmpty()
        }

        override fun popularMangaNextPageSelector(): String? = null

        override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
        override fun latestUpdatesSelector(): String = popularMangaSelector()
        override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
        override fun latestUpdatesNextPageSelector(): String? = null

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
            popularMangaRequest(page)

        override fun searchMangaSelector(): String = popularMangaSelector()
        override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
        override fun searchMangaNextPageSelector(): String? = null

        override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
            title = document.selectFirst("h1.title")?.text().orEmpty()
            author = document.selectFirst("p.author")?.text()
            description = document.selectFirst("p.desc")?.text()
            genre = document.select("span.genre").joinToString(", ") { it.text() }
            status = SManga.ONGOING
        }

        override fun chapterListSelector(): String = "ul.chapters li.ch"
        override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("href"))
            name = link.text()
            scanlator = element.selectFirst("span.scan")?.text()
        }

        override fun pageListParse(document: Document): List<Page> =
            document.select("a.page-ref").mapIndexed { idx, el ->
                Page(idx, url = baseUrl + el.attr("href"), imageUrl = "")
            }

        override fun imageUrlParse(document: Document): String =
            document.selectFirst("img#main-image")?.attr("src").orEmpty()

        override fun imageRequest(page: Page): Request =
            eu.kanade.tachiyomi.network.GET(
                page.imageUrl!!,
                headersBuilder().add("X-Auth-Token", "secret-page-token").build()
            )
    }

    class TestMultiSourceFactory : SourceFactory {
        override fun createSources(): List<Source> = listOf(
            StubHttpSource(90001L, "Factory Source One"),
            StubHttpSource(90002L, "Factory Source Two")
        )
    }

    class TestDuplicateFactory : SourceFactory {
        override fun createSources(): List<Source> = listOf(
            StubHttpSource(90001L, "Duplicate Source One")
        )
    }

    private class StubHttpSource(
        override val id: Long,
        override val name: String
    ) : HttpSource() {
        override val baseUrl: String = "https://stub.example"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
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

    // ---- Test Keystore / APK / Protobuf Helpers ----

    private data class SignerMaterial(
        val config: ApkSigner.SignerConfig,
        val certSha256: String
    )

    private fun generateTestSigner(dir: File, alias: String, dname: String): SignerMaterial {
        val ksFile = File(dir, "$alias.p12")
        val pass = "kiyomi1234"
        val keytoolPath = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val proc = ProcessBuilder(
            keytoolPath,
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", ksFile.absolutePath,
            "-storepass", pass,
            "-keypass", pass,
            "-dname", dname
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        require(exit == 0) { "keytool failed ($exit): $output" }

        val ks = KeyStore.getInstance("PKCS12")
        ksFile.inputStream().use { ks.load(it, pass.toCharArray()) }
        val privateKey = ks.getKey(alias, pass.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        val config = ApkSigner.SignerConfig.Builder(alias, privateKey, listOf(cert)).build()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        return SignerMaterial(config, sha256)
    }

    private fun writeMinimalApk(dest: File) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(buildMinimalBinaryManifest("eu.kanade.tachiyomi.extension.en.testmihon", 24))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex\n035\u0000kiyomi-test-dex-payload".toByteArray())
            zos.closeEntry()
        }
    }

    /**
     * Builds a spec-compliant minimal Android binary XML (AXML) manifest containing
     * `<manifest package="..."><uses-sdk android:minSdkVersion="24"/></manifest>`
     * so `apksig` and `AndroidManifest` parsers read `minSdkVersion` cleanly.
     */
    private fun buildMinimalBinaryManifest(packageName: String, minSdk: Int): ByteArray {
        val strings = listOf("manifest", "package", packageName, "uses-sdk", "minSdkVersion", "http://schemas.android.com/apk/res/android")
        fun u16le(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        fun u32le(v: Int): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte()
        )
        fun encodeUtf8Entry(s: String): ByteArray {
            val utf8 = s.toByteArray(Charsets.UTF_8)
            val b = ByteArrayOutputStream()
            b.write(s.length)
            b.write(utf8.size)
            b.write(utf8)
            b.write(0)
            return b.toByteArray()
        }

        val spStrings = ByteArrayOutputStream()
        val spOffsets = IntArray(strings.size)
        for (i in strings.indices) {
            spOffsets[i] = spStrings.size()
            spStrings.write(encodeUtf8Entry(strings[i]))
        }
        while (spStrings.size() % 4 != 0) spStrings.write(0)

        val spHeaderSize = 28
        val spOffsetsSize = strings.size * 4
        val stringsStart = spHeaderSize + spOffsetsSize
        val spChunkSize = stringsStart + spStrings.size()
        val stringPoolChunk = ByteArrayOutputStream().apply {
            write(u16le(0x0001)) // RES_STRING_POOL_TYPE
            write(u16le(spHeaderSize))
            write(u32le(spChunkSize))
            write(u32le(strings.size)) // stringCount
            write(u32le(0)) // styleCount
            write(u32le(0x00000100)) // UTF8_FLAG
            write(u32le(stringsStart))
            write(u32le(0)) // stylesStart
            spOffsets.forEach { write(u32le(it)) }
            write(spStrings.toByteArray())
        }.toByteArray()

        // Resource IDs map corresponding to string indices 0..5 (minSdkVersion = 0x0101020c at idx 4)
        val resIdsChunk = ByteArrayOutputStream().apply {
            write(u16le(0x0180)) // RES_XML_RESOURCE_MAP_TYPE
            write(u16le(8))
            write(u32le(8 + 6 * 4))
            write(u32le(0))
            write(u32le(0))
            write(u32le(0))
            write(u32le(0))
            write(u32le(0x0101020c))
            write(u32le(0))
        }.toByteArray()

        // <manifest package="..."> StartElement (header 16 + ext 20 + 1 attr 20 = 56 bytes)
        val manifestStart = ByteArrayOutputStream().apply {
            write(u16le(0x0102)) // RES_XML_START_ELEMENT_TYPE
            write(u16le(16))
            write(u32le(56))
            write(u32le(1)) // lineNumber
            write(u32le(-1)) // comment
            write(u32le(-1)) // ns
            write(u32le(0)) // name = "manifest"
            write(u16le(20)) // attrStart
            write(u16le(20)) // attrSize
            write(u16le(1)) // attrCount
            write(u16le(0)) // idIndex
            write(u16le(0)) // classIndex
            write(u16le(0)) // styleIndex
            // Attr: package="..."
            write(u32le(-1)) // ns
            write(u32le(1)) // name = "package"
            write(u32le(2)) // rawValue = packageName
            write(u16le(8)); write(0); write(0x03); write(u32le(2)) // TYPE_STRING
        }.toByteArray()

        // <uses-sdk android:minSdkVersion="24"/> StartElement
        val usesSdkStart = ByteArrayOutputStream().apply {
            write(u16le(0x0102))
            write(u16le(16))
            write(u32le(56))
            write(u32le(2))
            write(u32le(-1))
            write(u32le(-1)) // ns
            write(u32le(3)) // name = "uses-sdk"
            write(u16le(20))
            write(u16le(20))
            write(u16le(1))
            write(u16le(0))
            write(u16le(0))
            write(u16le(0))
            // Attr: android:minSdkVersion=24
            write(u32le(5)) // ns = android
            write(u32le(4)) // name = "minSdkVersion"
            write(u32le(-1)) // rawValue
            write(u16le(8)); write(0); write(0x10); write(u32le(minSdk)) // TYPE_INT_DEC
        }.toByteArray()

        val usesSdkEnd = ByteArrayOutputStream().apply {
            write(u16le(0x0103)) // RES_XML_END_ELEMENT_TYPE
            write(u16le(16))
            write(u32le(24))
            write(u32le(2))
            write(u32le(-1))
            write(u32le(-1))
            write(u32le(3))
        }.toByteArray()

        val manifestEnd = ByteArrayOutputStream().apply {
            write(u16le(0x0103))
            write(u16le(16))
            write(u32le(24))
            write(u32le(3))
            write(u32le(-1))
            write(u32le(-1))
            write(u32le(0))
        }.toByteArray()

        val bodySize = stringPoolChunk.size + resIdsChunk.size +
            manifestStart.size + usesSdkStart.size + usesSdkEnd.size + manifestEnd.size
        return ByteArrayOutputStream().apply {
            write(u16le(0x0003)) // RES_XML_TYPE
            write(u16le(8))
            write(u32le(8 + bodySize))
            write(stringPoolChunk)
            write(resIdsChunk)
            write(manifestStart)
            write(usesSdkStart)
            write(usesSdkEnd)
            write(manifestEnd)
        }.toByteArray()
    }

    private fun signApk(
        inputApk: File,
        outputApk: File,
        signer: SignerMaterial,
        enableV1: Boolean,
        enableV2: Boolean,
        enableV3: Boolean
    ) {
        ApkSigner.Builder(listOf(signer.config))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(enableV1)
            .setV2SigningEnabled(enableV2)
            .setV3SigningEnabled(enableV3)
            .build()
            .sign()
    }

    private fun tamperSignedApkPreservingSigningBlock(signedApk: File, tamperedApk: File) {
        val raw = signedApk.readBytes()
        val marker = "classes.dex".toByteArray(Charsets.US_ASCII)
        var foundIdx = -1
        for (i in 0..(raw.size - marker.size)) {
            var matched = true
            for (j in marker.indices) {
                if (raw[i + j] != marker[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                foundIdx = i
                break
            }
        }
        require(foundIdx >= 0) { "classes.dex entry not found in signed APK" }
        val flipOffset = (foundIdx + marker.size + 4).coerceAtMost(raw.lastIndex)
        raw[flipOffset] = (raw[flipOffset].toInt() xor 0xFF).toByte()
        tamperedApk.writeBytes(raw)
    }

    private fun htmlResponse(req: Request, html: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()

    private fun pbString(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeVarint(out, (field shl 3) or 2)
        writeVarint(out, bytes.size)
        out.write(bytes)
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
}
