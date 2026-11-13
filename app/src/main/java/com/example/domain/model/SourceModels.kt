package com.example.domain.model

import kotlinx.serialization.Serializable

data class SearchFilters(
    val genres: Set<String> = emptySet(),
    val status: MangaStatus? = null,
    val language: String? = null,
    val authorOrArtist: String = "",
    val releaseYear: Int? = null,
    val contentRating: String? = null,
    val sortBy: SourceSortOption = SourceSortOption.POPULARITY
)

enum class SourceSortOption(val id: String, val label: String) {
    RELEVANCE("relevance", "Relevance"),
    POPULARITY("popularity", "Popularity"),
    LATEST_UPDATE("latest", "Latest Update");

    companion object {
        fun fromId(id: String): SourceSortOption = entries.firstOrNull { it.id == id } ?: POPULARITY
    }
}

data class SourceHealthStatus(
    val isOnline: Boolean,
    val latencyMs: Long = 0L,
    val httpStatus: Int? = null,
    val errorReason: String? = null
)

@Serializable
data class ExtensionSourceMeta(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String = ""
)

data class SourceMangaItem(
    val sourceId: String,
    val sourceName: String,
    val mangaKey: String,
    val title: String,
    val altTitle: String = "",
    val coverUrl: String = "",
    val author: String = "",
    val artist: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val language: String = "en",
    val contentRating: String = "safe",
    val releaseYear: Int? = null,
    val latestChapterName: String = "",
    val latestChapterNumber: Double = 0.0,
    val isDemoData: Boolean = false
)

data class SourcePagedResult(
    val items: List<SourceMangaItem>,
    val hasNextPage: Boolean
)

data class SourceChapter(
    val chapterKey: String,
    val name: String,
    val chapterNumber: Double,
    val volumeNumber: String = "",
    val scanlator: String = "",
    val uploadedAt: Long = System.currentTimeMillis()
)

data class SourcePage(
    val index: Int,
    val imageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val isLocalFile: Boolean = false
)

data class SourcePreferenceItem(
    val key: String,
    val title: String,
    val summary: String,
    val currentValue: String,
    val options: List<Pair<String, String>> = emptyList(),
    val isSecret: Boolean = false
)

sealed interface SourceSessionResult {
    data class Authenticated(val username: String, val note: String) : SourceSessionResult
    data class Failed(val reason: String) : SourceSessionResult
    data class Unsupported(val reason: String) : SourceSessionResult
}
