package com.example.extension

import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionSourceMeta
import com.example.source.DeclarativeJsonSourceAdapter
import com.example.source.MangaSourceAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONObject

data class LoadedExtensionDescriptor(
    val pkg: ExtensionPackageEntity,
    val sources: List<ExtensionSourceMeta>,
    val compatibility: ExtensionCompatibilityStatus,
    val canExecuteInKoma: Boolean
)

/**
 * Loads parsed extension metadata, sub-source catalogs (`ExtensionSourceMeta`),
 * and compatibility descriptors for installed and verified packages.
 */
object ExtensionLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadDescriptor(pkg: ExtensionPackageEntity): LoadedExtensionDescriptor {
        val sources = runCatching {
            if (pkg.sourcesJson.isBlank()) emptyList()
            else json.decodeFromString<List<ExtensionSourceMeta>>(pkg.sourcesJson)
        }.getOrDefault(emptyList())

        val status = runCatching {
            ExtensionCompatibilityStatus.valueOf(pkg.compatibilityStatus)
        }.getOrDefault(ExtensionCompatibilityStatus.UNKNOWN)

        return LoadedExtensionDescriptor(
            pkg = pkg,
            sources = sources,
            compatibility = status,
            canExecuteInKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id &&
                status == ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON
        )
    }
}

/**
 * Exposes sandboxed Koma declarative sources alongside metadata-only Mihon sub-sources
 * so the Sources screen and Global Search stay consistent with user enable/disable preferences.
 */
class ExtensionSourceRegistry(
    private val extensionDao: ExtensionDao,
    private val prefsRepo: AppPreferencesRepository,
    private val httpClient: OkHttpClient
) {
    val activeDeclarativeSources: Flow<List<MangaSourceAdapter>> = combine(
        extensionDao.observeActiveKomaPackages(),
        prefsRepo.preferences
    ) { pkgs, prefs ->
        pkgs.mapNotNull { pkg ->
            if (pkg.pkgId in prefs.disabledSourceIds) return@mapNotNull null
            runCatching {
                DeclarativeJsonSourceAdapter(
                    id = pkg.pkgId,
                    name = pkg.name,
                    language = pkg.language,
                    descriptor = JSONObject(pkg.adapterJson),
                    httpClient = httpClient
                )
            }.getOrNull()
        }
    }

    val verifiedExtensionDescriptors: Flow<List<LoadedExtensionDescriptor>> =
        extensionDao.observePackages().map { pkgs ->
            pkgs.filter { it.isInstalled || it.localApkPath.isNotBlank() }
                .map { ExtensionLoader.loadDescriptor(it) }
        }
}

/**
 * Detects available updates across installed extensions and executes batch updates.
 */
class ExtensionUpdateManager(
    private val packageManager: ExtensionPackageManager,
    private val extensionDao: ExtensionDao
) {
    val updatablePackages: Flow<List<ExtensionPackageEntity>> =
        extensionDao.observePackages().map { list ->
            list.filter { it.isInstalled && it.hasUpdate }
        }

    suspend fun updateAll(packages: List<ExtensionPackageEntity>): Int {
        var count = 0
        packages.filter { it.hasUpdate }.forEach { pkg ->
            val result = if (pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id) {
                packageManager.installKomaAdapter(pkg)
            } else if (pkg.localApkPath.isNotBlank()) {
                packageManager.downloadAndVerifyMihonApk(pkg).map { }
            } else {
                Result.failure(UnsupportedOperationException("No local adapter update available"))
            }
            if (result.isSuccess) count++
        }
        return count
    }
}
