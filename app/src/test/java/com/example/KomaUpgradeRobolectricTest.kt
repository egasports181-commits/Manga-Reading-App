package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.ExtensionRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.ApkIncompatibilityCategory
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionLifecycleState
import com.example.domain.model.TapZoneLayout
import com.example.extension.*
import com.example.network.DnsMode
import com.example.network.KomaDns
import com.example.network.KomaTlsProvider
import com.example.network.ModernTlsSocketFactory
import com.example.network.NetworkErrorFormatter
import com.example.network.TlsFailureContext
import com.example.source.MangaDexSourceAdapter
import com.example.source.SourceRegistry
import com.example.ui.screens.reader.TapZoneAction
import com.example.ui.screens.reader.resolveReaderTapAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KomaUpgradeRobolectricTest {

    // ---- Protobuf wire-format encoder helpers ----
    private fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                break
            } else {
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }
        return out.toByteArray()
    }

    private fun fieldVarint(fieldNum: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNum shl 3) or 0).toLong()))
        out.write(encodeVarint(value))
        return out.toByteArray()
    }

    private fun fieldBytes(fieldNum: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNum shl 3) or 2).toLong()))
        out.write(encodeVarint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fieldString(fieldNum: Int, text: String): ByteArray =
        fieldBytes(fieldNum, text.toByteArray(Charsets.UTF_8))

    private fun buildSyntheticProtobufIndex(): ByteArray {
        val sourcePayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "Example Scan"))
            write(fieldString(2, "en"))
            write(fieldVarint(3, 424242L))
            write(fieldString(4, "https://examplescan.example"))
        }.toByteArray()

        val extPayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "Tachiyomi: Example Scan"))
            write(fieldString(2, "eu.kanade.tachiyomi.extension.en.examplescan"))
            write(fieldString(3, "tachiyomi-en.examplescan-v1.4.8.apk"))
            write(fieldString(4, "en"))
            write(fieldVarint(5, 8L))
            write(fieldString(6, "1.4.8"))
            write(fieldVarint(7, 0L))
            write(fieldBytes(8, sourcePayload))
            write(fieldVarint(9, 1L))
        }.toByteArray()

        return fieldBytes(1, extPayload)
    }

    @Test
    fun `protobuf index parser decodes raw and gzipped index pb payloads`() {
        val rawPb = buildSyntheticProtobufIndex()
        val parsedRaw = ExtensionIndexParser.parseIndexBytes(
            repoId = 1L,
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            rawBytes = rawPb
        )
        assertEquals("index.pb", parsedRaw.formatLabel)
        assertEquals(1, parsedRaw.packages.size)
        val pkg = parsedRaw.packages.first()
        assertEquals("eu.kanade.tachiyomi.extension.en.examplescan", pkg.pkgId)
        assertEquals("Example Scan", pkg.name)
        assertEquals("en", pkg.language)
        assertEquals("1.4.8", pkg.versionName)
        assertEquals(8, pkg.versionCode)
        assertEquals("1.4", pkg.libVersion)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-en.examplescan-v1.4.8.apk",
            pkg.apkUrl
        )
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/icon/eu.kanade.tachiyomi.extension.en.examplescan.png",
            pkg.iconUrl
        )
        assertTrue(pkg.sourcesJson.contains("Example Scan"))
        assertTrue(pkg.sourcesJson.contains("https://examplescan.example"))
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED.name, pkg.compatibilityStatus)

        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(rawPb) }
        val parsedGz = ExtensionIndexParser.parseIndexBytes(
            repoId = 1L,
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            rawBytes = gzOut.toByteArray()
        )
        assertEquals(1, parsedGz.packages.size)
        assertEquals(pkg.pkgId, parsedGz.packages.first().pkgId)
    }

    @Test
    fun `json and repo companion metadata parsing handles index min json koma json and invalid payloads`() {
        val minJson = """
            [
              {
                "name": "Tachiyomi: Suwayomi Test",
                "pkg": "eu.kanade.tachiyomi.extension.en.suwayomitest",
                "apk": "tachiyomi-en.suwayomitest-v1.3.2.apk",
                "lang": "en",
                "code": 12,
                "version": "1.3.2",
                "nsfw": 1,
                "sources": [
                  {"name": "Suwayomi Test", "lang": "en", "id": "998877", "baseUrl": "https://test.example"}
                ]
              }
            ]
        """.trimIndent()
        val parsedMin = ExtensionIndexParser.parseIndexBytes(
            repoId = 2L,
            indexUrl = "https://raw.githubusercontent.com/suwayomi/tachiyomi-extension/repo/index.min.json",
            rawBytes = minJson.toByteArray()
        )
        assertEquals("index.min.json", parsedMin.formatLabel)
        assertEquals(1, parsedMin.packages.size)
        assertTrue(parsedMin.packages.first().isNsfw)
        assertEquals("1.3", parsedMin.packages.first().libVersion)

        val komaJson = """
            {
              "name": "Koma Community",
              "extensions": [
                {
                  "id": "koma.sample.api",
                  "name": "Sample JSON Adapter",
                  "versionCode": 2,
                  "versionName": "2.0.0",
                  "lang": "en",
                  "adapter": {
                    "baseUrl": "https://api.example.com",
                    "popularEndpoint": "/popular"
                  }
                }
              ]
            }
        """.trimIndent()
        val parsedKoma = ExtensionIndexParser.parseIndexBytes(
            repoId = 3L,
            indexUrl = "https://example.com/koma.json",
            rawBytes = komaJson.toByteArray()
        )
        assertEquals("koma_json_v1", parsedKoma.formatLabel)
        assertEquals(ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON.name, parsedKoma.packages.first().compatibilityStatus)

        val repoJson = """
            {
              "meta": {
                "name": "Keiyoushi",
                "displayName": "Keiyoushi Extensions",
                "website": "https://keiyoushi.github.io",
                "signingKeyFingerprint": "abcdef123456"
              }
            }
        """.trimIndent()
        val meta = ExtensionIndexParser.parseRepoJson(repoJson)
        assertNotNull(meta)
        assertEquals("Keiyoushi Extensions", meta?.displayName)
        assertEquals("https://keiyoushi.github.io", meta?.website)
        assertEquals("abcdef123456", meta?.signingKeyFingerprint)

        var threw = false
        try {
            ExtensionIndexParser.parseIndexBytes(4L, "https://example.com/bad", byteArrayOf())
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `repository manager seeds 8 default repos without duplicates and supports export import roundtrip`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val manager = ExtensionRepositoryManager(db.extensionDao(), OkHttpClient())

        assertEquals(8, BuiltInRepositories.ALL.size)
        val insertedFirst = manager.seedDefaultRepositoriesIfMissing(forceRestore = false)
        assertEquals(8, insertedFirst)

        val insertedSecond = manager.seedDefaultRepositoriesIfMissing(forceRestore = false)
        assertEquals(0, insertedSecond)

        val repos = db.extensionDao().getReposOnce()
        assertEquals(8, repos.size)
        assertTrue(repos.any { it.indexUrl == "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb" })
        assertTrue(repos.any { it.indexUrl == "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/index.pb" })

        val cleartext = manager.addRepository("http://insecure.example.com/index.pb")
        assertTrue(cleartext.isFailure)

        val dup = manager.addRepository("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb/")
        assertTrue(dup.isFailure)

        val exportedJson = manager.exportRepositoriesJson()
        val db2 = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val manager2 = ExtensionRepositoryManager(db2.extensionDao(), OkHttpClient())
        val importedCount = manager2.importRepositoriesJson(exportedJson).getOrThrow()
        assertEquals(8, importedCount)
        assertEquals(0, manager2.importRepositoriesJson(exportedJson).getOrThrow())

        db.close()
        db2.close()
    }

    @Test
    fun `compatibility resolver enforces libVersion 1_2 to 1_5 trust and signature rules`() {
        val okMihon = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_PROTOBUF_INDEX,
            versionName = "1.4.8",
            isRepoTrusted = true,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED, okMihon.status)
        assertEquals("1.4", okMihon.libVersion)
        assertFalse(okMihon.canExecuteInKoma)

        val v16Ok = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.6.6",
            isRepoTrusted = true,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED, v16Ok.status)
        assertEquals("1.6", v16Ok.libVersion)
        assertFalse(v16Ok.canExecuteInKoma)

        val tooNew = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.7.0",
            isRepoTrusted = true,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE, tooNew.status)

        val tooOld = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.0.5",
            isRepoTrusted = true,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE, tooOld.status)

        val sigMismatch = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.4.2",
            isRepoTrusted = true,
            signatureMatch = false
        )
        assertEquals(ExtensionCompatibilityStatus.SIGNATURE_MISMATCH, sigMismatch.status)

        val untrusted = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.4.2",
            isRepoTrusted = false,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.UNTRUSTED_REPO, untrusted.status)

        val komaOk = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.KOMA_JSON_ADAPTER,
            versionName = "1.0.0",
            isRepoTrusted = true,
            signatureMatch = true
        )
        assertEquals(ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON, komaOk.status)
        assertTrue(komaOk.canExecuteInKoma)
    }

    @Test
    fun `room migration 1 to 2 preserves existing user data and creates v2 schema tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "koma_migration_test_${System.currentTimeMillis()}.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS manga (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        mangaKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        altTitle TEXT NOT NULL,
                        author TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        description TEXT NOT NULL,
                        genres TEXT NOT NULL,
                        status TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        language TEXT NOT NULL,
                        contentRating TEXT NOT NULL,
                        releaseYear INTEGER,
                        inLibrary INTEGER NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL,
                        latestChapterNumber REAL NOT NULL,
                        latestChapterName TEXT NOT NULL,
                        latestUpdateAt INTEGER NOT NULL,
                        isBookmarked INTEGER NOT NULL,
                        totalReadingTimeMs INTEGER NOT NULL,
                        unreadCount INTEGER NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        isDemoData INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_sourceId ON manga(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_inLibrary ON manga(inLibrary)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_lastReadAt ON manga(lastReadAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS manga_category_cross_ref (
                        mangaId TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        PRIMARY KEY(mangaId, categoryId),
                        FOREIGN KEY(mangaId) REFERENCES manga(id) ON DELETE CASCADE,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manga_category_cross_ref_categoryId ON manga_category_cross_ref(categoryId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapters (
                        id TEXT NOT NULL PRIMARY KEY,
                        mangaId TEXT NOT NULL,
                        chapterKey TEXT NOT NULL,
                        name TEXT NOT NULL,
                        chapterNumber REAL NOT NULL,
                        volumeNumber TEXT NOT NULL,
                        scanlator TEXT NOT NULL,
                        uploadedAt INTEGER NOT NULL,
                        isRead INTEGER NOT NULL,
                        isBookmarked INTEGER NOT NULL,
                        lastPageRead INTEGER NOT NULL,
                        totalPages INTEGER NOT NULL,
                        isDownloaded INTEGER NOT NULL,
                        localDirPath TEXT NOT NULL,
                        downloadedAt INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        FOREIGN KEY(mangaId) REFERENCES manga(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_mangaId ON chapters(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_isDownloaded ON chapters(isDownloaded)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_isRead ON chapters(isRead)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mangaId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        mangaTitle TEXT NOT NULL,
                        chapterName TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        readAt INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        pagesRead INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_mangaId ON reading_history(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_readAt ON reading_history(readAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_repos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        indexUrl TEXT NOT NULL,
                        signingKeyFingerprint TEXT NOT NULL,
                        isTrusted INTEGER NOT NULL,
                        lastRefreshedAt INTEGER NOT NULL,
                        lastError TEXT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_packages (
                        pkgId TEXT NOT NULL PRIMARY KEY,
                        repoId INTEGER,
                        name TEXT NOT NULL,
                        versionName TEXT NOT NULL,
                        versionCode INTEGER NOT NULL,
                        author TEXT NOT NULL,
                        description TEXT NOT NULL,
                        language TEXT NOT NULL,
                        iconUrl TEXT NOT NULL,
                        format TEXT NOT NULL,
                        adapterJson TEXT NOT NULL,
                        isInstalled INTEGER NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        installedVersionCode INTEGER NOT NULL,
                        hasUpdate INTEGER NOT NULL,
                        latestVersionCode INTEGER NOT NULL,
                        latestVersionName TEXT NOT NULL,
                        signatureHash TEXT NOT NULL,
                        popularity INTEGER NOT NULL,
                        permissionsSummary TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extension_packages_repoId ON extension_packages(repoId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extension_packages_isInstalled ON extension_packages(isInstalled)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_tasks (
                        chapterId TEXT NOT NULL PRIMARY KEY,
                        mangaId TEXT NOT NULL,
                        mangaTitle TEXT NOT NULL,
                        chapterName TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        chapterKey TEXT NOT NULL,
                        status TEXT NOT NULL,
                        progress REAL NOT NULL,
                        totalPages INTEGER NOT NULL,
                        downloadedPages INTEGER NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        bytesDownloaded INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_mangaId ON download_tasks(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_status ON download_tasks(status)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS update_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mangaId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        chapterName TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        mangaTitle TEXT NOT NULL,
                        coverUrl TEXT NOT NULL,
                        detectedAt INTEGER NOT NULL,
                        isSeen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_update_entries_mangaId ON update_entries(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_update_entries_detectedAt ON update_entries(detectedAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracking_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mangaId TEXT NOT NULL,
                        serviceId TEXT NOT NULL,
                        remoteMediaId TEXT NOT NULL,
                        remoteTitle TEXT NOT NULL,
                        status TEXT NOT NULL,
                        score REAL NOT NULL,
                        chaptersRead INTEGER NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        lastSyncedAt INTEGER NOT NULL,
                        syncState TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracking_entries_mangaId ON tracking_entries(mangaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracking_entries_serviceId ON tracking_entries(serviceId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        query TEXT NOT NULL,
                        sourceFilter TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isSaved INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS source_preferences (
                        sourceId TEXT NOT NULL,
                        prefKey TEXT NOT NULL,
                        prefValue TEXT NOT NULL,
                        PRIMARY KEY(sourceId, prefKey)
                    )
                    """.trimIndent()
                )

                // Seed v1 user records
                db.execSQL("INSERT INTO categories (id, name, sortOrder, isDefault) VALUES (1, 'Favorites', 0, 1)")
                db.execSQL(
                    """
                    INSERT INTO manga VALUES (
                        'mangadex::v1id', 'mangadex', 'MangaDex', 'v1id', 'Preserved Manga', '', 'Auth', '', 'Desc', 'Action',
                        'ONGOING', '', 'en', 'safe', 2024, 1, 1000, 2000, 5.0, 'Ch. 5', 3000, 1, 5000, 1, 5, 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO chapters VALUES (
                        'mangadex::v1id::ch1', 'mangadex::v1id', 'ch1', 'Chapter 1', 1.0, '1', 'Group', 1000, 0, 0, 4, 20, 0, '', 0, 0
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO extension_repos VALUES (1, 'CustomV1', 'https://example.com/v1.json', 'fp1', 1, 500, NULL)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, KomaDatabase::class.java, dbName)
            .addMigrations(KomaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val preservedManga = migratedDb.mangaDao().getMangaById("mangadex::v1id")
        assertNotNull(preservedManga)
        assertEquals("Preserved Manga", preservedManga?.title)

        val cats = migratedDb.categoryDao().getCategoriesOnce()
        assertEquals(1, cats.size)
        assertEquals("Favorites", cats.first().name)
        assertEquals("folder", cats.first().iconName)

        val chapter = migratedDb.chapterDao().getChapterById("mangadex::v1id::ch1")
        assertNotNull(chapter)
        assertEquals("[]", chapter?.bookmarkedPagesJson)

        val repos = migratedDb.extensionDao().getReposOnce()
        assertEquals(1, repos.size)
        assertEquals("CustomV1", repos.first().name)
        assertTrue(repos.first().isEnabled)

        migratedDb.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `reader tap zone mapping and page bookmarks work in LTR and RTL`() = runTest {
        assertEquals(TapZoneAction.PREV, resolveReaderTapAction(TapZoneLayout.SIDES, 0.1f, 0.5f, isRtl = false))
        assertEquals(TapZoneAction.NEXT, resolveReaderTapAction(TapZoneLayout.SIDES, 0.9f, 0.5f, isRtl = false))
        assertEquals(TapZoneAction.TOGGLE_HUD, resolveReaderTapAction(TapZoneLayout.SIDES, 0.5f, 0.5f, isRtl = false))

        assertEquals(TapZoneAction.NEXT, resolveReaderTapAction(TapZoneLayout.SIDES, 0.1f, 0.5f, isRtl = true))
        assertEquals(TapZoneAction.PREV, resolveReaderTapAction(TapZoneLayout.SIDES, 0.9f, 0.5f, isRtl = true))
        assertEquals(TapZoneAction.TOGGLE_HUD, resolveReaderTapAction(TapZoneLayout.SIDES, 0.5f, 0.5f, isRtl = true))

        assertEquals(TapZoneAction.TOGGLE_HUD, resolveReaderTapAction(TapZoneLayout.KINDLISH, 0.5f, 0.1f, isRtl = false))
        assertEquals(TapZoneAction.PREV, resolveReaderTapAction(TapZoneLayout.KINDLISH, 0.1f, 0.5f, isRtl = false))
        assertEquals(TapZoneAction.NEXT, resolveReaderTapAction(TapZoneLayout.KINDLISH, 0.5f, 0.5f, isRtl = false))

        assertEquals(TapZoneAction.PREV, resolveReaderTapAction(TapZoneLayout.EDGES, 0.1f, 0.5f, isRtl = false))
        assertEquals(TapZoneAction.TOGGLE_HUD, resolveReaderTapAction(TapZoneLayout.EDGES, 0.22f, 0.5f, isRtl = false))

        assertEquals(TapZoneAction.PREV, resolveReaderTapAction(TapZoneLayout.L_SHAPE, 0.4f, 0.1f, isRtl = false))
        assertEquals(TapZoneAction.NEXT, resolveReaderTapAction(TapZoneLayout.L_SHAPE, 0.85f, 0.85f, isRtl = false))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = AppPreferencesRepository(context)
        val registry = SourceRegistry(context, OkHttpClient(), prefsRepo, db.extensionDao(), db.sourcePreferenceDao())
        val mangaRepo = MangaRepository(
            db.mangaDao(),
            db.categoryDao(),
            db.chapterDao(),
            db.historyDao(),
            db.updateDao(),
            db.searchHistoryDao(),
            registry,
            prefsRepo
        )
        db.mangaDao().upsertManga(
            MangaEntity(id = "m1", sourceId = "mangadex", sourceName = "MangaDex", mangaKey = "1", title = "Test")
        )
        db.chapterDao().upsertChapter(
            ChapterEntity(id = "m1::c1", mangaId = "m1", chapterKey = "c1", name = "Ch 1")
        )
        val afterAdd = mangaRepo.togglePageBookmark("m1::c1", 5)
        assertEquals(setOf(5), afterAdd)
        val afterSecond = mangaRepo.togglePageBookmark("m1::c1", 2)
        assertEquals(setOf(2, 5), afterSecond)
        val afterRemove = mangaRepo.togglePageBookmark("m1::c1", 5)
        assertEquals(setOf(2), afterRemove)
        db.close()
    }

    @Test
    fun `network error formatter unwraps TLS cause chains accurately`() {
        assertTrue(NetworkErrorFormatter.describe(UnknownHostException("raw.githubusercontent.com")).contains("DNS"))
        assertTrue(NetworkErrorFormatter.describe(SSLHandshakeException("cert")).contains("TLS/SSL"))
        assertTrue(NetworkErrorFormatter.describe(SocketTimeoutException("timed out")).contains("timed out"))
        assertTrue(NetworkErrorFormatter.describe(ConnectException("refused")).contains("TCP"))

        val expired = SSLHandshakeException("Chain validation failed").apply {
            initCause(CertPathValidatorException("validity check failed", CertificateExpiredException("expired")))
        }
        val expiredMsg = NetworkErrorFormatter.describe(expired, "cdn.mangadex.network")
        assertTrue(expiredMsg.contains("TLS/SSL"))
        assertTrue(expiredMsg.contains("cdn.mangadex.network"))
        assertTrue(expiredMsg.contains("expired", ignoreCase = true))

        val notYetValid = IOException(
            SSLHandshakeException("Handshake failed").apply {
                initCause(CertificateNotYetValidException("not valid yet"))
            }
        )
        val clockMsg = NetworkErrorFormatter.describe(notYetValid, "api.mangadex.org")
        assertTrue(clockMsg.contains("TLS/SSL"))
        assertTrue(clockMsg.contains("date/time"))

        val trustAnchor = SSLHandshakeException("Handshake failed").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        }
        val caMsg = NetworkErrorFormatter.describe(trustAnchor, "uploads.mangadex.org")
        assertTrue(caMsg.contains("TLS/SSL"))
        assertTrue(caMsg.contains("untrusted certificate authority"))

        val peerMismatch = SSLPeerUnverifiedException("Hostname api.mangadex.org not verified")
        val mismatchMsg = NetworkErrorFormatter.describe(peerMismatch, "api.mangadex.org")
        assertTrue(mismatchMsg.contains("TLS/SSL"))
        assertTrue(mismatchMsg.contains("hostname verification"))
        assertTrue(NetworkErrorFormatter.isTlsVerificationFailure(peerMismatch))
    }

    @Test
    fun `koma dns filters spoofed bogons passes ip literals and escalates failed tls hosts to doh`() {
        val publicIp = InetAddress.getByAddress("api.mangadex.org", byteArrayOf(104, 26, 2, 1))
        val spoofedLoopback = InetAddress.getByAddress("api.mangadex.org", byteArrayOf(127, 0, 0, 1))
        val spoofedPrivate = InetAddress.getByAddress("api.mangadex.org", byteArrayOf(10, 0, 0, 5))
        val systemIp = InetAddress.getByAddress("api.mangadex.org", byteArrayOf(172.toByte(), 67, 1, 2))

        var dohCalls = 0
        val dns = KomaDns(
            systemResolver = { host ->
                if (host == "api.mangadex.org") listOf(spoofedLoopback, spoofedPrivate) else listOf(systemIp)
            },
            dohResolver = { _, _ ->
                dohCalls++
                listOf(publicIp)
            }
        )

        // Bogon-only system DNS triggers DoH fallback
        val resolved = dns.lookup("api.mangadex.org")
        assertEquals(listOf(publicIp), resolved)
        assertEquals(1, dohCalls)

        // IP literal resolves directly without DoH
        val dohBeforeLiteral = dohCalls
        val ipLiteral = dns.lookup("1.1.1.1")
        assertEquals(1, ipLiteral.size)
        assertEquals("1.1.1.1", ipLiteral.first().hostAddress)
        assertEquals(dohBeforeLiteral, dohCalls)

        // Host marked after TLS failure switches to DoH first even if system returns a non-bogon IP
        val cleanSystemFirst = KomaDns(
            systemResolver = { listOf(systemIp) },
            dohResolver = { _, _ ->
                dohCalls++
                listOf(publicIp)
            }
        )
        assertEquals(listOf(systemIp), cleanSystemFirst.lookup("uploads.mangadex.org"))
        cleanSystemFirst.markHostForDohPreferred("uploads.mangadex.org")
        assertTrue(cleanSystemFirst.isHostDohPreferred("uploads.mangadex.org"))
        assertEquals(listOf(publicIp), cleanSystemFirst.lookup("uploads.mangadex.org"))
        cleanSystemFirst.mode = DnsMode.SYSTEM_DEFAULT
        assertFalse(cleanSystemFirst.isHostDohPreferred("uploads.mangadex.org"))
    }

    @Test
    fun `http client enforces strict tls verification and mangadex requests port 443`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val client = container.httpClient

        // Must never allow cleartext in httpClient connectionSpecs, and only allow MODERN_TLS (1.3 / 1.2)
        assertEquals(1, client.connectionSpecs.size)
        assertTrue(client.connectionSpecs.all { it.isTls })
        assertFalse(client.connectionSpecs.first().tlsVersions?.any { it.javaName == "TLSv1" || it.javaName == "TLSv1.1" } == true)

        // Must keep strict X509TrustManager (rejects empty cert chain instead of trusting all)
        val tm = client.x509TrustManager
        assertNotNull(tm)
        var rejected = false
        try {
            tm!!.checkServerTrusted(emptyArray(), "RSA")
        } catch (_: CertificateException) {
            rejected = true
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        // MangaDex at-home endpoint must request forcePort443=true
        val adapter = MangaDexSourceAdapter(client, container.database.sourcePreferenceDao())
        assertEquals(
            "https://api.mangadex.org/at-home/server/abc-123?forcePort443=true",
            adapter.buildAtHomeRequestUrl("abc-123")
        )
    }

    @Test
    fun `modern tls socket factory enables tls 1_3 and 1_2 without legacy tls and formats protocol diagnostics`() {
        val tlsConfig = KomaTlsProvider.configuration
        assertTrue(tlsConfig.capabilities.tls13Supported)
        assertTrue(tlsConfig.capabilities.tls12Supported)
        assertTrue(tlsConfig.capabilities.enabledProtocols.containsAll(listOf("TLSv1.3", "TLSv1.2")))

        val baseFactory = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
        val wrappedFactory = ModernTlsSocketFactory(baseFactory)
        val sock = wrappedFactory.createSocket() as SSLSocket
        sock.use { s ->
            val enabled = s.enabledProtocols.toSet()
            assertTrue("TLSv1.3" in enabled)
            assertTrue("TLSv1.2" in enabled)
            assertFalse("TLSv1" in enabled)
            assertFalse("TLSv1.1" in enabled)
            assertFalse("SSLv3" in enabled)
        }

        val protoErr = SSLProtocolException("TLSV1_ALERT_PROTOCOL_VERSION")
        NetworkErrorFormatter.attachFailureContext(
            protoErr,
            TlsFailureContext(
                host = "api.mangadex.org",
                path = "/manga",
                resolvedIp = "172.66.0.1:443",
                attemptedTlsVersions = listOf("TLSv1.3", "TLSv1.2"),
                tlsProvider = "Conscrypt"
            )
        )
        assertFalse(NetworkErrorFormatter.isTlsVerificationFailure(protoErr))
        assertTrue(NetworkErrorFormatter.isAnySslException(protoErr))
        val formatted = NetworkErrorFormatter.describe(protoErr)
        assertTrue(formatted.contains("api.mangadex.org"))
        assertTrue(formatted.contains("/manga"))
        assertTrue(formatted.contains("SSLProtocolException"))
        assertTrue(formatted.contains("TLSv1.3/TLSv1.2"))
        assertTrue(formatted.contains("TLSV1_ALERT_PROTOCOL_VERSION"))
    }

    private fun fieldFixed32(fieldNum: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNum shl 3) or 5).toLong()))
        for (i in 0..3) out.write((value ushr (i * 8)) and 0xFF)
        return out.toByteArray()
    }

    private fun fieldFixed64(fieldNum: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNum shl 3) or 1).toLong()))
        for (i in 0..7) out.write(((value ushr (i * 8)) and 0xFFL).toInt())
        return out.toByteArray()
    }

    private fun fieldGroup(fieldNum: Int, innerBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNum shl 3) or 3).toLong()))
        out.write(innerBytes)
        out.write(encodeVarint(((fieldNum shl 3) or 4).toLong()))
        return out.toByteArray()
    }

    @Test
    fun `keiyoushi index_v2 gzipped protobuf decodes repo header artifacts sources and groups cleanly`() {
        val sourcePayload = ByteArrayOutputStream().apply {
            write(fieldVarint(1, 5724751873601868259L))
            write(fieldString(2, "包子漫画"))
            write(fieldString(3, "zh"))
            write(fieldString(4, "https://cn.baozimh.com"))
            write(fieldString(5, "https://tw.baozimh.com"))
        }.toByteArray()

        val artifactsPayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "https://github.com/keiyoushi/extensions/releases/download/b035310-3/tachiyomi-zh.baozimanhua-v1.6.33.apk"))
            write(fieldString(2, "https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/zh/baozimanhua/res/mipmap-xhdpi/ic_launcher.png"))
            write(fieldString(501, "https://github.com/keiyoushi/extensions/releases/download/b035310-3/tachiyomi-zh.baozimanhua-v1.6.33.jar"))
        }.toByteArray()

        val extPayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "Baozi Manhua"))
            write(fieldString(2, "eu.kanade.tachiyomi.extension.zh.baozimanhua"))
            write(fieldBytes(3, artifactsPayload))
            write(fieldString(4, "1.6"))
            write(fieldVarint(5, 106033L))
            write(fieldString(6, "1.6.33"))
            write(fieldVarint(7, 1L)) // bit 0 = hasReadme, SFW
            write(fieldBytes(8, sourcePayload))
            // Unknown legacy group + fixed fields to verify wire types 0,1,2,3,4,5 don't corrupt remaining fields
            write(fieldGroup(99, fieldVarint(1, 7L) + fieldFixed32(2, 0x12345678)))
        }.toByteArray()

        val linksPayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "https://keiyoushi.github.io"))
            write(fieldString(2, "https://discord.gg/3FbCpdKbdY"))
        }.toByteArray()

        val wrapperPayload = fieldBytes(1, extPayload)
        val rootV2 = ByteArrayOutputStream().apply {
            write(fieldString(1, "Keiyoushi"))
            write(fieldString(2, "KEI"))
            write(fieldString(3, "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"))
            write(fieldBytes(4, linksPayload))
            write(fieldFixed64(98, 999999L))
            write(fieldBytes(101, wrapperPayload))
        }.toByteArray()

        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(rootV2) }

        val parsed = ExtensionIndexParser.parseIndexBytes(
            repoId = 1L,
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            rawBytes = gzOut.toByteArray(),
            contentType = "application/octet-stream"
        )

        assertEquals("index.pb (v2)", parsed.formatLabel)
        assertEquals("Keiyoushi", parsed.repoName)
        assertEquals("KEI", parsed.repoShortName)
        assertEquals("https://keiyoushi.github.io", parsed.repoWebsite)
        assertEquals("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2", parsed.repoFingerprint)
        assertEquals(1, parsed.packages.size)

        val pkg = parsed.packages.first()
        assertEquals("Baozi Manhua", pkg.name)
        assertEquals("eu.kanade.tachiyomi.extension.zh.baozimanhua", pkg.pkgId)
        assertEquals("zh", pkg.language)
        assertEquals("1.6", pkg.libVersion)
        assertEquals("1.6.33", pkg.versionName)
        assertEquals(106033, pkg.versionCode)
        assertTrue(pkg.hasReadme)
        assertFalse(pkg.isNsfw)
        assertEquals(
            "https://github.com/keiyoushi/extensions/releases/download/b035310-3/tachiyomi-zh.baozimanhua-v1.6.33.apk",
            pkg.apkUrl
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/zh/baozimanhua/res/mipmap-xhdpi/ic_launcher.png",
            pkg.iconUrl
        )
        assertTrue(pkg.sourcesJson.contains("https://cn.baozimh.com"))
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED.name, pkg.compatibilityStatus)
    }

    @Test
    fun `protobuf wire reader skips unknown wire types 0 1 2 3 4 5 safely and rejects malformed inputs`() {
        // Unknown nested groups + fixed32 + fixed64 before and after valid v1 extension payload
        val innerGroup = fieldGroup(10, fieldVarint(1, 42L) + fieldString(2, "nested"))
        val outerGroup = fieldGroup(55, fieldFixed32(1, 123) + fieldFixed64(2, 456L) + innerGroup)
        val extPayload = ByteArrayOutputStream().apply {
            write(outerGroup)
            write(fieldFixed32(44, 0xCAFE))
            write(fieldFixed64(45, 0xDEADBEEFL))
            write(fieldString(1, "WireSafe Source"))
            write(fieldString(2, "eu.kanade.tachiyomi.extension.en.wiresafe"))
            write(fieldString(3, "tachiyomi-en.wiresafe-v1.4.2.apk"))
            write(fieldString(4, "en"))
            write(fieldVarint(5, 4L))
            write(fieldString(6, "1.4.2"))
        }.toByteArray()

        val rootWithUnknowns = ByteArrayOutputStream().apply {
            write(fieldGroup(90, fieldString(1, "header-group")))
            write(fieldBytes(1, extPayload))
        }.toByteArray()

        val ok = ExtensionIndexParser.parseIndexBytes(9L, "https://repo.example/index.pb", rootWithUnknowns)
        assertEquals(1, ok.packages.size)
        assertEquals("WireSafe Source", ok.packages.first().name)

        // Malformed fixtures: HTML page, unclosed group (wt=3), unmatched end group (wt=4), reserved wt=6, truncated LEN
        val htmlErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(
                9L,
                "https://repo.example/index.pb",
                "<!DOCTYPE html><html><body>404</body></html>".toByteArray(),
                contentType = "text/html"
            )
        }.exceptionOrNull()
        assertNotNull(htmlErr)
        assertTrue(htmlErr!!.message!!.contains("repo.example"))
        assertTrue(htmlErr.message!!.contains("HTML/XML"))

        val unclosedGroupBytes = byteArrayOf(((10 shl 3) or 3).toByte(), 0x08, 0x01)
        val unclosedErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(9L, "https://repo.example/bad1.pb", unclosedGroupBytes)
        }.exceptionOrNull()
        assertNotNull(unclosedErr)
        assertTrue(unclosedErr!!.message!!.contains("START_GROUP"))

        val unmatchedEndGroup = byteArrayOf(((10 shl 3) or 4).toByte())
        val unmatchedErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(9L, "https://repo.example/bad2.pb", unmatchedEndGroup)
        }.exceptionOrNull()
        assertNotNull(unmatchedErr)
        assertTrue(unmatchedErr!!.message!!.contains("END_GROUP"))

        val reservedWireType6 = byteArrayOf(((5 shl 3) or 6).toByte())
        val reservedErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(9L, "https://repo.example/bad3.pb", reservedWireType6)
        }.exceptionOrNull()
        assertNotNull(reservedErr)
        assertTrue(reservedErr!!.message!!.contains("wireType=6"))

        val truncatedLen = byteArrayOf(0x0A, 0x50, 0x01, 0x02)
        val truncErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(9L, "https://repo.example/bad4.pb", truncatedLen)
        }.exceptionOrNull()
        assertNotNull(truncErr)
        assertTrue(truncErr!!.message!!.contains("Truncated"))
    }

    @Test
    fun `native apk reports downloaded verified unsupported cleanly while koma_json_v1 translates into working source adapter`() = runTest {
        // 1) Native APK (BaoziManhuaPlus 1.6.6) must report Downloaded + Verified + Unsupported, NEVER Installed/Active
        val nativeVerifiedApk = ExtensionPackageEntity(
            pkgId = "eu.kanade.tachiyomi.extension.zh.baozimanhuaplus",
            name = "BaoziManhuaPlus",
            versionName = "1.6.6",
            versionCode = 6,
            language = "zh",
            format = ExtensionFormat.MIHON_JSON_INDEX.id,
            localApkPath = "/data/user/0/com.aistudio.koma/files/extensions_cache/baozi.apk",
            signatureHash = "abcd1234abcd1234",
            libVersion = "1.6",
            compatibilityStatus = ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED.name,
            isInstalled = false,
            isEnabled = false
        )
        val nativeLifecycle = ExtensionCompatibilityResolver.resolveLifecycle(nativeVerifiedApk)
        assertTrue(nativeLifecycle.isDownloaded)
        assertTrue(nativeLifecycle.isVerified)
        assertFalse(nativeLifecycle.isCompatible)
        assertTrue(nativeLifecycle.isUnsupported)
        assertFalse(nativeLifecycle.isActive)
        assertEquals(ExtensionLifecycleState.VERIFIED, nativeLifecycle.primaryState)
        assertTrue(ExtensionCompatibilityTranslator.translateToSourceAdapter(nativeVerifiedApk, OkHttpClient()).isFailure)

        // 2) Declarative koma_json_v1 manifest translates cleanly into working MangaSourceAdapter (search, details, chapters, pages)
        val cleartextCheck = KomaManifestValidator.validateJsonString("""{"baseUrl":"http://insecure.example","popularEndpoint":"/popular"}""")
        assertFalse(cleartextCheck.isValid)

        val manifestJson = """
            {
              "id": "koma.test.baozi_mirror",
              "name": "Koma Mirror Catalog",
              "versionCode": 1,
              "versionName": "1.0.0",
              "lang": "en",
              "adapter": {
                "baseUrl": "https://mirror.example.org",
                "popularEndpoint": "/api/popular?page={page}",
                "searchEndpoint": "/api/search?q={query}&page={page}",
                "detailsEndpoint": "/api/manga/{id}",
                "chaptersEndpoint": "/api/manga/{id}/chapters",
                "pagesEndpoint": "/api/chapter/{chapterId}/pages",
                "itemsPath": "data.items",
                "idField": "id",
                "titleField": "meta.title",
                "coverField": "meta.cover",
                "authorField": "meta.author",
                "chaptersPath": "chapters",
                "pagesPath": "pages",
                "pageUrlField": "url"
              }
            }
        """.trimIndent()
        val parsed = ExtensionIndexParser.parseIndexBytes(
            repoId = 20L,
            indexUrl = "https://mirror.example.org/manifest.json",
            rawBytes = manifestJson.toByteArray()
        )
        assertEquals("koma_json_v1", parsed.formatLabel)
        assertEquals(1, parsed.packages.size)

        val komaPkg = parsed.packages.first().copy(isInstalled = true, isEnabled = true)
        val komaLifecycle = ExtensionCompatibilityResolver.resolveLifecycle(komaPkg)
        assertTrue(komaLifecycle.isCompatible)
        assertTrue(komaLifecycle.isActive)
        assertFalse(komaLifecycle.isUnsupported)
        assertEquals(ExtensionLifecycleState.ACTIVE, komaLifecycle.primaryState)

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val jsonBody = when {
                    url.contains("/api/search") -> """{"data":{"items":[{"id":"m-1","meta":{"title":"Test Manga","cover":"https://mirror.example.org/c.jpg","author":"A. Author"}}]}}"""
                    url.contains("/chapters") -> """{"chapters":[{"id":"ch-1","title":"Ch 1: Beginning","number":1.0}]}"""
                    url.contains("/pages") -> """{"pages":[{"url":"https://mirror.example.org/p1.jpg"},{"url":"/p2.jpg"}]}"""
                    url.contains("/api/manga/m-1") -> """{"id":"m-1","meta":{"title":"Test Manga","cover":"https://mirror.example.org/c.jpg","author":"A. Author"},"description":"Full desc","status":"ongoing"}"""
                    else -> """{"data":{"items":[]}}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val adapter = ExtensionCompatibilityTranslator.translateToSourceAdapter(komaPkg, mockHttp).getOrThrow()
        val searchRes = adapter.search("Test", 1)
        assertEquals(1, searchRes.items.size)
        assertEquals("Test Manga", searchRes.items.first().title)

        val details = adapter.getMangaDetails("m-1")
        assertEquals("Full desc", details.description)
        assertEquals("A. Author", details.author)

        val chapters = adapter.getChapters("m-1")
        assertEquals(1, chapters.size)
        assertEquals("ch-1", chapters.first().chapterKey)

        val pages = adapter.getChapterPages("ch-1")
        assertEquals(2, pages.size)
        assertEquals("https://mirror.example.org/p1.jpg", pages[0].imageUrl)
        assertEquals("https://mirror.example.org/p2.jpg", pages[1].imageUrl)
    }

    @Test
    fun `index parser handles single-field v2 artifacts zlib deflate and rejects plain text or zip payloads`() {
        // Single-field v2 artifacts submessage (only field 1 apkUrl) + relative "apk/..." path deduplication
        val singleArtifact = fieldString(1, "apk/tachiyomi-en.singlefield-v1.5.0.apk")
        val extPayload = ByteArrayOutputStream().apply {
            write(fieldString(1, "Single Field V2"))
            write(fieldString(2, "eu.kanade.tachiyomi.extension.en.singlefield"))
            write(fieldBytes(3, singleArtifact))
            write(fieldString(4, "1.5"))
            write(fieldVarint(5, 105000L))
            write(fieldString(6, "1.5.0"))
        }.toByteArray()
        val rootV2 = ByteArrayOutputStream().apply {
            write(fieldString(1, "Keiyoushi"))
            write(fieldString(2, "KEI"))
            write(fieldString(3, "deadbeef"))
            write(fieldBytes(101, fieldBytes(1, extPayload)))
        }.toByteArray()

        // Compress with raw zlib DEFLATE (0x78 ...)
        val zlibOut = ByteArrayOutputStream()
        DeflaterOutputStream(zlibOut).use { it.write(rootV2) }

        val parsed = ExtensionIndexParser.parseIndexBytes(
            repoId = 1L,
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            rawBytes = zlibOut.toByteArray()
        )
        assertEquals(1, parsed.packages.size)
        val pkg = parsed.packages.first()
        assertEquals("eu.kanade.tachiyomi.extension.en.singlefield", pkg.pkgId)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-en.singlefield-v1.5.0.apk",
            pkg.apkUrl
        )

        // Plain-text HTTP error must NOT enter ProtobufWireReader
        val textErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(
                1L,
                "https://cdn.example.com/index.pb",
                "429 Too Many Requests".toByteArray()
            )
        }.exceptionOrNull()
        assertNotNull(textErr)
        assertTrue(textErr!!.message!!.contains("plain text", ignoreCase = true))

        // ZIP archive returned where index.pb was expected must be rejected cleanly
        val zipErr = runCatching {
            ExtensionIndexParser.parseIndexBytes(
                1L,
                "https://cdn.example.com/index.pb",
                byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
            )
        }.exceptionOrNull()
        assertNotNull(zipErr)
        assertTrue(zipErr!!.message!!.contains("ZIP/APK", ignoreCase = true))
    }

    private fun writeSyntheticApkZip(
        destFile: File,
        includeManifest: Boolean = true,
        includeDex: Boolean = true,
        includeCert: Boolean = false
    ) {
        ZipOutputStream(destFile.outputStream()).use { zos ->
            if (includeManifest) {
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zos.closeEntry()
            }
            if (includeDex) {
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write("dex\n035\u0000".toByteArray())
                zos.closeEntry()
            }
            if (includeCert) {
                zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
                zos.write(byteArrayOf(0x30, 0x82.toByte()))
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `apk classification separates Category A B C D and security fingerprint checks cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "apk_audit_test_${System.currentTimeMillis()}").apply { mkdirs() }

        // 1) Category B: Corrupt/non-ZIP file
        val corruptApk = File(tempDir, "corrupt.apk").apply { writeText("<html>502 Bad Gateway</html>") }
        val corruptAudit = ExtensionSecurityManager.inspectApkStructural(context, corruptApk, "eu.kanade.tachiyomi.extension.en.test")
        assertFalse(corruptAudit.isValidZipArchive)
        val corruptEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = ExtensionPackageEntity(
                pkgId = "eu.kanade.tachiyomi.extension.en.test",
                name = "Corrupt",
                versionName = "1.4.0",
                versionCode = 1
            ),
            inspection = corruptAudit,
            isRepoTrusted = true,
            expectedFingerprint = ""
        )
        assertEquals(ExtensionCompatibilityStatus.MALFORMED_APK, corruptEval.status)
        assertEquals(ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE, corruptEval.category)

        // 2) Category B: Fingerprint mismatch when signature is blank must FAIL (fixes old silent-pass security hole)
        assertFalse(ExtensionSecurityManager.fingerprintsMatch("9add655a78e96c4e", ""))
        val validZipNoPm = ApkInspectionResult(
            packageName = "eu.kanade.tachiyomi.extension.en.good",
            versionCode = 10L,
            versionName = "1.4.2",
            extensionClass = "eu.kanade.tachiyomi.extension.en.good.GoodSource",
            isNsfw = false,
            hasReadme = false,
            signatureSha256 = "",
            fileSha256 = "0123456789abcdef0123456789abcdef",
            isValidZipArchive = true,
            hasAndroidManifestEntry = true,
            hasDexBytecode = true,
            hasSigningBlockOrCert = false,
            packageManagerParsed = true,
            structuralIssues = emptyList()
        )
        val missingSigEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = ExtensionPackageEntity(
                pkgId = "eu.kanade.tachiyomi.extension.en.good",
                name = "Good",
                versionName = "1.4.2",
                versionCode = 10
            ),
            inspection = validZipNoPm,
            isRepoTrusted = true,
            expectedFingerprint = "9add655a78e96c4e"
        )
        assertEquals(ExtensionCompatibilityStatus.SIGNATURE_MISMATCH, missingSigEval.status)
        assertEquals(ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE, missingSigEval.category)

        // 3) Category D: Valid signed Android APK missing Mihon extension markers
        val nonMihonInspection = ApkInspectionResult(
            packageName = "com.example.randomapp",
            versionCode = 1L,
            versionName = "1.4.0",
            extensionClass = "",
            isNsfw = false,
            hasReadme = false,
            signatureSha256 = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            fileSha256 = "abcdef01",
            isValidZipArchive = true,
            hasAndroidManifestEntry = true,
            hasDexBytecode = true,
            hasSigningBlockOrCert = true,
            packageManagerParsed = true,
            structuralIssues = emptyList()
        )
        val catDEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = ExtensionPackageEntity(
                pkgId = "com.example.randomapp",
                name = "RandomApp",
                versionName = "1.4.0",
                versionCode = 1
            ),
            inspection = nonMihonInspection,
            isRepoTrusted = true,
            expectedFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        )
        assertEquals(ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE, catDEval.status)
        assertEquals(ApkIncompatibilityCategory.DIFFERENT_ARCHITECTURE, catDEval.category)

        // 4) Category A: Valid Mihon APK targeting unsupported extensions-lib 1.7
        val catAInspection = nonMihonInspection.copy(
            packageName = "eu.kanade.tachiyomi.extension.en.future",
            versionName = "1.7.0",
            extensionClass = ".FutureSource",
            hasTachiyomiFeature = true
        )
        val catAEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = ExtensionPackageEntity(
                pkgId = "eu.kanade.tachiyomi.extension.en.future",
                name = "Future",
                versionName = "1.7.0",
                versionCode = 1
            ),
            inspection = catAInspection,
            isRepoTrusted = true,
            expectedFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        )
        assertEquals(ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE, catAEval.status)
        assertEquals(ApkIncompatibilityCategory.API_VERSION_INCOMPATIBLE, catAEval.category)

        // 5) Category C: Valid signed Mihon APK targeting supported metadata range (v1.4) + DEX bytecode
        val catCInspection = catAInspection.copy(versionName = "1.4.12")
        val catCEval = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = ExtensionPackageEntity(
                pkgId = "eu.kanade.tachiyomi.extension.en.validmihon",
                name = "ValidMihon",
                versionName = "1.4.12",
                versionCode = 12
            ),
            inspection = catCInspection,
            isRepoTrusted = true,
            expectedFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        )
        assertEquals(ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED, catCEval.status)
        assertEquals(ApkIncompatibilityCategory.MIHON_DEX_POLICY_RESTRICTED, catCEval.category)
        assertFalse(catCEval.canExecuteInKoma)

        // 6) End-to-end PackageManager download rejection on malformed APK body
        val db = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("NOT A VALID APK".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val extRepo = ExtensionRepository(db.extensionDao(), mockHttp, context)
        val repoId = db.extensionDao().upsertRepo(
            ExtensionRepoEntity(
                name = "TestRepo",
                indexUrl = "https://repo.example/index.pb",
                signingKeyFingerprint = "9add655a",
                isTrusted = true
            )
        )
        val pkg = ExtensionPackageEntity(
            pkgId = "eu.kanade.tachiyomi.extension.en.bad",
            repoId = repoId,
            name = "BadApk",
            versionName = "1.4.0",
            versionCode = 1,
            apkUrl = "https://repo.example/apk/bad.apk",
            format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id
        )
        db.extensionDao().upsertPackage(pkg)
        val dlResult = extRepo.downloadAndVerifyMihonApk(pkg)
        assertTrue(dlResult.isFailure)
        val saved = db.extensionDao().getPackage(pkg.pkgId)!!
        assertEquals(ExtensionCompatibilityStatus.MALFORMED_APK.name, saved.compatibilityStatus)
        assertFalse(saved.isInstalled)
        assertEquals("", saved.signatureHash)

        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `option A companion IPC discovers activates and executes full manga workflow without DEX loading`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.example.companion.ipc.test"
        val companionPkg = "com.koma.companion.testsource"
        val sigBytes = "test-companion-cert".toByteArray()
        val expectedFp = MessageDigest.getInstance("SHA-256").digest(sigBytes).joinToString("") { "%02x".format(it) }

        // Register real Robolectric ContentProvider responding to call() IPC requests
        val fakeProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                val json = when (method) {
                    "status" -> """{"online":true}"""
                    "popular", "search" -> """{"items":[{"mangaKey":"ipc-1","title":"Companion Saga","coverUrl":"https://cdn.example/c.jpg","author":"IPC Author","status":"ongoing"}],"hasNextPage":false}"""
                    "details" -> """{"mangaKey":"ipc-1","title":"Companion Saga","author":"IPC Author","description":"Served across Binder IPC","status":"ongoing","genres":["Action","Sci-Fi"]}"""
                    "chapters" -> """{"chapters":[{"chapterKey":"ipc-ch-1","name":"Chapter 1","chapterNumber":1.0}]}"""
                    "pages" -> """{"pages":[{"index":0,"url":"https://cdn.example/p1.webp","headers":{"Referer":"https://companion.example/"}}]}"""
                    else -> """{"error":"unknown method"}"""
                }
                return Bundle().apply { putString("payload", json) }
            }
        }
        val providerInfo = ProviderInfo().apply {
            this.authority = authority
            this.packageName = companionPkg
            this.name = "FakeIpcProvider"
            this.metaData = Bundle().apply {
                putString("koma.extension.ipc.authority", authority)
            }
        }
        fakeProvider.attachInfo(context, providerInfo)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, fakeProvider)

        val shadowPm = shadowOf(context.packageManager)
        val companionAppInfo = ApplicationInfo().apply {
            packageName = companionPkg
            metaData = Bundle().apply {
                putString("koma.extension.ipc.authority", authority)
            }
        }
        val registeredProvider = ProviderInfo(providerInfo).apply {
            applicationInfo = companionAppInfo
        }
        @Suppress("DEPRECATION")
        val pkgInfo = PackageInfo().apply {
            packageName = companionPkg
            versionName = "1.0.0"
            versionCode = 1
            applicationInfo = companionAppInfo
            providers = arrayOf(registeredProvider)
            signatures = arrayOf(Signature(sigBytes))
        }
        shadowPm.installPackage(pkgInfo)
        val resolveInfo = ResolveInfo().apply {
            this.providerInfo = registeredProvider
            this.activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = companionPkg
                name = "com.koma.companion.testsource.CompanionActivity"
                applicationInfo = companionAppInfo
            }
        }
        shadowPm.addResolveInfoForIntent(android.content.Intent(ExtensionSecurityManager.ACTION_KOMA_IPC_PROVIDER), resolveInfo)

        val discovered = ExtensionSecurityManager.discoverInstalledCompanionPackages(
            context,
            mapOf(companionPkg to expectedFp)
        )
        assertEquals(1, discovered.size)
        assertEquals(authority, discovered.first().ipcAuthority)
        assertTrue(discovered.first().matchesExpectedFingerprint)

        val db = Room.inMemoryDatabaseBuilder(context, KomaDatabase::class.java).allowMainThreadQueries().build()
        val extRepo = ExtensionRepository(db.extensionDao(), OkHttpClient(), context)
        val repoId = db.extensionDao().upsertRepo(
            ExtensionRepoEntity(
                name = "CompanionRepo",
                indexUrl = "https://companion.example/repo.json",
                signingKeyFingerprint = expectedFp,
                isTrusted = true
            )
        )
        val pkg = ExtensionPackageEntity(
            pkgId = companionPkg,
            repoId = repoId,
            name = "Companion Source",
            versionName = "1.0.0",
            versionCode = 1,
            language = "en",
            format = ExtensionFormat.MIHON_JSON_INDEX.id
        )
        db.extensionDao().upsertPackage(pkg)

        // Auto-sync detects IPC provider cleanly & marks COMPANION_IPC_READY
        val synced = extRepo.syncInstalledCompanionApps()
        assertEquals(1, synced)
        val readyPkg = db.extensionDao().getPackage(companionPkg)!!
        assertEquals(ExtensionFormat.COMPANION_IPC_EXTENSION.id, readyPkg.format)
        assertEquals(ExtensionCompatibilityStatus.COMPANION_IPC_READY.name, readyPkg.compatibilityStatus)

        // Activate IPC extension and register inside SourceRegistry
        assertTrue(extRepo.activateCompanionIpc(readyPkg, authority, companionPkg).isSuccess)
        val prefsRepo = AppPreferencesRepository(context)
        val registry = SourceRegistry(context, OkHttpClient(), prefsRepo, db.extensionDao(), db.sourcePreferenceDao())
        val activeAdapter = registry.getSourceById(companionPkg)
        assertNotNull(activeAdapter)
        assertTrue(activeAdapter!!.isAvailable())
        val searchRes = activeAdapter.search("Companion", 1)
        assertEquals("Companion Saga", searchRes.items.first().title)
        assertEquals("Served across Binder IPC", activeAdapter.getMangaDetails("ipc-1").description)
        assertEquals("ipc-ch-1", activeAdapter.getChapters("ipc-1").first().chapterKey)
        val pages = activeAdapter.getChapterPages("ipc-ch-1")
        assertEquals("https://companion.example/", pages.first().headers["Referer"])

        db.close()
    }

    @Test
    fun `option B apiVersion bounds offset headers and option C native mangadex mapping behave cleanly`() = runTest {
        // Option B: apiVersion > 1 rejected cleanly
        val unsupportedApi = KomaManifestValidator.validateJsonString(
            """{"apiVersion":2,"baseUrl":"https://api.example.com","popularEndpoint":"/popular"}"""
        )
        assertFalse(unsupportedApi.isValid)
        assertTrue(unsupportedApi.errorReason!!.contains("api", ignoreCase = true))

        // Option B: {offset}/{limit} & pageHeaders propagated to SourcePage
        val komaPkg = ExtensionPackageEntity(
            pkgId = "koma.offset.test",
            name = "Offset Source",
            versionName = "1.0.0",
            versionCode = 1,
            language = "en",
            format = ExtensionFormat.KOMA_JSON_ADAPTER.id,
            adapterJson = """
                {
                  "apiVersion": 1,
                  "baseUrl": "https://cdn.example.org",
                  "pageSize": 10,
                  "popularEndpoint": "/manga?offset={offset}&limit={limit}",
                  "pagesEndpoint": "/pages/{chapterId}",
                  "itemsPath": "results",
                  "hasNextPath": "hasMore",
                  "pagesPath": "images",
                  "pageUrlField": "src",
                  "pageHeaders": {"Referer": "https://cdn.example.org/reader"}
                }
            """.trimIndent(),
            compatibilityStatus = ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON.name,
            isInstalled = true,
            isEnabled = true
        )
        var capturedUrl = ""
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedUrl = chain.request().url.toString()
                val body = if (capturedUrl.contains("/pages/")) {
                    """{"images":[{"src":"https://cdn.example.org/img1.png"}]}"""
                } else {
                    """{"hasMore":false,"results":[{"id":"a1","title":"Paged Item"}]}"""
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
        val adapter = ExtensionCompatibilityTranslator.translateToSourceAdapter(komaPkg, mockHttp).getOrThrow()
        val page2 = adapter.getPopular(page = 3)
        assertEquals("https://cdn.example.org/manga?offset=20&limit=10", capturedUrl)
        assertFalse(page2.hasNextPage)
        assertEquals("Paged Item", page2.items.first().title)
        val pages = adapter.getChapterPages("ch99")
        assertEquals("https://cdn.example.org/reader", pages.first().headers["Referer"])

        // Option C: Keiyoushi MangaDex maps to built-in native mangadex while reporting DEX restriction honestly
        val mdEval = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_PROTOBUF_INDEX,
            versionName = "1.4.205",
            isRepoTrusted = true,
            signatureMatch = true,
            pkgId = "eu.kanade.tachiyomi.extension.all.mangadex",
            extensionName = "MangaDex"
        )
        assertEquals(ExtensionCompatibilityStatus.NATIVE_EQUIVALENT_AVAILABLE, mdEval.status)
        assertEquals("mangadex", mdEval.nativeEquivalentSourceId)
        assertFalse(mdEval.canExecuteInKoma)

        val mdEntity = ExtensionPackageEntity(
            pkgId = "eu.kanade.tachiyomi.extension.all.mangadex",
            repoId = 1L,
            name = "MangaDex",
            versionName = "1.4.205",
            versionCode = 205,
            libVersion = "1.4",
            format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
            compatibilityStatus = mdEval.status.name
        )
        val mdLifecycle = ExtensionCompatibilityResolver.resolveLifecycle(mdEntity)
        assertEquals("mangadex", mdLifecycle.nativeEquivalentSourceId)
        assertFalse(mdLifecycle.isCompatible)
        assertNotNull(mdLifecycle.matrix)
        assertTrue(mdLifecycle.matrix!!.repositoryImported)
        assertTrue(mdLifecycle.matrix!!.metadataRecognized)
        assertFalse(mdLifecycle.matrix!!.extensionActivated)
    }
}
