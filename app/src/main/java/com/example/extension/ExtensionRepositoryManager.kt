package com.example.extension

import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.network.HttpResponseValidator
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class BuiltInRepositorySpec(
    val name: String,
    val indexUrl: String
)

object BuiltInRepositories {
    val ALL: List<BuiltInRepositorySpec> = listOf(
        BuiltInRepositorySpec(
            name = "Keiyoushi Extensions",
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
        ),
        BuiltInRepositorySpec(
            name = "Cursed Manga Repo",
            indexUrl = "https://raw.githubusercontent.com/mojuru/cursed-manga-repo/repo/index.pb"
        ),
        BuiltInRepositorySpec(
            name = "FelipeGFA Extensões",
            indexUrl = "https://raw.githubusercontent.com/FelipeGFA/extensoes/refs/heads/repo/index.pb"
        ),
        BuiltInRepositorySpec(
            name = "LittleSurvival CopyManga",
            indexUrl = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/index.min.json"
        ),
        BuiltInRepositorySpec(
            name = "Suwayomi Tachiyomi Extensions",
            indexUrl = "https://raw.githubusercontent.com/suwayomi/tachiyomi-extension/repo/index.min.json"
        ),
        BuiltInRepositorySpec(
            name = "Kavita Tach-Extension",
            indexUrl = "https://raw.githubusercontent.com/Kareadita/tach-extension/repo/index.min.json"
        ),
        BuiltInRepositorySpec(
            name = "Yuzono Manga Repo",
            indexUrl = "https://raw.githubusercontent.com/yuzono/manga-repo/repo/index.min.json"
        ),
        BuiltInRepositorySpec(
            name = "Yuzono Cursed Manga",
            indexUrl = "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/index.pb"
        )
    )
}

@Serializable
data class RepoExportItem(
    val name: String,
    val indexUrl: String,
    val signingKeyFingerprint: String = "",
    val isTrusted: Boolean = true,
    val isEnabled: Boolean = true
)

@Serializable
data class RepoExportBundle(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val repositories: List<RepoExportItem>
)

data class RepoBatchRefreshReport(
    val totalAttempted: Int,
    val succeededRepos: Int,
    val failedRepos: Int,
    val totalPackagesLoaded: Int,
    val errorsByRepo: Map<String, String> = emptyMap()
)

class ExtensionRepositoryManager(
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun normalizeUrl(raw: String): String {
        return raw.trim().trimEnd('/')
    }

    fun canonicalUrlKey(raw: String): String {
        val norm = normalizeUrl(raw)
        val parsed = norm.toHttpUrlOrNull() ?: return norm.lowercase()
        val host = parsed.host.lowercase()
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        // Canonicalize GitHub raw aliases:
        // - https://github.com/<owner>/<repo>/raw/<branch>/<path...>
        // - https://raw.githubusercontent.com/<owner>/<repo>/refs/heads/<branch>/<path...>
        // - https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path...>
        if (host == "github.com" && segments.size >= 4 && segments[2].equals("raw", ignoreCase = true)) {
            val owner = segments[0].lowercase()
            val repo = segments[1].lowercase()
            val tail = segments.drop(3).joinToString("/").removePrefix("refs/heads/")
            return "https://raw.githubusercontent.com/$owner/$repo/$tail".lowercase()
        }
        if (host == "raw.githubusercontent.com" && segments.size >= 3) {
            val owner = segments[0].lowercase()
            val repo = segments[1].lowercase()
            val rest = segments.drop(2).joinToString("/").removePrefix("refs/heads/")
            return "https://raw.githubusercontent.com/$owner/$repo/$rest".lowercase()
        }
        return "${parsed.scheme.lowercase()}://$host/${segments.joinToString("/")}".lowercase()
    }

    /**
     * Seeds the 8 default built-in repositories without duplicates.
     * When [forceRestore] is false, seeds only if no built-in repositories currently exist
     * so user-deleted defaults stay removed until explicitly restored.
     */
    suspend fun seedDefaultRepositoriesIfMissing(forceRestore: Boolean = false): Int = withContext(Dispatchers.IO) {
        val existing = extensionDao.getReposOnce()
        if (!forceRestore && existing.any { it.isBuiltIn }) {
            return@withContext 0
        }
        val existingNormalized = existing.map { canonicalUrlKey(it.indexUrl) }.toMutableSet()
        var inserted = 0
        for (spec in BuiltInRepositories.ALL) {
            val normalized = normalizeUrl(spec.indexUrl)
            val key = canonicalUrlKey(spec.indexUrl)
            if (key !in existingNormalized) {
                val format = if (normalized.endsWith(".pb")) "index.pb" else "index.min.json"
                extensionDao.upsertRepo(
                    ExtensionRepoEntity(
                        name = spec.name,
                        displayName = spec.name,
                        indexUrl = spec.indexUrl,
                        isTrusted = true,
                        isEnabled = true,
                        isBuiltIn = true,
                        repoFormat = format
                    )
                )
                existingNormalized.add(normalized)
                inserted++
            }
        }
        inserted
    }

    suspend fun addRepository(
        rawUrl: String,
        customName: String = "",
        expectedFingerprint: String = ""
    ): Result<ExtensionRepoEntity> = withContext(Dispatchers.IO) {
        val trimmed = normalizeUrl(rawUrl)
        val parsed = trimmed.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid repository URL format."))
        if (!parsed.isHttps) {
            return@withContext Result.failure(
                SecurityException("Only HTTPS repository URLs are permitted for extension verification.")
            )
        }

        val canonicalNew = canonicalUrlKey(trimmed)
        val existing = extensionDao.getReposOnce().firstOrNull {
            canonicalUrlKey(it.indexUrl) == canonicalNew
        }
        if (existing != null) {
            return@withContext Result.failure(
                IllegalStateException("Repository '${existing.displayName.ifBlank { existing.name }}' is already added.")
            )
        }

        val detectedFormat = when {
            trimmed.endsWith(".pb", ignoreCase = true) -> "index.pb"
            trimmed.endsWith(".json", ignoreCase = true) -> "index.min.json"
            else -> "auto"
        }

        val repo = ExtensionRepoEntity(
            name = customName.ifBlank { parsed.host },
            displayName = customName.ifBlank { parsed.host },
            indexUrl = trimmed,
            signingKeyFingerprint = expectedFingerprint.trim(),
            isTrusted = expectedFingerprint.isNotBlank() || parsed.host == "raw.githubusercontent.com",
            isEnabled = true,
            isBuiltIn = false,
            repoFormat = detectedFormat
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
        var lastHttpStatus: Int? = null
        var responseContentType: String? = null
        var finalResolvedUrl: String = repo.indexUrl
        val repoLabel = repo.displayName.ifBlank { repo.name }
        try {
            val request = Request.Builder()
                .url(repo.indexUrl)
                .header("Accept", "application/json, application/x-protobuf, application/octet-stream, */*")
                .get()
                .build()

            val (rawBytes, validatedContentType) = httpClient.newCall(request).execute().use { resp ->
                lastHttpStatus = resp.code
                finalResolvedUrl = resp.request.url.toString()
                HttpResponseValidator.validateHttpIndexResponse(resp, repoLabel)
            }
            responseContentType = validatedContentType

            val parsedIndex = ExtensionIndexParser.parseIndexBytes(
                repoId = repo.id,
                indexUrl = finalResolvedUrl,
                rawBytes = rawBytes,
                contentType = responseContentType
            )
            val indexDigest = ExtensionIndexParser.sha256Hex(rawBytes)
            val companionMeta = fetchCompanionRepoJson(finalResolvedUrl)

            val resolvedFingerprint = repo.signingKeyFingerprint.ifBlank {
                parsedIndex.repoFingerprint?.ifBlank { null }
                    ?: companionMeta?.signingKeyFingerprint.orEmpty()
            }
            val actualFingerprint = parsedIndex.repoFingerprint?.ifBlank { null }
                ?: companionMeta?.signingKeyFingerprint.orEmpty()
            val fingerprintMatches = actualFingerprint.isBlank() ||
                repo.signingKeyFingerprint.isBlank() ||
                ExtensionSecurityManager.fingerprintsMatch(repo.signingKeyFingerprint, actualFingerprint)

            val existingPackagesById = extensionDao.getPackagesOnce().associateBy { it.pkgId }
            val merged = parsedIndex.packages.map { incoming ->
                val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                    format = ExtensionFormat.fromId(incoming.format),
                    versionName = incoming.versionName,
                    isRepoTrusted = repo.isTrusted && fingerprintMatches,
                    signatureMatch = fingerprintMatches,
                    explicitLibVersion = incoming.libVersion.takeIf { it.isNotBlank() },
                    pkgId = incoming.pkgId,
                    extensionName = incoming.name
                )
                val existing = existingPackagesById[incoming.pkgId]
                if (existing != null && (existing.isInstalled || existing.localApkPath.isNotBlank())) {
                    val hasUpdate = incoming.versionCode > existing.installedVersionCode && existing.isInstalled
                    existing.copy(
                        repoId = repo.id,
                        name = incoming.name,
                        author = incoming.author,
                        description = incoming.description,
                        language = incoming.language,
                        iconUrl = incoming.iconUrl.ifBlank { existing.iconUrl },
                        format = incoming.format,
                        adapterJson = if (existing.isInstalled && !hasUpdate) existing.adapterJson else incoming.adapterJson,
                        latestVersionCode = incoming.versionCode,
                        latestVersionName = incoming.versionName,
                        hasUpdate = hasUpdate,
                        signatureHash = incoming.signatureHash,
                        popularity = incoming.popularity,
                        permissionsSummary = incoming.permissionsSummary,
                        apkName = incoming.apkName,
                        apkUrl = incoming.apkUrl,
                        isNsfw = incoming.isNsfw,
                        hasReadme = incoming.hasReadme,
                        libVersion = compat.libVersion,
                        sourcesJson = incoming.sourcesJson,
                        compatibilityStatus = if (existing.localApkPath.isNotBlank()) existing.compatibilityStatus else compat.status.name,
                        compatibilityReason = if (existing.localApkPath.isNotBlank()) existing.compatibilityReason else compat.reason,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    incoming.copy(
                        compatibilityStatus = compat.status.name,
                        compatibilityReason = compat.reason
                    )
                }
            }

            extensionDao.deleteUninstalledFromRepo(repo.id)
            extensionDao.upsertPackages(merged)

            val resolvedDisplayName = companionMeta?.displayName?.ifBlank { companionMeta.name }
                ?.takeIf { it.isNotBlank() }
                ?: parsedIndex.repoName
                ?: repo.displayName.ifBlank { repo.name }

            val now = System.currentTimeMillis()
            extensionDao.upsertRepo(
                repo.copy(
                    name = resolvedDisplayName,
                    displayName = resolvedDisplayName,
                    website = companionMeta?.website?.takeIf { it.isNotBlank() }
                        ?: parsedIndex.repoWebsite?.takeIf { it.isNotBlank() }
                        ?: repo.website,
                    iconUrl = companionMeta?.iconUrl?.takeIf { it.isNotBlank() } ?: repo.iconUrl,
                    signingKeyFingerprint = resolvedFingerprint.ifBlank { indexDigest.take(16) },
                    isTrusted = fingerprintMatches && repo.isTrusted,
                    repoFormat = parsedIndex.formatLabel,
                    lastRefreshedAt = now,
                    lastSuccessAt = now,
                    lastHttpStatus = lastHttpStatus ?: 0,
                    extensionCount = merged.size,
                    lastError = if (fingerprintMatches) null else "Signing key fingerprint mismatch!"
                )
            )
            Result.success(merged.size)
        } catch (e: Exception) {
            val host = finalResolvedUrl.toHttpUrlOrNull()?.host ?: repo.indexUrl.toHttpUrlOrNull()?.host
            val formatted = if (e is IllegalArgumentException && e.message?.isNotBlank() == true) {
                e.message!!
            } else {
                NetworkErrorFormatter.describe(e, host)
            }
            extensionDao.upsertRepo(
                repo.copy(
                    lastRefreshedAt = System.currentTimeMillis(),
                    lastHttpStatus = lastHttpStatus ?: 0,
                    lastError = formatted
                )
            )
            Result.failure(IOException(formatted, e))
        }
    }

    suspend fun refreshAllEnabled(onlyFailed: Boolean = false): RepoBatchRefreshReport {
        val repos = extensionDao.getReposOnce().filter {
            it.isEnabled && (!onlyFailed || !it.lastError.isNullOrBlank())
        }
        var succeeded = 0
        var failed = 0
        var totalPackages = 0
        val errors = linkedMapOf<String, String>()
        for (repo in repos) {
            val res = refreshRepository(repo)
            if (res.isSuccess) {
                succeeded++
                totalPackages += res.getOrDefault(0)
            } else {
                failed++
                errors[repo.displayName.ifBlank { repo.name }] =
                    res.exceptionOrNull()?.message ?: "Refresh failed"
            }
        }
        return RepoBatchRefreshReport(
            totalAttempted = repos.size,
            succeededRepos = succeeded,
            failedRepos = failed,
            totalPackagesLoaded = totalPackages,
            errorsByRepo = errors
        )
    }

    suspend fun setRepoEnabled(repo: ExtensionRepoEntity, enabled: Boolean) {
        extensionDao.upsertRepo(repo.copy(isEnabled = enabled))
    }

    suspend fun setRepoTrusted(repo: ExtensionRepoEntity, trusted: Boolean) {
        extensionDao.upsertRepo(repo.copy(isTrusted = trusted))
    }

    suspend fun removeRepository(repo: ExtensionRepoEntity) {
        extensionDao.deleteUninstalledFromRepo(repo.id)
        extensionDao.deleteRepo(repo.id)
    }

    suspend fun exportRepositoriesJson(): String = withContext(Dispatchers.IO) {
        val items = extensionDao.getReposOnce().map {
            RepoExportItem(
                name = it.displayName.ifBlank { it.name },
                indexUrl = it.indexUrl,
                signingKeyFingerprint = it.signingKeyFingerprint,
                isTrusted = it.isTrusted,
                isEnabled = it.isEnabled
            )
        }
        json.encodeToString(RepoExportBundle(repositories = items))
    }

    suspend fun importRepositoriesJson(rawJson: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = json.decodeFromString<RepoExportBundle>(rawJson.trim())
            val existing = extensionDao.getReposOnce().map { canonicalUrlKey(it.indexUrl) }.toMutableSet()
            var imported = 0
            for (item in bundle.repositories) {
                val url = normalizeUrl(item.indexUrl)
                val key = canonicalUrlKey(url)
                val parsed = url.toHttpUrlOrNull() ?: continue
                if (!parsed.isHttps || key in existing) continue
                extensionDao.upsertRepo(
                    ExtensionRepoEntity(
                        name = item.name.ifBlank { parsed.host },
                        displayName = item.name.ifBlank { parsed.host },
                        indexUrl = url,
                        signingKeyFingerprint = item.signingKeyFingerprint,
                        isTrusted = item.isTrusted,
                        isEnabled = item.isEnabled,
                        repoFormat = if (url.endsWith(".pb")) "index.pb" else "index.min.json"
                    )
                )
                existing.add(key)
                imported++
            }
            imported
        }
    }

    private fun fetchCompanionRepoJson(indexUrl: String): RepoCompanionMeta? {
        val baseDir = indexUrl.substringBeforeLast("/", "")
        if (baseDir.isBlank() || !ExtensionSecurityManager.isSecureHttpsUrl(baseDir)) return null
        return runCatching {
            val req = Request.Builder()
                .url("$baseDir/repo.json")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val jsonObj = HttpResponseValidator.parseJsonObjectStrict(resp, "Repo metadata")
                ExtensionIndexParser.parseRepoJson(jsonObj.toString())
            }
        }.getOrNull()
    }
}
