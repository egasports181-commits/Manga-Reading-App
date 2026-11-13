package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KomaDatabase
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.repository.ExtensionRepository
import com.example.domain.model.BackupCategory
import com.example.domain.model.BackupManga
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.KomaBackupV1
import com.example.source.DemoSampleSourceAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Koma`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Koma", context.getString(R.string.app_name))
    }

    @Test
    fun `demo sample source marks items as demo data and yields readable pages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = DemoSampleSourceAdapter(context)
        val popular = adapter.getPopular(1)
        assertTrue(popular.items.isNotEmpty())
        assertTrue(popular.items.all { it.isDemoData })

        val chapters = adapter.getChapters(popular.items.first().mangaKey)
        assertTrue(chapters.isNotEmpty())
        val pages = adapter.getChapterPages(chapters.first().chapterKey)
        assertEquals(8, pages.size)
        assertTrue(pages.first().imageUrl.startsWith("demo-page://"))
    }

    @Test
    fun `extension repository rejects cleartext http and blocks external apk execution`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = KomaDatabase.build(context)
        val repo = ExtensionRepository(db.extensionDao(), OkHttpClient())

        val httpResult = repo.addRepository("http://insecure.example.com/index.json")
        assertTrue(httpResult.isFailure)

        val apkPkg = ExtensionPackageEntity(
            pkgId = "eu.kanade.tachiyomi.extension.en.sample",
            name = "External APK",
            versionName = "1.0",
            versionCode = 1,
            format = ExtensionFormat.EXTERNAL_APK_INCOMPATIBLE.id
        )
        val installResult = repo.installExtension(apkPkg)
        assertTrue(installResult.isFailure)
        db.close()
    }

    @Test
    fun `backup v1 serialization roundtrip preserves schema and library data`() {
        val backup = KomaBackupV1(
            categories = listOf(BackupCategory("Reading", 0, true)),
            manga = listOf(
                BackupManga(
                    id = "mangadex::abc",
                    sourceId = "mangadex",
                    mangaKey = "abc",
                    title = "Sample",
                    altTitle = "",
                    author = "Author",
                    artist = "Artist",
                    description = "Desc",
                    genres = "Action",
                    status = "ONGOING",
                    coverUrl = "",
                    language = "en",
                    contentRating = "safe",
                    dateAdded = 1000L,
                    lastReadAt = 2000L,
                    isBookmarked = true,
                    totalReadingTimeMs = 5000L,
                    categoryNames = listOf("Reading")
                )
            )
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(backup)
        val decoded = json.decodeFromString<KomaBackupV1>(encoded)
        assertEquals(1, decoded.schemaVersion)
        assertEquals("Sample", decoded.manga.first().title)
        assertEquals(listOf("Reading"), decoded.manga.first().categoryNames)
    }
}
