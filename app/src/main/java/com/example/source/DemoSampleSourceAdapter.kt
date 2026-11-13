package com.example.source

import android.content.Context
import com.example.domain.model.*

/**
 * Explicitly labeled Demo Sample Source (`isDemoSource = true`).
 * Only exposed when the user enables Demo Mode in Settings or Home.
 * Never presented as live external catalog data.
 */
class DemoSampleSourceAdapter(private val context: Context) : MangaSourceAdapter {

    override val id: String = "demo_sample"
    override val name: String = "Demo Catalog (Sample)"
    override val language: String = "en"
    override val category: String = "Sample Showcase"
    override val baseUrl: String = "demo://koma.local"
    override val supportsFilters: Boolean = true
    override val supportsLogin: Boolean = false
    override val isDemoSource: Boolean = true

    private val pkg = context.packageName

    private val sampleCatalog = listOf(
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = "neon_ronin_sector_0",
            title = "Neon Ronin: Sector 0",
            altTitle = "ネオン浪人・零区",
            coverUrl = "android.resource://$pkg/drawable/img_demo_cover_1",
            author = "K. Takahashi",
            artist = "K. Takahashi",
            description = "In a rain-drenched cybernetic Neo-Tokyo, an exiled courier hunts rogue memory syndicates across vertical megastructures using an analog blade.",
            genres = listOf("Action", "Cyberpunk", "Seinen", "Sci-Fi"),
            status = MangaStatus.ONGOING,
            language = "en",
            contentRating = "safe",
            releaseYear = 2025,
            latestChapterName = "Ch.14 - Rain Over Kabuki Spire",
            latestChapterNumber = 14.0,
            isDemoData = true
        ),
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = "lanterns_of_shirakawa",
            title = "Lanterns of Shirakawa",
            altTitle = "白川の灯籠",
            coverUrl = "android.resource://$pkg/drawable/img_demo_cover_2",
            author = "Aoi Fujimoto",
            artist = "Haru Mizuno",
            description = "Every autumn equinox, floating shrines drift above the misty valley of Shirakawa. Apprentice lantern-keeper Ren must guide lost spirits home before dawn.",
            genres = listOf("Fantasy", "Slice of Life", "Supernatural", "Drama"),
            status = MangaStatus.COMPLETED,
            language = "en",
            contentRating = "safe",
            releaseYear = 2023,
            latestChapterName = "Ch.12 - Equinox Dawn (Finale)",
            latestChapterNumber = 12.0,
            isDemoData = true
        ),
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = "perihelion_drift",
            title = "Perihelion Drift",
            altTitle = "近日点ドリフト",
            coverUrl = "android.resource://$pkg/drawable/img_demo_cover_3",
            author = "Sora Watanabe",
            artist = "Sora Watanabe",
            description = "When an orbital salvage crew discovers an intact pre-collapse observatory orbiting a shattered ring world, every faction in the belt closes in.",
            genres = listOf("Sci-Fi", "Adventure", "Mystery", "Shonen"),
            status = MangaStatus.ONGOING,
            language = "en",
            contentRating = "safe",
            releaseYear = 2026,
            latestChapterName = "Ch.9 - Lagrange Gate",
            latestChapterNumber = 9.0,
            isDemoData = true
        )
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        filterSample(query = "", page = page, filters = filters)

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        filterSample(query = "", page = page, filters = filters.copy(sortBy = SourceSortOption.LATEST_UPDATE))

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult =
        filterSample(query = query, page = page, filters = filters)

    private fun filterSample(query: String, page: Int, filters: SearchFilters): SourcePagedResult {
        if (page > 1) return SourcePagedResult(emptyList(), false)
        val filtered = sampleCatalog.filter { item ->
            val matchesQuery = query.isBlank() ||
                item.title.contains(query, true) ||
                item.altTitle.contains(query, true)
            val matchesStatus = filters.status == null || item.status == filters.status
            val matchesGenres = filters.genres.isEmpty() || filters.genres.any { g ->
                item.genres.any { it.equals(g, ignoreCase = true) }
            }
            val matchesAuthor = filters.authorOrArtist.isBlank() ||
                item.author.contains(filters.authorOrArtist, true)
            val matchesYear = filters.releaseYear == null || item.releaseYear == filters.releaseYear
            matchesQuery && matchesStatus && matchesGenres && matchesAuthor && matchesYear
        }
        return SourcePagedResult(filtered, hasNextPage = false)
    }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem =
        sampleCatalog.firstOrNull { it.mangaKey == mangaKey } ?: sampleCatalog.first()

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> {
        val item = getMangaDetails(mangaKey)
        val maxCh = item.latestChapterNumber.toInt().coerceAtLeast(5)
        val now = System.currentTimeMillis()
        return (maxCh downTo 1).map { num ->
            val titleSuffix = if (num == maxCh && item.latestChapterName.contains(" - ")) {
                " - " + item.latestChapterName.substringAfter(" - ")
            } else {
                " - Episode $num"
            }
            SourceChapter(
                chapterKey = "$mangaKey::ch_$num",
                name = "Ch.$num$titleSuffix",
                chapterNumber = num.toDouble(),
                volumeNumber = "${((num - 1) / 4) + 1}",
                scanlator = "Demo Sample",
                uploadedAt = now - (maxCh - num) * 86_400_000L * 3L
            )
        }
    }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> {
        val parts = chapterKey.split("::")
        val mangaKey = parts.getOrElse(0) { "sample" }
        val chKey = parts.getOrElse(1) { "ch_1" }
        return (0 until 8).map { idx ->
            SourcePage(
                index = idx,
                imageUrl = "demo-page://$mangaKey/$chKey/$idx",
                isLocalFile = true
            )
        }
    }
}
