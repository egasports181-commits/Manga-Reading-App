package com.example.data.repository

import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.domain.model.ExtensionFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

class ExtensionRepository(
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient
) {
    val repos: Flow<List<ExtensionRepoEntity>> = extensionDao.observeRepos()
    val packages: Flow<List<ExtensionPackageEntity>> = extensionDao.observePackages()

    suspend fun addRepository(
        rawUrl: String,
        customName: String = "",
        expectedFingerprint: String = ""
    ): Result<ExtensionRepoEntity> = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val parsed = trimmed.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid repository URL format."))
        if (!parsed.isHttps) {
            return@withContext Result.failure(
                SecurityException("Only HTTPS repository URLs are permitted for extension verification.")
            )
        }

        val repo = ExtensionRepoEntity(
            name = customName.ifBlank { parsed.host },
            indexUrl = trimmed,
            signingKeyFingerprint = expectedFingerprint.trim(),
            isTrusted = expectedFingerprint.isNotBlank()
        )
        val id = extensionDao.upsertRepo(repo)
        val saved = repo.copy(id = id)
        val refreshResult = refreshRepository(saved)
        if (refreshResult.isFailure) {
            Result.failure(refreshResult.exceptionOrNull() ?: IOException("Failed to fetch repository index"))
        } else {
            Result.success(extensionDao.getRepoById(id) ?: saved)
        }
    }

    suspend fun refreshRepository(repo: ExtensionRepoEntity): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(repo.indexUrl)
                .header("User-Agent", "KomaAndroidReader/1.0")
                .get()
                .build()

            val body = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Repository returned HTTP ${resp.code}")
                }
                resp.body?.string() ?: throw IOException("Empty repository index response")
            }

            val indexDigest = sha256Hex(body)
            val existingPackages = mutableMapOf<String, ExtensionPackageEntity>()
            val parsedPackages = parseRepositoryPayload(repo.id, body, indexDigest)

            // Preserve installed state and detect available updates
            val merged = parsedPackages.map { incoming ->
                val existing = extensionDao.getPackage(incoming.pkgId)
                if (existing != null && existing.isInstalled) {
                    val hasUpdate = incoming.versionCode > existing.installedVersionCode
                    existing.copy(
                        repoId = repo.id,
                        name = incoming.name,
                        author = incoming.author,
                        description = incoming.description,
                        language = incoming.language,
                        iconUrl = incoming.iconUrl,
                        format = incoming.format,
                        adapterJson = if (!hasUpdate) existing.adapterJson else incoming.adapterJson,
                        latestVersionCode = incoming.versionCode,
                        latestVersionName = incoming.versionName,
                        hasUpdate = hasUpdate,
                        signatureHash = incoming.signatureHash,
                        popularity = incoming.popularity,
                        permissionsSummary = incoming.permissionsSummary
                    )
                } else {
                    existingPackages[incoming.pkgId] = incoming
                    incoming
                }
            }

            extensionDao.deleteUninstalledFromRepo(repo.id)
            extensionDao.upsertPackages(merged)

            val resolvedName = extractRepoName(body) ?: repo.name
            val fpVerified = repo.signingKeyFingerprint.isBlank() ||
                repo.signingKeyFingerprint.equals(indexDigest, ignoreCase = true)

            extensionDao.upsertRepo(
                repo.copy(
                    name = resolvedName,
                    signingKeyFingerprint = repo.signingKeyFingerprint.ifBlank { indexDigest.take(16) },
                    isTrusted = fpVerified && repo.isTrusted,
                    lastRefreshedAt = System.currentTimeMillis(),
                    lastError = if (fpVerified) null else "Signing fingerprint mismatch! Verify repository authenticity."
                )
            )
            Result.success(merged.size)
        } catch (e: Exception) {
            extensionDao.upsertRepo(
                repo.copy(
                    lastRefreshedAt = System.currentTimeMillis(),
                    lastError = e.message ?: "Failed to fetch repository index"
                )
            )
            Result.failure(e)
        }
    }

    suspend fun setRepoTrusted(repo: ExtensionRepoEntity, trusted: Boolean) {
        extensionDao.upsertRepo(repo.copy(isTrusted = trusted))
    }

    suspend fun removeRepository(repo: ExtensionRepoEntity) {
        extensionDao.deleteUninstalledFromRepo(repo.id)
        extensionDao.deleteRepo(repo.id)
    }

    suspend fun installExtension(pkg: ExtensionPackageEntity): Result<Unit> {
        if (pkg.format != ExtensionFormat.KOMA_JSON_ADAPTER.id) {
            return Result.failure(
                UnsupportedOperationException(
                    "Mihon/Tachiyomi APK plugins cannot be silently installed or executed inside this sandboxed runtime. Use a Koma Declarative JSON adapter repository instead."
                )
            )
        }
        val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
        if (repo != null && !repo.isTrusted) {
            return Result.failure(
                SecurityException("Repository '${repo.name}' is not marked as trusted. Inspect and trust the repository before installing.")
            )
        }
        val installed = pkg.copy(
            isInstalled = true,
            isEnabled = true,
            installedVersionCode = pkg.latestVersionCode.coerceAtLeast(pkg.versionCode),
            versionCode = pkg.latestVersionCode.coerceAtLeast(pkg.versionCode),
            versionName = pkg.latestVersionName.ifBlank { pkg.versionName },
            hasUpdate = false
        )
        extensionDao.upsertPackage(installed)
        return Result.success(Unit)
    }

    suspend fun updateAllExtensions(packages: List<ExtensionPackageEntity>): Int {
        var updated = 0
        packages.filter { it.isInstalled && it.hasUpdate && it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id }
            .forEach { pkg ->
                if (installExtension(pkg).isSuccess) updated++
            }
        return updated
    }

    suspend fun setExtensionEnabled(pkgId: String, enabled: Boolean) {
        extensionDao.setPackageEnabled(pkgId, enabled)
    }

    suspend fun uninstallExtension(pkgId: String) {
        extensionDao.uninstallPackage(pkgId)
    }

    private fun extractRepoName(payload: String): String? {
        return runCatching {
            val trimmed = payload.trim()
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optString("name").takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
    }

    private fun parseRepositoryPayload(
        repoId: Long,
        payload: String,
        digest: String
    ): List<ExtensionPackageEntity> {
        val trimmed = payload.trim()
        // Case 1: Koma Declarative Repository JSON Object
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val extArray = root.optJSONArray("extensions") ?: JSONArray()
            return buildList {
                for (i in 0 until extArray.length()) {
                    val obj = extArray.getJSONObject(i)
                    val pkgId = obj.optString("id").ifBlank { "koma.ext.$i" }
                    val vCode = obj.optInt("versionCode", 1)
                    val vName = obj.optString("versionName", "1.0.0")
                    val adapterObj = obj.optJSONObject("adapter")
                    val format = if (adapterObj != null) {
                        ExtensionFormat.KOMA_JSON_ADAPTER.id
                    } else {
                        ExtensionFormat.EXTERNAL_APK_INCOMPATIBLE.id
                    }
                    add(
                        ExtensionPackageEntity(
                            pkgId = pkgId,
                            repoId = repoId,
                            name = obj.optString("name", pkgId),
                            versionName = vName,
                            versionCode = vCode,
                            author = obj.optString("author", "Unknown"),
                            description = obj.optString("description", "Declarative source extension"),
                            language = obj.optString("lang", "en"),
                            iconUrl = obj.optString("iconUrl"),
                            format = format,
                            adapterJson = adapterObj?.toString() ?: "",
                            latestVersionCode = vCode,
                            latestVersionName = vName,
                            signatureHash = obj.optString("sha256", digest.take(16)),
                            popularity = obj.optInt("popularity", 100 - i),
                            permissionsSummary = if (adapterObj != null) {
                                "HTTPS requests to ${adapterObj.optString("baseUrl", "declared host")} only"
                            } else {
                                "External APK binary (Incompatible with sandboxed runtime)"
                            }
                        )
                    )
                }
            }
        }

        // Case 2: Standard Mihon/Tachiyomi `index.min.json` JSON Array
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pkg = obj.optString("pkg", "tachiyomi.ext.$i")
                    val vCode = obj.optInt("code", 1)
                    val vName = obj.optString("version", "1.0")
                    val sourcesArr = obj.optJSONArray("sources")
                    val sourceCount = sourcesArr?.length() ?: 0
                    val rawName = obj.optString("name", pkg).removePrefix("Tachiyomi: ")
                    add(
                        ExtensionPackageEntity(
                            pkgId = pkg,
                            repoId = repoId,
                            name = rawName,
                            versionName = vName,
                            versionCode = vCode,
                            author = "External APK Author",
                            description = "Mihon/Tachiyomi APK extension ($sourceCount sources). Parsed metadata only; Android APK plugins cannot execute inside Koma.",
                            language = obj.optString("lang", "en"),
                            iconUrl = "",
                            format = ExtensionFormat.EXTERNAL_APK_INCOMPATIBLE.id,
                            adapterJson = "",
                            latestVersionCode = vCode,
                            latestVersionName = vName,
                            signatureHash = obj.optString("apk", "").take(16),
                            popularity = sourceCount * 10,
                            permissionsSummary = "APK package (requires native Mihon APK runtime; blocked for safety)"
                        )
                    )
                }
            }
        }

        throw IllegalArgumentException("Unsupported repository index JSON structure.")
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
