package com.example.source

import com.example.domain.model.*
import com.example.network.HttpResponseValidator
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Option B: Sandboxed Koma Declarative JSON Source Adapter (`koma_json_v1`, API v1).
 * Parses a declarative JSON descriptor specifying HTTPS REST endpoints,
 * dot-path JSON field selectors, pagination offsets, and optional request/page headers.
 * Executes ZERO remote JavaScript and loads ZERO native/DEX code.
 */
class DeclarativeJsonSourceAdapter(
    override val id: String,
    override val name: String,
    override val language: String,
    private val descriptor: JSONObject,
    private val httpClient: OkHttpClient
) : MangaSourceAdapter {

    override val category: String = "Kiyomi Extension"
    override val baseUrl: String = descriptor.optString("baseUrl", "").trim()
    override val supportsFilters: Boolean = descriptor.optString("searchEndpoint", "").contains("{lang}")
    override val supportsLogin: Boolean = false

    private val pageSize: Int = descriptor.optInt("pageSize", 20).coerceIn(1, 200)

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
            val req = applyHeaders(Request.Builder().url(baseUrl).head()).build()
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
                errorReason = NetworkErrorFormatter.describe(e, baseUrl.toHttpUrlOrNull()?.host)
            )
        }
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        fetchList(descriptor.optString("popularEndpoint", ""), page, filters)

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        fetchList(descriptor.optString("latestEndpoint", descriptor.optString("popularEndpoint", "")), page, filters)

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult {
        val template = descriptor.optString("searchEndpoint", "")
        if (template.isBlank()) return SourcePagedResult(emptyList(), false)
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchList(template.replace("{query}", encoded), page, filters)
    }

    private suspend fun fetchList(
        endpointTemplate: String,
        page: Int,
        filters: SearchFilters
    ): SourcePagedResult = withContext(Dispatchers.IO) {
        if (endpointTemplate.isBlank()) return@withContext SourcePagedResult(emptyList(), false)
        val offset = ((page - 1).coerceAtLeast(0)) * pageSize
        val resolvedTemplate = endpointTemplate
            .replace("{page}", page.toString())
            .replace("{offset}", offset.toString())
            .replace("{limit}", pageSize.toString())
            .replace("{lang}", URLEncoder.encode(filters.language ?: language, "UTF-8"))
        val url = resolveUrl(resolvedTemplate)
        val root = executeJsonRoot(url)
        val listPath = descriptor.optString("itemsPath", "items")
        val array = extractArray(root, listPath)
        val keyField = descriptor.optString("idField", "id")
        val titleField = descriptor.optString("titleField", "title")
        val coverField = descriptor.optString("coverField", "cover")
        val authorField = descriptor.optString("authorField", "author")
        val descField = descriptor.optString("descriptionField", "description")
        val statusField = descriptor.optString("statusField", "status")
        val latestField = descriptor.optString("latestChapterField", "latestChapter")

        val items = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rawCover = extractString(obj, coverField)
                add(
                    SourceMangaItem(
                        sourceId = id,
                        sourceName = name,
                        mangaKey = extractString(obj, keyField),
                        title = extractString(obj, titleField).ifBlank { "Untitled" },
                        coverUrl = normalizeOptionalHttpsUrl(rawCover),
                        author = extractString(obj, authorField),
                        description = extractString(obj, descField),
                        status = MangaStatus.fromString(extractString(obj, statusField)),
                        language = language,
                        latestChapterName = extractString(obj, latestField)
                    )
                )
            }
        }
        val hasNextPath = descriptor.optString("hasNextPath", "")
        val hasNext = if (hasNextPath.isNotBlank() && root is JSONObject) {
            extractValue(root, hasNextPath)?.toString()?.toBooleanStrictOrNull() ?: (items.size >= pageSize)
        } else {
            items.size >= pageSize
        }
        SourcePagedResult(items, hasNextPage = hasNext)
    }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem = withContext(Dispatchers.IO) {
        val template = descriptor.optString("detailsEndpoint", "")
        if (template.isBlank()) {
            return@withContext SourceMangaItem(id, name, mangaKey, mangaKey)
        }
        val root = executeJsonObject(resolveUrl(template.replace("{id}", mangaKey)))
        val detailsPath = descriptor.optString("detailsPath", "")
        val obj = if (detailsPath.isNotBlank()) extractObject(root, detailsPath) ?: root else root
        val genresPath = descriptor.optString("genresField", "genres")
        val genres = buildList {
            val gRaw = extractValue(obj, genresPath)
            if (gRaw is JSONArray) {
                for (i in 0 until gRaw.length()) {
                    gRaw.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = mangaKey,
            title = extractString(obj, descriptor.optString("titleField", "title")).ifBlank { mangaKey },
            coverUrl = normalizeOptionalHttpsUrl(extractString(obj, descriptor.optString("coverField", "cover"))),
            author = extractString(obj, descriptor.optString("authorField", "author")),
            description = extractString(obj, descriptor.optString("descriptionField", "description")),
            genres = genres,
            status = MangaStatus.fromString(extractString(obj, descriptor.optString("statusField", "status"))),
            language = language
        )
    }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> = withContext(Dispatchers.IO) {
        val template = descriptor.optString("chaptersEndpoint", "")
        if (template.isBlank()) return@withContext emptyList()
        val root = executeJsonRoot(resolveUrl(template.replace("{id}", mangaKey)))
        val arr = extractArray(root, descriptor.optString("chaptersPath", "chapters"))
        val chapterIdField = descriptor.optString("chapterIdField", "id")
        val chapterTitleField = descriptor.optString("chapterTitleField", "title")
        val chapterNumberField = descriptor.optString("chapterNumberField", "number")
        val scanlatorField = descriptor.optString("scanlatorField", "scanlator")
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val numVal = extractValue(obj, chapterNumberField)?.toString()?.toDoubleOrNull()
                    ?: (i + 1).toDouble()
                add(
                    SourceChapter(
                        chapterKey = extractString(obj, chapterIdField),
                        name = extractString(obj, chapterTitleField).ifBlank { "Chapter ${i + 1}" },
                        chapterNumber = numVal,
                        scanlator = extractString(obj, scanlatorField)
                    )
                )
            }
        }
    }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> = withContext(Dispatchers.IO) {
        val template = descriptor.optString("pagesEndpoint", "")
        if (template.isBlank()) return@withContext emptyList()
        val root = executeJsonRoot(resolveUrl(template.replace("{chapterId}", chapterKey)))
        val arr = extractArray(root, descriptor.optString("pagesPath", "pages"))
        val pageUrlField = descriptor.optString("pageUrlField", "url")
        val staticPageHeaders = readHeaderMap(descriptor.optJSONObject("pageHeaders"))
            .ifEmpty { readHeaderMap(descriptor.optJSONObject("headers")) }
        buildList {
            for (i in 0 until arr.length()) {
                val entry = arr.opt(i)
                val rawUrl = when (entry) {
                    is String -> entry
                    is JSONObject -> extractString(entry, pageUrlField)
                    else -> ""
                }
                if (rawUrl.isNotBlank()) {
                    add(SourcePage(index = i, imageUrl = resolveUrl(rawUrl), headers = staticPageHeaders))
                }
            }
        }
    }

    private fun resolveUrl(path: String): String {
        val trimmed = path.trim()
        val full = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            baseUrl.trimEnd('/') + "/" + trimmed.trimStart('/')
        }
        require(full.startsWith("https://")) {
            "Only HTTPS endpoints are permitted for Kiyomi Declarative Extensions."
        }
        return full
    }

    private fun normalizeOptionalHttpsUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("https://") -> raw
            raw.startsWith("/") -> baseUrl.trimEnd('/') + raw
            else -> ""
        }
    }

    private fun readHeaderMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank() && !key.equals("Host", ignoreCase = true)) {
                map[key] = value
            }
        }
        return map
    }

    private fun applyHeaders(builder: Request.Builder): Request.Builder {
        readHeaderMap(descriptor.optJSONObject("headers")).forEach { (key, value) ->
            builder.header(key, value)
        }
        return builder
    }

    private fun executeJsonRoot(url: String): Any {
        val req = applyHeaders(
            Request.Builder().url(url).header("Accept", "application/json")
        ).get().build()
        httpClient.newCall(req).execute().use { resp ->
            return HttpResponseValidator.parseJsonRootStrict(resp, name)
        }
    }

    private fun executeJsonObject(url: String): JSONObject {
        val req = applyHeaders(
            Request.Builder().url(url).header("Accept", "application/json")
        ).get().build()
        httpClient.newCall(req).execute().use { resp ->
            return HttpResponseValidator.parseJsonObjectStrict(resp, name)
        }
    }

    private fun extractArray(root: Any, dotPath: String): JSONArray {
        if (root is JSONArray && (dotPath.isBlank() || dotPath == "$")) return root
        val objRoot = root as? JSONObject ?: return JSONArray()
        val parts = dotPath.split(".").filter { it.isNotBlank() }
        var current: Any = objRoot
        for (p in parts) {
            current = (current as? JSONObject)?.opt(p) ?: return JSONArray()
        }
        return current as? JSONArray ?: JSONArray()
    }

    private fun extractObject(root: JSONObject, dotPath: String): JSONObject? {
        val parts = dotPath.split(".").filter { it.isNotBlank() }
        var current: Any = root
        for (p in parts) {
            current = (current as? JSONObject)?.opt(p) ?: return null
        }
        return current as? JSONObject
    }

    private fun extractValue(root: JSONObject, dotPath: String): Any? {
        if (dotPath.isBlank()) return null
        if (!dotPath.contains(".")) return root.opt(dotPath)
        val parts = dotPath.split(".").filter { it.isNotBlank() }
        var current: Any = root
        for (i in parts.indices) {
            val obj = current as? JSONObject ?: return null
            val next = obj.opt(parts[i]) ?: return null
            if (i == parts.lastIndex) return next
            current = next
        }
        return null
    }

    private fun extractString(root: JSONObject, dotPath: String): String {
        val value = extractValue(root, dotPath) ?: return ""
        val str = value.toString().trim()
        return if (str == "null") "" else str
    }
}
