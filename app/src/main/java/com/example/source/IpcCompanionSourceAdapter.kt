package com.example.source

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.example.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Option A: Out-of-process Companion Extension Adapter (`koma_ipc_v1`).
 *
 * Communicates strictly across OS-enforced IPC boundaries via Android [android.content.ContentResolver]
 * with a user-installed, signature-verified standalone companion application declaring:
 * - `<action android:name="com.koma.extension.IPC_PROVIDER" />`
 * - `<meta-data android:name="koma.extension.ipc.authority" android:value="..." />`
 *
 * Contract methods / paths:
 * - `status` -> `{"online": true}`
 * - `popular` (arg: `page`) -> `{"items": [...], "hasNextPage": bool}`
 * - `latest` (arg: `page`) -> `{"items": [...], "hasNextPage": bool}`
 * - `search` (arg: `query`, extras: `page`) -> `{"items": [...], "hasNextPage": bool}`
 * - `details` (arg: `mangaKey`) -> `{...}`
 * - `chapters` (arg: `mangaKey`) -> `{"chapters": [...]}`
 * - `pages` (arg: `chapterKey`) -> `{"pages": [{"url": "...", "headers": {...}}]}`
 *
 * Never loads or executes DEX/APK code inside Koma's process.
 */
class IpcCompanionSourceAdapter(
    private val context: Context,
    override val id: String,
    override val name: String,
    override val language: String,
    private val authority: String,
    private val targetPackageName: String
) : MangaSourceAdapter {

    override val category: String = "Companion IPC"
    override val baseUrl: String = "content://$authority"
    override val supportsFilters: Boolean = false
    override val supportsLogin: Boolean = false

    private val baseUri: Uri = Uri.parse("content://$authority")

    override suspend fun isAvailable(): Boolean = checkStatus().isOnline

    override suspend fun checkStatus(): SourceHealthStatus = withContext(Dispatchers.IO) {
        if (authority.isBlank()) {
            return@withContext SourceHealthStatus(false, errorReason = "Missing IPC provider authority")
        }
        val start = System.currentTimeMillis()
        try {
            val payload = invokeIpc("status", null, Bundle.EMPTY)
            val json = JSONObject(payload)
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            SourceHealthStatus(
                isOnline = json.optBoolean("online", true),
                latencyMs = elapsed,
                errorReason = json.optString("error").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            SourceHealthStatus(
                isOnline = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                errorReason = e.message ?: "Companion IPC app '$targetPackageName' unavailable"
            )
        }
    }

    override suspend fun getPopular(page: Int, filters: SearchFilters): SourcePagedResult =
        withContext(Dispatchers.IO) {
            val extras = Bundle().apply { putInt("page", page) }
            parsePagedItems(invokeIpc("popular", page.toString(), extras))
        }

    override suspend fun getLatest(page: Int, filters: SearchFilters): SourcePagedResult =
        withContext(Dispatchers.IO) {
            val extras = Bundle().apply { putInt("page", page) }
            parsePagedItems(invokeIpc("latest", page.toString(), extras))
        }

    override suspend fun search(query: String, page: Int, filters: SearchFilters): SourcePagedResult =
        withContext(Dispatchers.IO) {
            val extras = Bundle().apply {
                putString("query", query)
                putInt("page", page)
            }
            parsePagedItems(invokeIpc("search", query, extras))
        }

    override suspend fun getMangaDetails(mangaKey: String): SourceMangaItem =
        withContext(Dispatchers.IO) {
            val obj = JSONObject(invokeIpc("details", mangaKey, Bundle.EMPTY))
            parseMangaObject(obj, fallbackKey = mangaKey)
        }

    override suspend fun getChapters(mangaKey: String): List<SourceChapter> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(invokeIpc("chapters", mangaKey, Bundle.EMPTY))
            val arr = root.optJSONArray("chapters") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    add(
                        SourceChapter(
                            chapterKey = c.optString("chapterKey").ifBlank { c.optString("id") },
                            name = c.optString("name").ifBlank { "Chapter ${i + 1}" },
                            chapterNumber = c.optDouble("chapterNumber", (i + 1).toDouble()),
                            volumeNumber = c.optString("volumeNumber"),
                            scanlator = c.optString("scanlator"),
                            uploadedAt = c.optLong("uploadedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }

    override suspend fun getChapterPages(chapterKey: String): List<SourcePage> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(invokeIpc("pages", chapterKey, Bundle.EMPTY))
            val arr = root.optJSONArray("pages") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val imageUrl = p.optString("url").trim()
                    if (imageUrl.isNotBlank()) {
                        val hdrMap = mutableMapOf<String, String>()
                        p.optJSONObject("headers")?.let { hdrObj ->
                            val keys = hdrObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                hdrObj.optString(k).takeIf { it.isNotBlank() }?.let { v -> hdrMap[k] = v }
                            }
                        }
                        add(SourcePage(index = p.optInt("index", i), imageUrl = imageUrl, headers = hdrMap))
                    }
                }
            }
        }

    private fun invokeIpc(method: String, arg: String?, extras: Bundle): String {
        val resolver = context.contentResolver
        val bundle = runCatching {
            resolver.call(baseUri, method, arg, extras)
        }.getOrNull()
        val payload = bundle?.getString("payload")
        if (!payload.isNullOrBlank()) return payload
        val err = bundle?.getString("error")
        if (!err.isNullOrBlank()) throw IOException("Companion IPC error: $err")

        // Fallback: query content://<authority>/<method>?arg=...
        val queryUri = baseUri.buildUpon().appendPath(method).apply {
            if (!arg.isNullOrBlank()) appendQueryParameter("arg", arg)
        }.build()
        resolver.query(queryUri, arrayOf("payload"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex("payload")
                if (idx >= 0) {
                    val text = cursor.getString(idx)
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        throw IOException("Companion IPC provider '$authority' ($targetPackageName) did not respond to '$method'.")
    }

    private fun parsePagedItems(rawJson: String): SourcePagedResult {
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(parseMangaObject(obj))
            }
        }
        return SourcePagedResult(
            items = items,
            hasNextPage = root.optBoolean("hasNextPage", items.size >= 20)
        )
    }

    private fun parseMangaObject(obj: JSONObject, fallbackKey: String = ""): SourceMangaItem {
        val key = obj.optString("mangaKey").ifBlank { obj.optString("id", fallbackKey) }
        val genresList = buildList {
            obj.optJSONArray("genres")?.let { gArr ->
                for (i in 0 until gArr.length()) {
                    gArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        return SourceMangaItem(
            sourceId = id,
            sourceName = name,
            mangaKey = key,
            title = obj.optString("title").ifBlank { key.ifBlank { "Untitled" } },
            altTitle = obj.optString("altTitle"),
            coverUrl = obj.optString("coverUrl"),
            author = obj.optString("author"),
            artist = obj.optString("artist"),
            description = obj.optString("description"),
            genres = genresList,
            status = MangaStatus.fromString(obj.optString("status")),
            language = obj.optString("language").ifBlank { language },
            latestChapterName = obj.optString("latestChapterName")
        )
    }
}
