package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MangaEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.TapZoneLayout
import com.example.extension.BuiltInRepositories
import com.example.extension.ExtensionCompatibilityResolver
import com.example.extension.ExtensionIndexParser
import com.example.extension.ExtensionRepositoryManager
import com.example.network.NetworkErrorFormatter
import com.example.source.SourceRegistry
import com.example.ui.screens.reader.TapZoneAction
import com.example.ui.screens.reader.resolveReaderTapAction
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLHandshakeException

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

        val tooNew = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_JSON_INDEX,
            versionName = "1.6.0",
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
    fun `network error formatter produces actionable diagnostics`() {
        assertTrue(NetworkErrorFormatter.describe(UnknownHostException("raw.githubusercontent.com")).contains("DNS"))
        assertTrue(NetworkErrorFormatter.describe(SSLHandshakeException("cert")).contains("TLS/SSL"))
        assertTrue(NetworkErrorFormatter.describe(SocketTimeoutException("timed out")).contains("timed out"))
        assertTrue(NetworkErrorFormatter.describe(ConnectException("refused")).contains("TCP"))
    }
}
