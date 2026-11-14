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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

data class LoadedExtensionDescriptor(
    val pkg: ExtensionPackageEntity,
    val sources: List<ExtensionSourceMeta>,
    val compatibility: ExtensionCompatibilityStatus,
    val lifecycle: ExtensionLifecycleSummary,
    val canExecuteInKoma: Boolean
)

data class ManifestValidationResult(
    val isValid: Boolean,
    val errorReason: String? = null
)

/**
 * Validates declarative `koma_json_v1` extension adapter manifests.
 * Enforces HTTPS-only origins and at least one browsable/searchable endpoint.
 */
object KomaManifestValidator {
    fun validateDescriptor(descriptor: JSONObject): ManifestValidationResult {
        val baseUrl = descriptor.optString("baseUrl", "").trim()
        val parsed = baseUrl.toHttpUrlOrNull()
            ?: return ManifestValidationResult(false, "Missing or invalid 'baseUrl'.")
        if (!parsed.isHttps) {
            return ManifestValidationResult(false, "Only HTTPS 'baseUrl' origins are permitted.")
        }
        val hasEndpoint = listOf(
            "popularEndpoint",
            "latestEndpoint",
            "searchEndpoint",
            "detailsEndpoint",
            "chaptersEndpoint",
            "pagesEndpoint"
        ).any { descriptor.optString(it).isNotBlank() }
        if (!hasEndpoint) {
            return ManifestValidationResult(
                false,
                "Manifest must declare at least one endpoint (popularEndpoint, latestEndpoint, or searchEndpoint)."
            )
        }
        return ManifestValidationResult(true)
    }

    fun validateJsonString(rawJson: String): ManifestValidationResult {
        if (rawJson.isBlank()) {
            return ManifestValidationResult(false, "Adapter JSON descriptor is empty.")
        }
        return runCatching {
            validateDescriptor(JSONObject(rawJson))
        }.getOrElse {
            ManifestValidationResult(false, "Malformed JSON adapter descriptor: ${it.message}")
        }
    }
}

/**
 * Compatibility translation layer between persisted extension metadata and Koma's
 * [MangaSourceAdapter] interface. Translates validated `koma_json_v1` declarative manifests
 * into sandboxed [DeclarativeJsonSourceAdapter] instances; refuses native DEX APK packages
 * without executing any untrusted bytecode.
 */
object ExtensionCompatibilityTranslator {

    fun translateToSourceAdapter(
        pkg: ExtensionPackageEntity,
        httpClient: OkHttpClient
    ): Result<MangaSourceAdapter> {
        if (pkg.format != ExtensionFormat.KOMA_JSON_ADAPTER.id) {
            return Result.failure(
                UnsupportedOperationException(
                    "${pkg.name} is a native Mihon/Tachiyomi APK (${pkg.libVersion.ifBlank { "DEX" }}). " +
                        "In-app DEX loading is disallowed; provide a koma_json_v1 declarative manifest to activate."
                )
            )
        }
        val validation = KomaManifestValidator.validateJsonString(pkg.adapterJson)
        if (!validation.isValid) {
            return Result.failure(
                IllegalArgumentException(validation.errorReason ?: "Invalid koma_json_v1 manifest.")
            )
        }
        return runCatching {
            DeclarativeJsonSourceAdapter(
                id = pkg.pkgId,
                name = pkg.name,
                language = pkg.language,
                descriptor = JSONObject(pkg.adapterJson),
                httpClient = httpClient
            )
        }
    }
}

/**
 * Loads parsed extension metadata, sub-source catalogs (`ExtensionSourceMeta`),
 * and lifecycle/compatibility descriptors for installed and verified packages.
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

        val lifecycle = ExtensionCompatibilityResolver.resolveLifecycle(pkg)

        return LoadedExtensionDescriptor(
            pkg = pkg,
            sources = sources,
            compatibility = status,
            lifecycle = lifecycle,
            canExecuteInKoma = lifecycle.isCompatible
        )
    }
}

/**
 * Exposes sandboxed Koma declarative sources alongside metadata-only / verified Mihon descriptors
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
            ExtensionCompatibilityTranslator.translateToSourceAdapter(pkg, httpClient).getOrNull()
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
            list.filter { (it.isInstalled || it.localApkPath.isNotBlank()) && it.hasUpdate }
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
