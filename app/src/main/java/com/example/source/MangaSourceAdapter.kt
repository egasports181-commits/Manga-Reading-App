package com.example.source

import com.example.domain.model.*

interface MangaSourceAdapter {
    val id: String
    val name: String
    val language: String
    val category: String
    val baseUrl: String
    val supportsFilters: Boolean
    val supportsLogin: Boolean
    val isDemoSource: Boolean get() = false

    suspend fun isAvailable(): Boolean
    suspend fun checkStatus(): SourceHealthStatus {
        val start = System.currentTimeMillis()
        return try {
            val ok = isAvailable()
            SourceHealthStatus(
                isOnline = ok,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = if (ok) null else "Source unavailable"
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SourceHealthStatus(
                isOnline = false,
                latencyMs = System.currentTimeMillis() - start,
                errorReason = t.message ?: t.javaClass.simpleName
            )
        }
    }
    suspend fun getPopular(page: Int, filters: SearchFilters = SearchFilters()): SourcePagedResult
    suspend fun getLatest(page: Int, filters: SearchFilters = SearchFilters()): SourcePagedResult
    suspend fun search(query: String, page: Int, filters: SearchFilters = SearchFilters()): SourcePagedResult
    suspend fun getMangaDetails(mangaKey: String): SourceMangaItem
    suspend fun getChapters(mangaKey: String): List<SourceChapter>
    suspend fun getChapterPages(chapterKey: String): List<SourcePage>
    suspend fun getPreferences(): List<SourcePreferenceItem> = emptyList()
    suspend fun updatePreference(key: String, value: String) {}
    suspend fun login(username: String, credential: String): SourceSessionResult =
        SourceSessionResult.Unsupported("This source does not support account login.")
}
