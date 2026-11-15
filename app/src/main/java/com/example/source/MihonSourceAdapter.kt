package com.example.source

import android.util.Log
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getSourcePreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Bridges any compiled Mihon [Source] ([HttpSource], [ParsedHttpSource], [CatalogueSource],
 * [ConfigurableSource]) cleanly into Kiyomi's [MangaSourceAdapter] pipeline, forwarding per-page
 * HTTP headers (`Referer`, custom `User-Agent`, cookies) into [SourcePage.headers] for both
 * Coil reading and offline chapter downloads.
 */
class MihonSourceAdapter(
    val mihonSource: Source,
    val extensionPackageName: String,
    private val httpClient: OkHttpClient
) : MangaSourceAdapter {

    val mihonSourceId: Long = runCatching { mihonSource.id }
        .getOrElse {
            runCatching { mihonSource.name.hashCode().toLong() and 0x7FFFFFFFL }
                .getOrDefault(extensionPackageName.hashCode().toLong() and 0x7FFFFFFFL)
        }

    override val id: String = "mihon_$mihonSourceId"
    override val name: String = runCatching { mihonSource.name }.getOrDefault(extensionPackageName)
    override val language: String = runCatching { mihonSource.lang.ifBlank { "all" } }.getOrDefault("all")
    override val category: String = "Mihon Extension"
    override val baseUrl: String by lazy {
        runCatching { (mihonSource as? HttpSource)?.baseUrl.orEmpty() }.getOrDefault("")
    }
    override val supportsFilters: Boolean by lazy {
        runCatching { (mihonSource as? CatalogueSource)?.getFilterList()?.isNotEmpty() == true }
            .getOrDefault(false)
    }
    override val supportsLogin: Boolean = false

    private val sourceHeadersMap: Map<String, String> by lazy {
        val hdrs = runCatching { (mihonSource as? HttpSource)?.headers }.getOrNull() ?: return@lazy emptyMap()
        buildMap {
            for (i in 0 until hdrs.size) {
                put(hdrs.name(i), hdrs.value(i))
            }
        }
    }

    override suspend fun isAvailable(): Boolean = checkStatus().isOnline

    override suspend fun checkStatus(): SourceHealthStatus = withContext(Dispatchers.IO) {
        val httpSrc = mihonSource as? HttpSource
        val targetUrl = runCatching { httpSrc?.baseUrl?.trim().orEmpty() }.getOrDefault("")
        if (targetUrl.isBlank()) {
            return@withContext SourceHealthStatus(isOnline = true, latencyMs = 1L)
        }
        val start = System.currentTimeMillis()
        try {
            val status = withTimeoutOrNull(12_000L) {
                val client = httpSrc?.client ?: httpClient
                val req = GET(targetUrl, httpSrc?.headers ?: okhttp3.Headers.Builder().build())
                client.newCall(req).await().use { resp ->
                    val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    runCatching {
                        Log.d("KiyomiSource", "Health check [$name] host=${targetUrl.toHttpUrlOrNull()?.host} code=${resp.code} (${elapsed}ms)")
                    }
                    SourceHealthStatus(
                        isOnline = resp.code < 500,
                        latencyMs = elapsed,
                        httpStatus = resp.code,
                        errorReason = if (resp.code >= 500) "HTTP ${resp.code}" else null
                    )
                }
            }
            status ?: SourceHealthStatus(
                isOnline = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = "Timed out checking source availability"
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching {
                Log.w("KiyomiSource", "Health check failed [$name] (${t.javaClass.simpleName}): ${t.message}", t)
            }
            SourceHealthStatus(
                isOnline = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = NetworkErrorFormatter.describe(t, targetUrl.toHttpUrlOrNull()?.host)
            )
        }
    }

    private suspend inline fun <T> guardExtensionCall(
        operation: String,
        crossinline block: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            block().also {
                val elapsed = System.currentTimeMillis() - start
                runCatching {
                    Log.d("KiyomiSource", "$operation ok [$name] (${elapsed}ms)")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            runCatching {
                Log.w("KiyomiSource", "$operation failed [$name] (${elapsed}ms): ${t.javaClass.simpleName}: ${t.message}", t)
            }
            if (t is Exception) {
                throw t
            } else {
                throw IllegalStateException("Extension '$name' failure during $operation: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}", t)
            }
        }
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        guardExtensionCall("popular(page=$page)") {
            val cat = mihonSource as? CatalogueSource
                ?: return@guardExtensionCall SourcePagedResult(emptyList(), false)
            val result = cat.getPopularManga(page)
            SourcePagedResult(
                items = result.mangas.map { it.toSourceMangaItem() },
                hasNextPage = result.hasNextPage
            )
        }

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        guardExtensionCall("latest(page=$page)") {
            val cat = mihonSource as? CatalogueSource
                ?: return@guardExtensionCall SourcePagedResult(emptyList(), false)
            val result = if (cat.supportsLatest) {
                cat.getLatestUpdates(page)
            } else {
                cat.getPopularManga(page)
            }
            SourcePagedResult(
                items = result.mangas.map { it.toSourceMangaItem() },
                hasNextPage = result.hasNextPage
            )
        }

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult =
        guardExtensionCall("search(page=$page)") {
            val cat = mihonSource as? CatalogueSource
                ?: return@guardExtensionCall SourcePagedResult(emptyList(), false)
            val filterList = runCatching { cat.getFilterList() }.getOrDefault(FilterList())
            val result = cat.getSearchManga(page, query, filterList)
            SourcePagedResult(
                items = result.mangas.map { it.toSourceMangaItem() },
                hasNextPage = result.hasNextPage
            )
        }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem =
        guardExtensionCall("details") {
            val input = SManga.create().apply {
                url = mangaKey
                title = mangaKey
            }
            mihonSource.getMangaDetails(input).toSourceMangaItem(fallbackKey = mangaKey)
        }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> =
        guardExtensionCall("chapters") {
            val input = SManga.create().apply {
                url = mangaKey
                title = mangaKey
            }
            val list = mihonSource.getChapterList(input)
            list.mapIndexed { idx, ch ->
                val resolvedNumber = if (ch.chapter_number >= 0f) {
                    ch.chapter_number.toDouble()
                } else {
                    extractChapterNumber(ch.name) ?: (list.size - idx).toDouble()
                }
                SourceChapter(
                    chapterKey = ch.url,
                    name = ch.name.ifBlank { "Chapter ${idx + 1}" },
                    chapterNumber = resolvedNumber,
                    scanlator = ch.scanlator.orEmpty(),
                    uploadedAt = if (ch.date_upload > 0L) ch.date_upload else System.currentTimeMillis()
                )
            }
        }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> =
        guardExtensionCall("pages") {
            val chapter = SChapter.create().apply {
                url = chapterKey
                name = chapterKey
            }
            val rawPages = mihonSource.getPageList(chapter)
            val httpSrc = mihonSource as? HttpSource
            rawPages.mapIndexedNotNull { idx, page ->
                val resolvedImageUrl = page.imageUrl?.takeIf { it.isNotBlank() }
                    ?: if (httpSrc != null && page.url.isNotBlank()) {
                        runCatching { httpSrc.getImageUrl(page) }.getOrNull()
                    } else {
                        null
                    }
                    ?: page.url.takeIf { it.startsWith("http") }

                val finalUrl = resolvedImageUrl?.trim().orEmpty()
                if (finalUrl.isBlank()) return@mapIndexedNotNull null

                val pageForRequest = Page(page.index, page.url, finalUrl, page.uri)
                val headersMap = extractImageHeaders(httpSrc, pageForRequest)
                SourcePage(
                    index = idx,
                    imageUrl = finalUrl,
                    headers = headersMap
                )
            }
        }

    override suspend fun getPreferences(): List<SourcePreferenceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = mihonSource as? ConfigurableSource ?: return@withContext emptyList()
            val prefs = cfg.getSourcePreferences()
            prefs.all.entries.map { (k, v) ->
                SourcePreferenceItem(
                    key = k,
                    title = k.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    summary = "Mihon source preference ($extensionPackageName)",
                    currentValue = v?.toString().orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun updatePreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = mihonSource as? ConfigurableSource ?: return@withContext
                cfg.getSourcePreferences().edit().putString(key, value).apply()
            }
        }
    }

    private fun extractImageHeaders(httpSrc: HttpSource?, page: Page): Map<String, String> {
        if (httpSrc == null) return emptyMap()
        val reqHeaders = runCatching { httpSrc.imageRequest(page).headers }.getOrNull()
            ?: return sourceHeadersMap
        return buildMap {
            for (i in 0 until reqHeaders.size) {
                val hName = reqHeaders.name(i)
                val hValue = reqHeaders.value(i)
                if (hName.isNotBlank() && hValue.isNotBlank() && !hName.equals("Host", ignoreCase = true)) {
                    put(hName, hValue)
                }
            }
        }
    }

    private fun SManga.toSourceMangaItem(fallbackKey: String = ""): SourceMangaItem {
        val rawUrl = runCatching { url }.getOrDefault(fallbackKey)
        val rawTitle = runCatching { title }.getOrDefault(fallbackKey)
        val resolvedCover = thumbnail_url.orEmpty().trim().let { cover ->
            when {
                cover.isBlank() -> ""
                cover.startsWith("http://") || cover.startsWith("https://") -> cover
                cover.startsWith("//") -> "https:$cover"
                cover.startsWith("/") && baseUrl.isNotBlank() -> baseUrl.trimEnd('/') + cover
                else -> cover
            }
        }
        return SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = rawUrl.ifBlank { fallbackKey },
            title = rawTitle.ifBlank { "Untitled" },
            coverUrl = resolvedCover,
            author = author.orEmpty(),
            artist = artist.orEmpty(),
            description = description.orEmpty(),
            genres = getGenres().orEmpty(),
            status = status.toMangaStatus(),
            language = language
        )
    }

    private fun Int.toMangaStatus(): MangaStatus = when (this) {
        SManga.ONGOING -> MangaStatus.ONGOING
        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaStatus.COMPLETED
        SManga.ON_HIATUS -> MangaStatus.HIATUS
        SManga.CANCELLED -> MangaStatus.CANCELLED
        else -> MangaStatus.UNKNOWN
    }

    private fun extractChapterNumber(name: String): Double? {
        val match = Regex("""(?:ch(?:apter)?\.?\s*#?\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(name)
            ?: Regex("""\b(\d+(?:\.\d+)?)\b""").find(name)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
}
