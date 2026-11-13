package com.example.data.repository

import com.example.data.local.dao.TrackingDao
import com.example.data.local.entity.TrackingEntryEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.RemoteTrackerSearchResult
import com.example.domain.model.SyncState
import com.example.domain.model.TrackerService
import com.example.domain.model.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class TrackingRepository(
    private val trackingDao: TrackingDao,
    private val prefsRepo: AppPreferencesRepository,
    private val httpClient: OkHttpClient
) {
    fun observeTrackingForManga(mangaId: String): Flow<List<TrackingEntryEntity>> =
        trackingDao.observeTrackingForManga(mangaId)

    suspend fun getToken(service: TrackerService): String {
        val prefs = prefsRepo.preferences.first()
        return when (service) {
            TrackerService.ANILIST -> prefs.anilistToken
            TrackerService.MYANIMELIST -> prefs.malToken
            TrackerService.KITSU -> prefs.kitsuToken
            else -> ""
        }
    }

    suspend fun isConnected(service: TrackerService): Boolean = getToken(service).isNotBlank()

    suspend fun searchRemote(
        service: TrackerService,
        query: String
    ): Result<List<RemoteTrackerSearchResult>> = withContext(Dispatchers.IO) {
        val token = getToken(service)
        if (!service.supportsOAuthTokenSync || token.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("${service.displayName} is not connected with an OAuth/API token. You can still use Manual Local Tracking.")
            )
        }
        try {
            when (service) {
                TrackerService.ANILIST -> {
                    val gql = """
                        query (${'$'}search: String) {
                          Page(perPage: 10) {
                            media(search: ${'$'}search, type: MANGA) {
                              id
                              title { romaji english }
                              chapters
                              coverImage { medium }
                            }
                          }
                        }
                    """.trimIndent()
                    val payload = JSONObject().apply {
                        put("query", gql)
                        put("variables", JSONObject().put("search", query))
                    }
                    val req = Request.Builder()
                        .url("https://graphql.anilist.co")
                        .header("Authorization", "Bearer $token")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val body = execute(req)
                    val list = JSONObject(body).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
                    val results = (0 until list.length()).map { i ->
                        val m = list.getJSONObject(i)
                        val titleObj = m.getJSONObject("title")
                        RemoteTrackerSearchResult(
                            remoteMediaId = m.getInt("id").toString(),
                            title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") },
                            totalChapters = m.optInt("chapters", 0),
                            coverUrl = m.optJSONObject("coverImage")?.optString("medium").orEmpty()
                        )
                    }
                    Result.success(results)
                }

                TrackerService.KITSU -> {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val req = Request.Builder()
                        .url("https://kitsu.io/api/edge/manga?filter[text]=$encoded&page[limit]=10")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val arr = JSONObject(execute(req)).optJSONArray("data") ?: JSONArray()
                    val results = (0 until arr.length()).map { i ->
                        val item = arr.getJSONObject(i)
                        val attrs = item.getJSONObject("attributes")
                        RemoteTrackerSearchResult(
                            remoteMediaId = item.getString("id"),
                            title = attrs.optString("canonicalTitle"),
                            totalChapters = attrs.optInt("chapterCount", 0)
                        )
                    }
                    Result.success(results)
                }

                TrackerService.MYANIMELIST -> {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val req = Request.Builder()
                        .url("https://api.myanimelist.net/v2/manga?q=$encoded&limit=10&fields=num_chapters")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val arr = JSONObject(execute(req)).optJSONArray("data") ?: JSONArray()
                    val results = (0 until arr.length()).map { i ->
                        val node = arr.getJSONObject(i).getJSONObject("node")
                        RemoteTrackerSearchResult(
                            remoteMediaId = node.getInt("id").toString(),
                            title = node.getString("title"),
                            totalChapters = node.optInt("num_chapters", 0)
                        )
                    }
                    Result.success(results)
                }

                else -> Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveLocalAndOptionalSync(entry: TrackingEntryEntity): Result<TrackingEntryEntity> =
        withContext(Dispatchers.IO) {
            val service = TrackerService.fromId(entry.serviceId)
            val token = getToken(service)
            if (!service.supportsOAuthTokenSync || token.isBlank() || entry.remoteMediaId.isBlank()) {
                val localEntry = entry.copy(
                    syncState = SyncState.LOCAL_ONLY.name,
                    lastSyncedAt = System.currentTimeMillis()
                )
                trackingDao.upsertTracking(localEntry)
                return@withContext Result.success(localEntry)
            }

            try {
                if (service == TrackerService.ANILIST) {
                    val statusGql = when (TrackingStatus.fromId(entry.status)) {
                        TrackingStatus.READING -> "CURRENT"
                        TrackingStatus.COMPLETED -> "COMPLETED"
                        TrackingStatus.ON_HOLD -> "PAUSED"
                        TrackingStatus.DROPPED -> "DROPPED"
                        TrackingStatus.PLAN_TO_READ -> "PLANNING"
                    }
                    val gql = """
                        mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}score: Float) {
                          SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, score: ${'$'}score) {
                            id progress
                          }
                        }
                    """.trimIndent()
                    val payload = JSONObject().apply {
                        put("query", gql)
                        put(
                            "variables",
                            JSONObject().apply {
                                put("mediaId", entry.remoteMediaId.toIntOrNull() ?: 0)
                                put("status", statusGql)
                                put("progress", entry.chaptersRead)
                                put("score", entry.score)
                            }
                        )
                    }
                    val req = Request.Builder()
                        .url("https://graphql.anilist.co")
                        .header("Authorization", "Bearer $token")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    execute(req)
                }
                val synced = entry.copy(
                    syncState = SyncState.SYNCED.name,
                    lastSyncedAt = System.currentTimeMillis()
                )
                trackingDao.upsertTracking(synced)
                Result.success(synced)
            } catch (e: Exception) {
                val conflicted = entry.copy(syncState = SyncState.CONFLICT.name)
                trackingDao.upsertTracking(conflicted)
                Result.failure(e)
            }
        }

    suspend fun resolveConflictKeepLocal(entry: TrackingEntryEntity): Result<TrackingEntryEntity> =
        saveLocalAndOptionalSync(entry.copy(syncState = SyncState.LOCAL_AHEAD.name))

    suspend fun deleteTracking(id: Long) = trackingDao.deleteTracking(id)

    private fun execute(req: Request): String {
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty response")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(150)}")
            return body
        }
    }
}
