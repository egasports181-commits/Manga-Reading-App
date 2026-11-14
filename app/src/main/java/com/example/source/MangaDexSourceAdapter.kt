package com.example.source

import com.example.data.local.dao.SourcePreferenceDao
import com.example.data.local.entity.SourcePreferenceEntity
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MangaDexSourceAdapter(
    private val httpClient: OkHttpClient,
    private val prefDao: SourcePreferenceDao
) : MangaSourceAdapter {

    override val id: String = "mangadex"
    override val name: String = "MangaDex"
    override val language: String = "multi"
    override val category: String = "Community API"
    override val baseUrl: String = "https://api.mangadex.org"
    override val supportsFilters: Boolean = true
    override val supportsLogin: Boolean = true

    private val tagIdCache = mutableMapOf<String, String>()

    override suspend fun isAvailable(): Boolean = checkStatus().isOnline

    override suspend fun checkStatus(): SourceHealthStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder().url("$baseUrl/ping").get().build()
            httpClient.newCall(req).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (resp.isSuccessful) {
                    SourceHealthStatus(isOnline = true, latencyMs = elapsed, httpStatus = resp.code)
                } else {
                    SourceHealthStatus(
                        isOnline = false,
                        latencyMs = elapsed,
                        httpStatus = resp.code,
                        errorReason = "HTTP ${resp.code} from MangaDex API"
                    )
                }
            }
        } catch (e: Exception) {
            SourceHealthStatus(
                isOnline = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = NetworkErrorFormatter.describe(e, "api.mangadex.org")
            )
        }
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        queryManga(
            query = "",
            page = page,
            filters = filters.copy(sortBy = SourceSortOption.POPULARITY)
        )

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        queryManga(
            query = "",
            page = page,
            filters = filters.copy(sortBy = SourceSortOption.LATEST_UPDATE)
        )

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult =
        queryManga(query = query, page = page, filters = filters)

    private suspend fun queryManga(
        query: String,
        page: Int,
        filters: SearchFilters
    ): SourcePagedResult = withContext(Dispatchers.IO) {
        val prefs = getPrefMap()
        val lang = filters.language ?: prefs["preferred_lang"] ?: "en"
        val limit = 20
        val offset = ((page - 1).coerceAtLeast(0)) * limit

        val urlBuilder = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("hasAvailableChapters", "true")

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("title", query.trim())
        }
        if (lang.isNotBlank() && lang != "all") {
            urlBuilder.addQueryParameter("availableTranslatedLanguage[]", lang)
        }
        filters.status?.let { status ->
            val mapped = when (status) {
                MangaStatus.ONGOING -> "ongoing"
                MangaStatus.COMPLETED -> "completed"
                MangaStatus.HIATUS -> "hiatus"
                MangaStatus.CANCELLED -> "cancelled"
                MangaStatus.UNKNOWN -> null
            }
            if (mapped != null) urlBuilder.addQueryParameter("status[]", mapped)
        }
        filters.releaseYear?.let { urlBuilder.addQueryParameter("year", it.toString()) }

        val ratings = when (filters.contentRating?.lowercase()) {
            "suggestive" -> listOf("safe", "suggestive")
            "all" -> listOf("safe", "suggestive", "erotica")
            else -> listOf("safe")
        }
        ratings.forEach { urlBuilder.addQueryParameter("contentRating[]", it) }

        if (filters.genres.isNotEmpty()) {
            ensureTagsLoaded()
            filters.genres.forEach { genreName ->
                tagIdCache[genreName.lowercase()]?.let { tagId ->
                    urlBuilder.addQueryParameter("includedTags[]", tagId)
                }
            }
        }

        when (filters.sortBy) {
            SourceSortOption.POPULARITY -> urlBuilder.addQueryParameter("order[followedCount]", "desc")
            SourceSortOption.LATEST_UPDATE -> urlBuilder.addQueryParameter("order[latestUploadedChapter]", "desc")
            SourceSortOption.RELEVANCE -> {
                if (query.isBlank()) urlBuilder.addQueryParameter("order[followedCount]", "desc")
                else urlBuilder.addQueryParameter("order[relevance]", "desc")
            }
        }

        val request = buildAuthorizedRequest(urlBuilder.build().toString())
        val json = executeJson(request)
        val data = json.optJSONArray("data") ?: JSONArray()
        val total = json.optInt("total", 0)
        val items = mutableListOf<SourceMangaItem>()

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val item = parseMangaObject(obj, lang)
            if (filters.authorOrArtist.isBlank() ||
                item.author.contains(filters.authorOrArtist, ignoreCase = true) ||
                item.artist.contains(filters.authorOrArtist, ignoreCase = true)
            ) {
                items.add(item)
            }
        }

        SourcePagedResult(
            items = items,
            hasNextPage = offset + data.length() < total
        )
    }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem = withContext(Dispatchers.IO) {
        val url = "$baseUrl/manga/$mangaKey?includes[]=cover_art&includes[]=author&includes[]=artist"
        val json = executeJson(buildAuthorizedRequest(url))
        val data = json.getJSONObject("data")
        val prefs = getPrefMap()
        parseMangaObject(data, prefs["preferred_lang"] ?: "en")
    }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> = withContext(Dispatchers.IO) {
        val prefs = getPrefMap()
        val lang = prefs["preferred_lang"] ?: "en"
        val chapters = mutableListOf<SourceChapter>()
        var offset = 0
        val limit = 100
        var keepFetching = true

        while (keepFetching && offset < 500) {
            val urlBuilder = "$baseUrl/manga/$mangaKey/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("contentRating[]", "safe")
                .addQueryParameter("contentRating[]", "suggestive")
            if (lang != "all") {
                urlBuilder.addQueryParameter("translatedLanguage[]", lang)
            }

            val json = executeJson(buildAuthorizedRequest(urlBuilder.build().toString()))
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val chObj = data.getJSONObject(i)
                val attrs = chObj.getJSONObject("attributes")
                val externalUrl = attrs.optString("externalUrl", "")
                // Respect external licensed chapters: skip empty external-only links
                if (externalUrl.isNotBlank() && externalUrl != "null") continue

                val chNumStr = attrs.optString("chapter", "")
                val chNum = chNumStr.toDoubleOrNull() ?: 0.0
                val title = attrs.optString("title", "").takeIf { it != "null" }.orEmpty()
                val vol = attrs.optString("volume", "").takeIf { it != "null" }.orEmpty()
                val displayName = buildString {
                    if (vol.isNotBlank()) append("Vol.$vol ")
                    if (chNumStr.isNotBlank()) append("Ch.$chNumStr") else append("Oneshot")
                    if (title.isNotBlank()) append(" - $title")
                }

                var scanlator = ""
                val rels = chObj.optJSONArray("relationships")
                if (rels != null) {
                    for (r in 0 until rels.length()) {
                        val rel = rels.getJSONObject(r)
                        if (rel.optString("type") == "scanlation_group") {
                            scanlator = rel.optJSONObject("attributes")?.optString("name").orEmpty()
                            break
                        }
                    }
                }

                chapters.add(
                    SourceChapter(
                        chapterKey = chObj.getString("id"),
                        name = displayName,
                        chapterNumber = chNum,
                        volumeNumber = vol,
                        scanlator = scanlator,
                        uploadedAt = System.currentTimeMillis()
                    )
                )
            }
            val total = json.optInt("total", 0)
            offset += data.length()
            keepFetching = offset < total && data.length() > 0
        }
        chapters
    }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> = withContext(Dispatchers.IO) {
        val prefs = getPrefMap()
        val dataSaver = prefs["data_saver"] == "true"
        val atHomeUrl = buildAtHomeRequestUrl(chapterKey)
        val json = executeJson(buildAuthorizedRequest(atHomeUrl))
        val rawHost = json.getString("baseUrl").trim().trimEnd('/')
        val parsedHost = rawHost.toHttpUrlOrNull()
        if (parsedHost == null || !parsedHost.isHttps) {
            throw IOException("MangaDex@Home returned an invalid or non-HTTPS image server URL: $rawHost")
        }
        val chapterObj = json.getJSONObject("chapter")
        val hash = chapterObj.getString("hash")
        val qualityPath = if (dataSaver) "data-saver" else "data"
        val files = chapterObj.getJSONArray(if (dataSaver) "dataSaver" else "data")

        buildList {
            for (i in 0 until files.length()) {
                val fileName = files.getString(i)
                add(
                    SourcePage(
                        index = i,
                        imageUrl = "$rawHost/$qualityPath/$hash/$fileName"
                    )
                )
            }
        }
    }

    internal fun buildAtHomeRequestUrl(chapterKey: String): String =
        "$baseUrl/at-home/server/$chapterKey?forcePort443=true"

    override suspend fun getPreferences(): List<SourcePreferenceItem> {
        val prefs = getPrefMap()
        return listOf(
            SourcePreferenceItem(
                key = "preferred_lang",
                title = "Chapter Language",
                summary = "Filter fetched manga & chapters by translation language",
                currentValue = prefs["preferred_lang"] ?: "en",
                options = listOf(
                    "en" to "English",
                    "ja" to "Japanese",
                    "ko" to "Korean",
                    "zh" to "Chinese",
                    "es" to "Spanish",
                    "fr" to "French",
                    "all" to "All Languages"
                )
            ),
            SourcePreferenceItem(
                key = "data_saver",
                title = "Data Saver Mode",
                summary = "Load compressed chapter images to reduce mobile data usage",
                currentValue = prefs["data_saver"] ?: "false",
                options = listOf("false" to "Original Quality", "true" to "Data Saver")
            ),
            SourcePreferenceItem(
                key = "session_token",
                title = "Personal Access Token",
                summary = if (prefs["session_token"].isNullOrBlank()) "Not signed in" else "Session token stored locally",
                currentValue = if (prefs["session_token"].isNullOrBlank()) "" else "••••••••",
                isSecret = true
            )
        )
    }

    override suspend fun updatePreference(key: String, value: String) {
        prefDao.setPreference(SourcePreferenceEntity(id, key, value))
    }

    override suspend fun login(username: String, credential: String): SourceSessionResult =
        withContext(Dispatchers.IO) {
            if (credential.isBlank()) {
                return@withContext SourceSessionResult.Failed("Provide a valid MangaDex personal OAuth token or password.")
            }
            // Verify token directly against /user/me
            try {
                val req = Request.Builder()
                    .url("$baseUrl/user/me")
                    .header("Authorization", "Bearer $credential")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val uname = JSONObject(body).optJSONObject("data")
                            ?.optJSONObject("attributes")
                            ?.optString("username")
                            ?: username.ifBlank { "MangaDex User" }
                        prefDao.setPreference(SourcePreferenceEntity(id, "session_token", credential))
                        SourceSessionResult.Authenticated(uname, "Verified against /user/me")
                    } else {
                        SourceSessionResult.Failed("Authentication rejected (HTTP ${resp.code}). Verify your Bearer token.")
                    }
                }
            } catch (e: Exception) {
                SourceSessionResult.Failed(NetworkErrorFormatter.describe(e, "api.mangadex.org"))
            }
        }

    private suspend fun ensureTagsLoaded() {
        if (tagIdCache.isNotEmpty()) return
        try {
            val json = executeJson(buildAuthorizedRequest("$baseUrl/manga/tag"))
            val data = json.optJSONArray("data") ?: return
            for (i in 0 until data.length()) {
                val tag = data.getJSONObject(i)
                val tagId = tag.getString("id")
                val name = tag.getJSONObject("attributes").getJSONObject("name").optString("en")
                if (name.isNotBlank()) {
                    tagIdCache[name.lowercase()] = tagId
                }
            }
        } catch (_: Exception) {
            // Best-effort tag lookup
        }
    }

    private fun parseMangaObject(obj: JSONObject, fallbackLang: String): SourceMangaItem {
        val mangaId = obj.getString("id")
        val attrs = obj.getJSONObject("attributes")
        val titleObj = attrs.getJSONObject("title")
        val title = titleObj.optString("en").ifBlank {
            titleObj.keys().asSequence().firstOrNull()?.let { titleObj.optString(it) } ?: "Untitled"
        }

        var altTitle = ""
        val altTitles = attrs.optJSONArray("altTitles")
        if (altTitles != null) {
            for (i in 0 until altTitles.length()) {
                val altObj = altTitles.getJSONObject(i)
                val candidate = altObj.optString("ja-ro").ifBlank {
                    altObj.optString("en").ifBlank {
                        altObj.keys().asSequence().firstOrNull()?.let { altObj.optString(it) }.orEmpty()
                    }
                }
                if (candidate.isNotBlank() && candidate != title) {
                    altTitle = candidate
                    break
                }
            }
        }

        val descObj = attrs.optJSONObject("description")
        val description = descObj?.optString("en")?.ifBlank {
            descObj.keys().asSequence().firstOrNull()?.let { descObj.optString(it) }
        } ?: ""

        val genres = mutableListOf<String>()
        val tags = attrs.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val tagName = tags.getJSONObject(i)
                    .optJSONObject("attributes")
                    ?.optJSONObject("name")
                    ?.optString("en")
                if (!tagName.isNullOrBlank()) genres.add(tagName)
            }
        }

        var coverFile = ""
        var author = ""
        var artist = ""
        val rels = obj.optJSONArray("relationships")
        if (rels != null) {
            for (i in 0 until rels.length()) {
                val rel = rels.getJSONObject(i)
                when (rel.optString("type")) {
                    "cover_art" -> coverFile = rel.optJSONObject("attributes")?.optString("fileName").orEmpty()
                    "author" -> author = rel.optJSONObject("attributes")?.optString("name").orEmpty()
                    "artist" -> artist = rel.optJSONObject("attributes")?.optString("name").orEmpty()
                }
            }
        }

        val coverUrl = if (coverFile.isNotBlank()) {
            "https://uploads.mangadex.org/covers/$mangaId/$coverFile.512.jpg"
        } else ""

        val lastChap = attrs.optString("lastChapter", "").takeIf { it != "null" }.orEmpty()
        val lastNum = lastChap.toDoubleOrNull() ?: 0.0
        val year = attrs.optInt("year", 0).takeIf { it > 1900 }

        return SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = mangaId,
            title = title,
            altTitle = altTitle,
            coverUrl = coverUrl,
            author = author,
            artist = artist,
            description = description,
            genres = genres,
            status = MangaStatus.fromString(attrs.optString("status")),
            language = fallbackLang,
            contentRating = attrs.optString("contentRating", "safe"),
            releaseYear = year,
            latestChapterName = if (lastChap.isNotBlank()) "Ch.$lastChap" else "",
            latestChapterNumber = lastNum,
            isDemoData = false
        )
    }

    private suspend fun getPrefMap(): Map<String, String> =
        prefDao.getForSource(id).associate { it.prefKey to it.prefValue }

    private suspend fun buildAuthorizedRequest(url: String): Request {
        val token = getPrefMap()["session_token"]
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun executeJson(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful) {
                throw IOException("MangaDex API HTTP ${response.code}: ${body.take(180)}")
            }
            return JSONObject(body)
        }
    }
}
