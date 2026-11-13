package com.example.source

import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Sandboxed Koma Declarative JSON Source Adapter (`koma_json_v1`).
 * Parses a declarative JSON descriptor specifying REST endpoints & JSON field paths.
 * Executes ZERO remote JavaScript and loads ZERO native/DEX code.
 */
class DeclarativeJsonSourceAdapter(
    override val id: String,
    override val name: String,
    override val language: String,
    private val descriptor: JSONObject,
    private val httpClient: OkHttpClient
) : MangaSourceAdapter {

    override val category: String = "Koma Extension"
    override val baseUrl: String = descriptor.optString("baseUrl", "")
    override val supportsFilters: Boolean = false
    override val supportsLogin: Boolean = false

    override suspend fun isAvailable(): Boolean = checkStatus().isOnline

    override suspend fun checkStatus(): SourceHealthStatus = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://")) {
            return@withContext SourceHealthStatus(
                isOnline = false,
                errorReason = "HTTPS endpoint required"
            )
        }
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(baseUrl).head().build()
            httpClient.newCall(req).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                SourceHealthStatus(
                    isOnline = resp.code < 500,
                    latencyMs = elapsed,
                    httpStatus = resp.code,
                    errorReason = if (resp.code >= 500) "HTTP ${resp.code}" else null
                )
            }
        } catch (e: Exception) {
            SourceHealthStatus(
                isOnline = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = NetworkErrorFormatter.describe(e)
            )
        }
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        fetchList(descriptor.optString("popularEndpoint", ""), page)

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        fetchList(descriptor.optString("latestEndpoint", descriptor.optString("popularEndpoint", "")), page)

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult {
        val template = descriptor.optString("searchEndpoint", "")
        if (template.isBlank()) return SourcePagedResult(emptyList(), false)
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchList(template.replace("{query}", encoded), page)
    }

    private suspend fun fetchList(endpointTemplate: String, page: Int): SourcePagedResult =
        withContext(Dispatchers.IO) {
            if (endpointTemplate.isBlank()) return@withContext SourcePagedResult(emptyList(), false)
            val url = resolveUrl(endpointTemplate.replace("{page}", page.toString()))
            val jsonText = executeGet(url)
            val root = JSONObject(jsonText)
            val listPath = descriptor.optString("itemsPath", "items")
            val array = extractArray(root, listPath)
            val keyField = descriptor.optString("idField", "id")
            val titleField = descriptor.optString("titleField", "title")
            val coverField = descriptor.optString("coverField", "cover")
            val authorField = descriptor.optString("authorField", "author")

            val items = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        SourceMangaItem(
                            sourceId = id,
                            sourceName = name,
                            mangaKey = obj.optString(keyField),
                            title = obj.optString(titleField, "Untitled"),
                            coverUrl = obj.optString(coverField),
                            author = obj.optString(authorField),
                            description = obj.optString("description"),
                            status = MangaStatus.fromString(obj.optString("status")),
                            language = language
                        )
                    )
                }
            }
            SourcePagedResult(items, hasNextPage = items.size >= 20)
        }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem = withContext(Dispatchers.IO) {
        val template = descriptor.optString("detailsEndpoint", "")
        if (template.isBlank()) {
            return@withContext SourceMangaItem(id, name, mangaKey, mangaKey)
        }
        val obj = JSONObject(executeGet(resolveUrl(template.replace("{id}", mangaKey))))
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = mangaKey,
            title = obj.optString(descriptor.optString("titleField", "title"), mangaKey),
            coverUrl = obj.optString(descriptor.optString("coverField", "cover")),
            author = obj.optString(descriptor.optString("authorField", "author")),
            description = obj.optString("description"),
            status = MangaStatus.fromString(obj.optString("status")),
            language = language
        )
    }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> = withContext(Dispatchers.IO) {
        val template = descriptor.optString("chaptersEndpoint", "")
        if (template.isBlank()) return@withContext emptyList()
        val root = JSONObject(executeGet(resolveUrl(template.replace("{id}", mangaKey))))
        val arr = extractArray(root, descriptor.optString("chaptersPath", "chapters"))
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    SourceChapter(
                        chapterKey = obj.optString("id"),
                        name = obj.optString("title", "Chapter ${i + 1}"),
                        chapterNumber = obj.optDouble("number", (i + 1).toDouble())
                    )
                )
            }
        }
    }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> = withContext(Dispatchers.IO) {
        val template = descriptor.optString("pagesEndpoint", "")
        if (template.isBlank()) return@withContext emptyList()
        val root = JSONObject(executeGet(resolveUrl(template.replace("{chapterId}", chapterKey))))
        val arr = extractArray(root, descriptor.optString("pagesPath", "pages"))
        buildList {
            for (i in 0 until arr.length()) {
                add(SourcePage(index = i, imageUrl = arr.getString(i)))
            }
        }
    }

    private fun resolveUrl(path: String): String {
        val full = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        require(full.startsWith("https://")) { "Only HTTPS endpoints are permitted for Koma Declarative Extensions." }
        return full
    }

    private fun executeGet(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty response")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return body
        }
    }

    private fun extractArray(root: JSONObject, dotPath: String): JSONArray {
        val parts = dotPath.split(".").filter { it.isNotBlank() }
        var current: Any = root
        for (p in parts) {
            current = (current as? JSONObject)?.opt(p) ?: return JSONArray()
        }
        return current as? JSONArray ?: JSONArray()
    }
}
