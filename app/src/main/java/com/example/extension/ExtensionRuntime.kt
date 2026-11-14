package com.example.extension

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionSourceMeta
import com.example.source.DeclarativeJsonSourceAdapter
import com.example.source.IpcCompanionSourceAdapter
import com.example.source.MangaSourceAdapter
import com.example.source.MihonSourceAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    val canExecuteInKiyomi: Boolean get() = canExecuteInKoma
}

data class ManifestValidationResult(
    val isValid: Boolean,
    val errorReason: String? = null
)

/**
 * Validates declarative `koma_json_v1` / `kiyomi_json_v1` extension adapter manifests.
 * Enforces version bounds, HTTPS-only origins, and at least one browsable/searchable endpoint.
 */
object KomaManifestValidator {
    const val KOMA_API_VERSION = 1

    fun validateDescriptor(descriptor: JSONObject): ManifestValidationResult {
        if (descriptor.has("apiVersion")) {
            val apiVersion = descriptor.optInt("apiVersion", KOMA_API_VERSION)
            if (apiVersion < 1 || apiVersion > KOMA_API_VERSION) {
                return ManifestValidationResult(
                    false,
                    "Unsupported Kiyomi declarative API version v$apiVersion (supported: v1–v$KOMA_API_VERSION)."
                )
            }
        }
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
 * Compatibility translation layer between persisted extension metadata and Kiyomi's
 * [MangaSourceAdapter] interface.
 */
object ExtensionCompatibilityTranslator {

    fun translateToSourceAdapter(
        pkg: ExtensionPackageEntity,
        httpClient: OkHttpClient,
        context: Context? = null,
        mihonLoader: MihonExtensionLoader? = null
    ): Result<MangaSourceAdapter> {
        if (pkg.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id) {
            val appContext = context ?: return Result.failure(
                IllegalStateException("Android Context is required to bind Companion IPC provider for ${pkg.name}.")
            )
            return runCatching {
                val cfg = JSONObject(pkg.adapterJson.ifBlank { "{}" })
                val authority = cfg.optString("authority").trim()
                require(authority.isNotBlank()) { "Missing Companion IPC ContentProvider authority." }
                val targetPackage = cfg.optString("packageName").ifBlank { pkg.pkgId }
                IpcCompanionSourceAdapter(
                    context = appContext,
                    id = pkg.pkgId,
                    name = pkg.name,
                    language = pkg.language,
                    authority = authority,
                    targetPackageName = targetPackage
                )
            }
        }

        if (pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id) {
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

        // Native Mihon APK loaded via MihonExtensionLoader
        val loaded = mihonLoader?.state?.value?.resultsByPkg?.get(pkg.pkgId)
        if (loaded is ExtensionLoadResult.Loaded && loaded.adapters.isNotEmpty()) {
            return Result.success(loaded.adapters.first())
        }
        return Result.failure(
            IllegalStateException(
                "${pkg.name} requires OS installation and explicit signer trust approval before its Mihon sources can be loaded."
            )
        )
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
 * Exposes sandboxed Kiyomi declarative adapters, Companion IPC adapters, and
 * verified & trusted installed Mihon extension adapters from [MihonExtensionLoader].
 */
class ExtensionSourceRegistry(
    private val extensionDao: ExtensionDao,
    private val prefsRepo: AppPreferencesRepository,
    private val httpClient: OkHttpClient,
    private val context: Context? = null,
    private val mihonLoader: MihonExtensionLoader? = null
) {
    private val mihonStateFlow: Flow<MihonLoadedState> =
        mihonLoader?.state ?: MutableStateFlow(MihonLoadedState())

    val activeDeclarativeSources: Flow<List<MangaSourceAdapter>> = combine(
        extensionDao.observeInstalledEnabledPackages(),
        prefsRepo.preferences,
        mihonStateFlow
    ) { pkgs, prefs, mihonState ->
        val declared = pkgs.filter {
            it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id ||
                it.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id
        }.mapNotNull { pkg ->
            if (pkg.pkgId in prefs.disabledSourceIds) return@mapNotNull null
            ExtensionCompatibilityTranslator.translateToSourceAdapter(pkg, httpClient, context, mihonLoader).getOrNull()
        }
        val mihon = mihonState.activeAdapters.filter { adapter ->
            val pkgDisabled = (adapter as? MihonSourceAdapter)?.extensionPackageName in prefs.disabledSourceIds
            adapter.id !in prefs.disabledSourceIds && !pkgDisabled
        }
        (declared + mihon).distinctBy { it.id }
    }

    val verifiedExtensionDescriptors: Flow<List<LoadedExtensionDescriptor>> =
        extensionDao.observePackages().map { pkgs ->
            pkgs.filter {
                it.isInstalled ||
                    it.localApkPath.isNotBlank() ||
                    it.compatibilityStatus == ExtensionCompatibilityStatus.COMPANION_IPC_READY.name ||
                    it.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name ||
                    it.compatibilityStatus == ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE.name
            }.map { ExtensionLoader.loadDescriptor(it) }
        }
}

/**
 * Detects available updates across installed/verified extensions and executes batch updates.
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
            val result = when {
                pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id ->
                    packageManager.installKiyomiAdapter(pkg)
                pkg.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id -> {
                    val authority = runCatching {
                        JSONObject(pkg.adapterJson).optString("authority")
                    }.getOrDefault("")
                    if (authority.isNotBlank()) {
                        packageManager.activateCompanionIpc(pkg, authority)
                    } else {
                        Result.failure(UnsupportedOperationException("Missing IPC authority"))
                    }
                }
                pkg.localApkPath.isNotBlank() || pkg.isInstalled ->
                    packageManager.downloadAndVerifyMihonApk(pkg).map { }
                else ->
                    Result.failure(UnsupportedOperationException("No local adapter update available"))
            }
            if (result.isSuccess) count++
        }
        return count
    }
}
