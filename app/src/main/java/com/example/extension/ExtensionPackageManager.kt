package com.example.extension

import android.content.Context
import android.content.Intent
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ApkIncompatibilityCategory
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionSourceMeta
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages extension verification (`apksig` v1/v2/v3/v3.1), explicit signer trust,
 * OS-consented Android package installation (`PackageInstaller`), Mihon DEX extension
 * loading via [MihonExtensionLoader], Companion IPC discovery, declarative JSON adapters,
 * and install audit history.
 */
class ExtensionPackageManager(
    private val context: Context?,
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient,
    private val trustManager: ExtensionTrustManager? = context?.let { ExtensionTrustManager(it) },
    private val installer: ExtensionInstaller? = context?.let { ExtensionInstaller(it) },
    private val mihonLoader: MihonExtensionLoader? = if (context != null && trustManager != null) {
        MihonExtensionLoader(context, httpClient, trustManager)
    } else null
) {
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val jsonSerializer = Json { encodeDefaults = true }

    @Deprecated(
        "Use installKiyomiAdapter instead",
        replaceWith = ReplaceWith("installKiyomiAdapter(pkg)")
    )
    suspend fun installKomaAdapter(pkg: ExtensionPackageEntity): Result<Unit> =
        installKiyomiAdapter(pkg)

    suspend fun installKiyomiAdapter(pkg: ExtensionPackageEntity): Result<Unit> {
        if (pkg.format != ExtensionFormat.KOMA_JSON_ADAPTER.id) {
            val msg = "Use Download & Verify APK to install native Mihon APK '${pkg.name}'."
            recordHistory(pkg, "INSTALL", "REJECTED", msg)
            return Result.failure(UnsupportedOperationException(msg))
        }
        val manifestCheck = KomaManifestValidator.validateJsonString(pkg.adapterJson)
        if (!manifestCheck.isValid) {
            val msg = manifestCheck.errorReason ?: "Invalid koma_json_v1 declarative manifest."
            recordHistory(pkg, "INSTALL", "FAILED", msg)
            extensionDao.upsertPackage(pkg.copy(installError = msg, updatedAt = System.currentTimeMillis()))
            return Result.failure(IllegalArgumentException(msg))
        }
        val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
        if (repo != null && !repo.isTrusted) {
            val msg = "Repository '${repo.name}' is not marked as trusted. Trust the repository before installing."
            recordHistory(pkg, "INSTALL", "BLOCKED", msg)
            return Result.failure(SecurityException(msg))
        }
        val installed = pkg.copy(
            isInstalled = true,
            isEnabled = true,
            installedVersionCode = pkg.latestVersionCode.coerceAtLeast(pkg.versionCode),
            versionCode = pkg.latestVersionCode.coerceAtLeast(pkg.versionCode),
            versionName = pkg.latestVersionName.ifBlank { pkg.versionName },
            hasUpdate = false,
            compatibilityStatus = ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON.name,
            compatibilityReason = "Compatible, installed, and active inside Kiyomi's declarative JSON sandbox (koma_json_v1, API v1).",
            installError = null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(installed)
        recordHistory(installed, "INSTALL", "ACTIVE", "Activated Kiyomi declarative source v${installed.versionName}")
        return Result.success(Unit)
    }

    /**
     * Option A: Activates a verified out-of-process Companion IPC extension (`koma_ipc_v1`).
     */
    suspend fun activateCompanionIpc(
        pkg: ExtensionPackageEntity,
        authority: String,
        targetPackageName: String = pkg.pkgId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanAuthority = authority.trim()
        if (cleanAuthority.isBlank()) {
            val msg = "Missing Companion IPC ContentProvider authority for ${pkg.name}."
            recordHistory(pkg, "ACTIVATE_IPC", "FAILED", msg)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }
        val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
        if (repo != null && !repo.isTrusted) {
            val msg = "Repository '${repo.name}' is untrusted. Trust repository before activating Companion IPC."
            recordHistory(pkg, "ACTIVATE_IPC", "BLOCKED", msg)
            return@withContext Result.failure(SecurityException(msg))
        }

        val appCtx = context
        var detectedSig = pkg.installedSignatureSha256
        if (appCtx != null && repo != null && repo.signingKeyFingerprint.isNotBlank()) {
            val discovered = ExtensionSecurityManager.discoverInstalledExtensionPackages(
                appCtx,
                mapOf(targetPackageName to repo.signingKeyFingerprint)
            ).firstOrNull { it.packageName == targetPackageName || it.ipcAuthority == cleanAuthority }
            if (discovered != null) {
                detectedSig = discovered.signatureSha256
                if (!discovered.matchesExpectedFingerprint) {
                    val msg = "Cat B (Signature Mismatch): Installed companion '$targetPackageName' certificate does not match repository fingerprint."
                    extensionDao.upsertPackage(
                        pkg.copy(
                            isInstalled = false,
                            isEnabled = false,
                            compatibilityStatus = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH.name,
                            compatibilityReason = msg,
                            installError = msg,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    recordHistory(pkg, "ACTIVATE_IPC", "SIGNATURE_MISMATCH", msg)
                    return@withContext Result.failure(SecurityException(msg))
                }
            }
        }

        val configJson = JSONObject()
            .put("authority", cleanAuthority)
            .put("packageName", targetPackageName)
            .toString()

        val activated = pkg.copy(
            format = ExtensionFormat.COMPANION_IPC_EXTENSION.id,
            adapterJson = configJson,
            isInstalled = true,
            isEnabled = true,
            installedVersionCode = pkg.latestVersionCode.coerceAtLeast(pkg.versionCode),
            hasUpdate = false,
            installedSignatureSha256 = detectedSig,
            libVersion = "koma-ipc-1",
            compatibilityStatus = ExtensionCompatibilityStatus.COMPANION_IPC_READY.name,
            compatibilityReason = "Active Companion IPC ('$cleanAuthority' via '$targetPackageName').",
            installError = null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(activated)
        recordHistory(activated, "ACTIVATE_IPC", "ACTIVE", activated.compatibilityReason)
        Result.success(Unit)
    }

    /**
     * Scans installed Companion IPC providers AND installed Mihon extension APKs via PackageManager,
     * synchronizes their Room states, and loads trusted verified Mihon sources into [MihonExtensionLoader].
     */
    suspend fun syncInstalledExtensions(): Int = withContext(Dispatchers.IO) {
        val appCtx = context ?: return@withContext 0
        val reposById = extensionDao.getReposOnce().associateBy { it.id }
        val existingPkgs = extensionDao.getPackagesOnce()
        val expectedFps = existingPkgs.mapNotNull { pkg ->
            val fp = pkg.repoId?.let { reposById[it]?.signingKeyFingerprint }.orEmpty()
            if (fp.isNotBlank()) pkg.pkgId to fp else null
        }.toMap()

        val discovered = ExtensionSecurityManager.discoverInstalledExtensionPackages(appCtx, expectedFps)
        val discoveredByPkg = discovered.associateBy { it.packageName }

        val blockedPkgIds = existingPkgs
            .filter { it.isInstalled && !it.isEnabled }
            .map { it.pkgId }
            .toSet()

        val loadedState = mihonLoader?.reloadInstalledExtensions(
            expectedFingerprintsByPkg = expectedFps,
            blockedPkgIds = blockedPkgIds
        )

        var updatedCount = 0
        val byPkgId = existingPkgs.associateBy { it.pkgId }

        // Handle OS-uninstalled Mihon APK packages that were previously marked installed
        existingPkgs.filter {
            it.isInstalled &&
                it.format != ExtensionFormat.KOMA_JSON_ADAPTER.id &&
                it.format != ExtensionFormat.COMPANION_IPC_EXTENSION.id &&
                it.pkgId !in discoveredByPkg
        }.forEach { removed ->
            val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                format = ExtensionFormat.fromId(removed.format),
                versionName = removed.latestVersionName.ifBlank { removed.versionName },
                isRepoTrusted = removed.repoId?.let { reposById[it]?.isTrusted } ?: true,
                signatureMatch = true,
                explicitLibVersion = removed.libVersion,
                pkgId = removed.pkgId,
                extensionName = removed.name
            )
            extensionDao.upsertPackage(
                removed.copy(
                    isInstalled = false,
                    isEnabled = false,
                    compatibilityStatus = compat.status.name,
                    compatibilityReason = compat.reason,
                    updatedAt = System.currentTimeMillis()
                )
            )
            updatedCount++
        }

        discovered.forEach { info ->
            val existing = byPkgId[info.packageName]
            val repo = existing?.repoId?.let { reposById[it] }
            val expectedFp = repo?.signingKeyFingerprint.orEmpty()
            val sigOk = expectedFp.isBlank() || info.matchesExpectedFingerprint

            if (info.ipcAuthority.isNotBlank() && info.tachiyomiExtensionClass.isBlank()) {
                // Option A Companion IPC provider
                if (existing != null) {
                    if (sigOk && repo?.isTrusted != false) {
                        val cfg = JSONObject()
                            .put("authority", info.ipcAuthority)
                            .put("packageName", info.packageName)
                            .toString()
                        val next = existing.copy(
                            format = ExtensionFormat.COMPANION_IPC_EXTENSION.id,
                            adapterJson = cfg,
                            libVersion = "koma-ipc-1",
                            installedSignatureSha256 = info.signatureSha256,
                            compatibilityStatus = ExtensionCompatibilityStatus.COMPANION_IPC_READY.name,
                            compatibilityReason = "Companion IPC detected on device ('${info.ipcAuthority}'). Tap Activate IPC to enable.",
                            installError = null,
                            updatedAt = System.currentTimeMillis()
                        )
                        if (next.compatibilityStatus != existing.compatibilityStatus || next.adapterJson != existing.adapterJson) {
                            extensionDao.upsertPackage(next)
                            updatedCount++
                        }
                    } else if (!sigOk) {
                        extensionDao.upsertPackage(
                            existing.copy(
                                isInstalled = false,
                                isEnabled = false,
                                installedSignatureSha256 = info.signatureSha256,
                                compatibilityStatus = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH.name,
                                compatibilityReason = "Cat B (Signature Mismatch): Installed app '${info.packageName}' certificate does not match repository fingerprint.",
                                installError = "Companion signing certificate mismatch.",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        updatedCount++
                    }
                }
            } else if (info.tachiyomiExtensionClass.isNotBlank()) {
                // Installed Mihon APK extension
                val loadResult = loadedState?.resultsByPkg?.get(info.packageName)
                val isBlocked = existing != null && existing.isInstalled && !existing.isEnabled
                val (nextStatus, nextReason, nextError, activeFlag, loadedSources) = when {
                    !sigOk -> Tuple5(
                        ExtensionCompatibilityStatus.SIGNATURE_MISMATCH,
                        "Cat B (Signature Mismatch): Installed APK '${info.packageName}' certificate does not match repository fingerprint.",
                        "Signing certificate mismatch.",
                        false,
                        emptyList()
                    )
                    loadResult is ExtensionLoadResult.Loaded -> Tuple5(
                        ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
                        "Installed & Active: ${loadResult.adapters.size} Mihon source(s) loaded (${info.verifiedSchemes.joinToString("+").ifBlank { "verified" }}).",
                        null,
                        true,
                        loadResult.adapters.mapNotNull { adapter ->
                            (adapter as? com.example.source.MihonSourceAdapter)?.let {
                                ExtensionSourceMeta(
                                    id = it.mihonSourceId,
                                    name = it.name,
                                    lang = it.language,
                                    baseUrl = it.baseUrl
                                )
                            }
                        }
                    )
                    loadResult is ExtensionLoadResult.Untrusted -> Tuple5(
                        ExtensionCompatibilityStatus.UNTRUSTED_SIGNER,
                        loadResult.reason,
                        null,
                        false,
                        emptyList()
                    )
                    loadResult is ExtensionLoadResult.Error -> Tuple5(
                        if (loadResult.isApiMismatch) {
                            ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE
                        } else {
                            ExtensionCompatibilityStatus.MIHON_LOAD_FAILED
                        },
                        loadResult.reason,
                        loadResult.reason,
                        false,
                        emptyList()
                    )
                    isBlocked -> Tuple5(
                        ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
                        "Installed on device (Paused by user).",
                        null,
                        true,
                        emptyList()
                    )
                    else -> Tuple5(
                        ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
                        "Installed on device (${info.verifiedSchemes.joinToString("+").ifBlank { "verified" }}).",
                        null,
                        true,
                        emptyList()
                    )
                }

                val targetEntity = existing?.copy(
                    isInstalled = true,
                    isEnabled = if (existing.isInstalled) existing.isEnabled else activeFlag,
                    installedVersionCode = info.versionCode.toInt(),
                    versionName = info.versionName.ifBlank { existing.versionName },
                    hasUpdate = existing.latestVersionCode > info.versionCode.toInt(),
                    signatureHash = info.signatureSha256.take(16),
                    installedSignatureSha256 = info.signatureSha256,
                    libVersion = info.libVersion.ifBlank { existing.libVersion },
                    sourcesJson = if (loadedSources.isNotEmpty()) {
                        jsonSerializer.encodeToString(loadedSources)
                    } else {
                        existing.sourcesJson
                    },
                    compatibilityStatus = nextStatus.name,
                    compatibilityReason = nextReason,
                    installError = nextError,
                    updatedAt = System.currentTimeMillis()
                ) ?: ExtensionPackageEntity(
                    pkgId = info.packageName,
                    repoId = null,
                    name = info.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() },
                    versionName = info.versionName.ifBlank { "1.4.0" },
                    versionCode = info.versionCode.toInt(),
                    author = "Sideloaded Mihon Extension",
                    description = "Installed Mihon extension APK (${info.packageName}).",
                    language = "all",
                    format = ExtensionFormat.MIHON_JSON_INDEX.id,
                    isInstalled = true,
                    isEnabled = activeFlag,
                    installedVersionCode = info.versionCode.toInt(),
                    latestVersionCode = info.versionCode.toInt(),
                    latestVersionName = info.versionName.ifBlank { "1.4.0" },
                    signatureHash = info.signatureSha256.take(16),
                    installedSignatureSha256 = info.signatureSha256,
                    isNsfw = info.isNsfw,
                    libVersion = info.libVersion.ifBlank { "1.4" },
                    sourcesJson = jsonSerializer.encodeToString(loadedSources),
                    compatibilityStatus = nextStatus.name,
                    compatibilityReason = nextReason,
                    installError = nextError,
                    updatedAt = System.currentTimeMillis()
                )
                extensionDao.upsertPackage(targetEntity)
                updatedCount++
            }
        }
        updatedCount
    }

    @Deprecated(
        "Use syncInstalledExtensions instead",
        replaceWith = ReplaceWith("syncInstalledExtensions()")
    )
    suspend fun syncInstalledCompanionApps(): Int = syncInstalledExtensions()

    /**
     * Explicit user approval of an extension's cryptographically verified signer certificate SHA-256.
     * Re-evaluates all downloaded/installed extensions sharing that signer cert cleanly.
     */
    suspend fun trustExtensionSigner(pkg: ExtensionPackageEntity): Result<ExtensionPackageEntity> =
        withContext(Dispatchers.IO) {
            val tm = trustManager ?: return@withContext Result.failure(
                IllegalStateException("ExtensionTrustManager is unavailable.")
            )
            val appCtx = context ?: return@withContext Result.failure(
                IllegalStateException("Android Context is unavailable.")
            )
            val sigSha = pkg.installedSignatureSha256.ifBlank { pkg.signatureHash }
            if (sigSha.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Download & verify APK first to inspect its cryptographic signer certificate.")
                )
            }
            tm.trustSigner(sigSha, "${pkg.name} (${pkg.pkgId})")
            recordHistory(pkg, "TRUST_SIGNER", "TRUSTED", "Approved signer certificate ${sigSha.take(16)}…")

            val apkFile = pkg.localApkPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            if (apkFile != null) {
                val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
                val audit = ExtensionSecurityManager.inspectApkStructural(appCtx, apkFile, pkg.pkgId)
                val installedOnDevice = ExtensionSecurityManager.discoverInstalledExtensionPackages(appCtx)
                    .any { it.packageName == pkg.pkgId }
                val compat = ExtensionCompatibilityResolver.evaluateApkArchive(
                    pkg = pkg,
                    inspection = audit,
                    isRepoTrusted = repo?.isTrusted ?: true,
                    expectedFingerprint = repo?.signingKeyFingerprint.orEmpty(),
                    isSignerTrusted = true,
                    isInstalledOnDevice = installedOnDevice
                )
                val updated = pkg.copy(
                    isInstalled = installedOnDevice,
                    isEnabled = installedOnDevice && compat.canExecuteInKoma,
                    compatibilityStatus = compat.status.name,
                    compatibilityReason = compat.reason,
                    installError = null,
                    updatedAt = System.currentTimeMillis()
                )
                extensionDao.upsertPackage(updated)
                if (installedOnDevice) {
                    syncInstalledExtensions()
                }
                Result.success(extensionDao.getPackage(pkg.pkgId) ?: updated)
            } else {
                syncInstalledExtensions()
                Result.success(extensionDao.getPackage(pkg.pkgId) ?: pkg)
            }
        }

    /**
     * Downloads a Mihon APK over HTTPS into `filesDir/extensions_cache/`, performs:
     * 1. ZIP / AndroidManifest / DEX container checks
     * 2. Official Android `apksig` v1/v2/v3/v3.1 signature verification
     * 3. Signer trust + fingerprint + API version checks
     */
    suspend fun downloadAndVerifyMihonApk(pkg: ExtensionPackageEntity): Result<ExtensionPackageEntity> =
        withContext(Dispatchers.IO) {
            val appCtx = context ?: return@withContext Result.failure(
                IllegalStateException("Application context is required for APK archive verification.")
            )
            val apkUrl = pkg.apkUrl.trim()
            if (apkUrl.isBlank() || !ExtensionSecurityManager.isSecureHttpsUrl(apkUrl)) {
                val msg = "Missing or non-HTTPS APK download URL for ${pkg.name}."
                recordHistory(pkg, "VERIFY_APK", "FAILED", msg)
                extensionDao.upsertPackage(pkg.copy(installError = msg))
                return@withContext Result.failure(SecurityException(msg))
            }

            val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
            if (repo != null && !repo.isTrusted) {
                val msg = "Repository '${repo.name}' is untrusted. Trust repository before downloading APK."
                recordHistory(pkg, "VERIFY_APK", "BLOCKED", msg)
                extensionDao.upsertPackage(
                    pkg.copy(
                        isInstalled = false,
                        isEnabled = false,
                        compatibilityStatus = ExtensionCompatibilityStatus.UNTRUSTED_REPO.name,
                        compatibilityReason = msg,
                        installError = msg
                    )
                )
                return@withContext Result.failure(SecurityException(msg))
            }

            val cacheDir = File(appCtx.filesDir, "extensions_cache").apply { mkdirs() }
            val safeFileName = pkg.apkName.ifBlank { "${pkg.pkgId}_v${pkg.versionCode}.apk" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destFile = File(cacheDir, safeFileName)
            val tempFile = File(cacheDir, "$safeFileName.tmp")

            _downloadProgress.update { it + (pkg.pkgId to 0.02f) }
            try {
                val req = Request.Builder()
                    .url(apkUrl)
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("APK download failed with HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("Empty APK response body")
                    val totalBytes = body.contentLength().coerceAtLeast(1L)
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buf = ByteArray(8192)
                            var read = input.read(buf)
                            while (read >= 0) {
                                if (read > 0) {
                                    output.write(buf, 0, read)
                                    downloaded += read
                                    if (body.contentLength() > 0L) {
                                        val progress = (downloaded.toFloat() / totalBytes).coerceIn(0.05f, 0.95f)
                                        _downloadProgress.update { it + (pkg.pkgId to progress) }
                                    }
                                }
                                read = input.read(buf)
                            }
                            output.fd.sync()
                        }
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                _downloadProgress.update { it + (pkg.pkgId to 0.98f) }

                val auditResult = verifyCachedApkFile(pkg, destFile)
                _downloadProgress.update { it - pkg.pkgId }
                auditResult
            } catch (e: Exception) {
                tempFile.delete()
                _downloadProgress.update { it - pkg.pkgId }
                val message = NetworkErrorFormatter.describe(e, apkUrl.toHttpUrlOrNull()?.host)
                extensionDao.upsertPackage(pkg.copy(installError = message, updatedAt = System.currentTimeMillis()))
                recordHistory(pkg, "VERIFY_APK", "FAILED", message)
                Result.failure(IOException(message, e))
            }
        }

    /**
     * Verifies an already-downloaded local APK file using `apksig`, signer trust, and API range checks.
     */
    suspend fun verifyCachedApkFile(
        pkg: ExtensionPackageEntity,
        apkFile: File
    ): Result<ExtensionPackageEntity> = withContext(Dispatchers.IO) {
        val appCtx = context ?: return@withContext Result.failure(
            IllegalStateException("Application context is required for APK archive verification.")
        )
        val repo = pkg.repoId?.let { extensionDao.getRepoById(it) }
        val expectedFp = repo?.signingKeyFingerprint.orEmpty()
        val apkAudit = ExtensionSecurityManager.inspectApkStructural(
            context = appCtx,
            apkFile = apkFile,
            expectedPkgId = pkg.pkgId
        )
        val installedList = ExtensionSecurityManager.discoverInstalledExtensionPackages(
            context = appCtx,
            expectedFingerprintsByPkg = if (expectedFp.isNotBlank()) mapOf(pkg.pkgId to expectedFp) else emptyMap()
        )
        val companionInfo = installedList.firstOrNull {
            it.packageName == pkg.pkgId && it.ipcAuthority.isNotBlank()
        }
        val isInstalledOnDevice = installedList.any {
            it.packageName == pkg.pkgId && it.tachiyomiExtensionClass.isNotBlank()
        }
        val isSignerTrusted = if (apkAudit.signatureSha256.isBlank()) {
            false
        } else {
            trustManager?.isSignerTrusted(apkAudit.signatureSha256) == true
        }

        val compat = ExtensionCompatibilityResolver.evaluateApkArchive(
            pkg = pkg,
            inspection = apkAudit,
            isRepoTrusted = repo?.isTrusted ?: true,
            expectedFingerprint = expectedFp,
            installedCompanion = companionInfo,
            isSignerTrusted = isSignerTrusted,
            isInstalledOnDevice = isInstalledOnDevice
        )

        val isFailureCategory = compat.category == ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE ||
            compat.category == ApkIncompatibilityCategory.API_VERSION_INCOMPATIBLE ||
            compat.category == ApkIncompatibilityCategory.DIFFERENT_ARCHITECTURE
        val isIpcReady = compat.status == ExtensionCompatibilityStatus.COMPANION_IPC_READY
        val updatedAdapterJson = if (isIpcReady && companionInfo != null) {
            JSONObject()
                .put("authority", companionInfo.ipcAuthority)
                .put("packageName", companionInfo.packageName)
                .toString()
        } else {
            pkg.adapterJson
        }

        val inspected = pkg.copy(
            format = if (isIpcReady) ExtensionFormat.COMPANION_IPC_EXTENSION.id else pkg.format,
            adapterJson = updatedAdapterJson,
            isInstalled = isInstalledOnDevice,
            isEnabled = isInstalledOnDevice && compat.canExecuteInKoma,
            installedVersionCode = if (isInstalledOnDevice) apkAudit.versionCode.toInt() else pkg.installedVersionCode,
            localApkPath = apkFile.absolutePath,
            signatureHash = if (isFailureCategory) "" else apkAudit.fileSha256.take(16),
            installedSignatureSha256 = apkAudit.signatureSha256,
            isNsfw = if (apkAudit.packageManagerParsed) apkAudit.isNsfw else pkg.isNsfw,
            hasReadme = if (apkAudit.packageManagerParsed) apkAudit.hasReadme else pkg.hasReadme,
            libVersion = compat.libVersion,
            compatibilityStatus = compat.status.name,
            compatibilityReason = buildString {
                append(compat.reason)
                if (apkAudit.signerSubjects.isNotEmpty()) {
                    append(" Signer: ${apkAudit.signerSubjects.first()}.")
                }
                if (apkAudit.fileSha256.isNotBlank()) {
                    append(" APK SHA-256: ${apkAudit.fileSha256.take(16)}…")
                }
            },
            installError = if (isFailureCategory) compat.reason else null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(inspected)

        val auditOutcome = when (compat.status) {
            ExtensionCompatibilityStatus.VERIFIED_MIHON_READY -> "VERIFIED_TRUSTED"
            ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE -> "INSTALLED_ACTIVE"
            ExtensionCompatibilityStatus.UNTRUSTED_SIGNER -> "SIGNER_APPROVAL_REQUIRED"
            ExtensionCompatibilityStatus.UNSIGNED_APK -> "CAT_B_UNSIGNED_APK"
            ExtensionCompatibilityStatus.COMPANION_IPC_READY -> "IPC_COMPANION_READY"
            ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> "CAT_A_API_MISMATCH"
            ExtensionCompatibilityStatus.MALFORMED_APK -> "CAT_B_MALFORMED_APK"
            ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> "CAT_B_SIGNATURE_MISMATCH"
            ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE -> "CAT_D_DIFFERENT_ARCH"
            else -> compat.status.name
        }
        recordHistory(inspected, action = "VERIFY_APK", outcome = auditOutcome, details = inspected.compatibilityReason)

        if (isInstalledOnDevice && isSignerTrusted && compat.canExecuteInKoma) {
            syncInstalledExtensions()
        }

        if (isFailureCategory) {
            val failureEx = if (compat.status == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH) {
                SecurityException(compat.reason)
            } else {
                IOException(compat.reason)
            }
            Result.failure(failureEx)
        } else {
            Result.success(inspected)
        }
    }

    /**
     * Initiates OS-consented Android package installation via [ExtensionInstaller]
     * for a downloaded, signature-verified, and explicitly signer-trusted Mihon extension APK.
     */
    suspend fun requestInstallMihonApk(pkg: ExtensionPackageEntity): Result<ExtensionInstallEvent> =
        withContext(Dispatchers.IO) {
            val inst = installer ?: return@withContext Result.failure(
                IllegalStateException("ExtensionInstaller is unavailable.")
            )
            val appCtx = context ?: return@withContext Result.failure(
                IllegalStateException("Application context is required.")
            )

            // Ensure APK is downloaded & verified first
            val currentPkg = if (pkg.localApkPath.isBlank() || !File(pkg.localApkPath).exists()) {
                val dlRes = downloadAndVerifyMihonApk(pkg)
                dlRes.getOrElse { return@withContext Result.failure(it) }
            } else {
                verifyCachedApkFile(pkg, File(pkg.localApkPath)).getOrElse { return@withContext Result.failure(it) }
            }

            val apkFile = File(currentPkg.localApkPath)
            val audit = ExtensionSecurityManager.inspectApkStructural(appCtx, apkFile, currentPkg.pkgId)
            val isSignerTrusted = trustManager?.isSignerTrusted(audit.signatureSha256) == true

            if (!isSignerTrusted) {
                val event = ExtensionInstallEvent.Failed(
                    currentPkg.pkgId,
                    "Signer certificate (${audit.signatureSha256.take(12)}…) is not trusted yet. Tap 'Trust Signer' to approve."
                )
                return@withContext Result.failure(SecurityException(event.reason))
            }

            val status = runCatching {
                ExtensionCompatibilityStatus.valueOf(currentPkg.compatibilityStatus)
            }.getOrDefault(ExtensionCompatibilityStatus.UNKNOWN)
            if (status != ExtensionCompatibilityStatus.VERIFIED_MIHON_READY &&
                status != ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE
            ) {
                return@withContext Result.failure(UnsupportedOperationException(currentPkg.compatibilityReason))
            }

            val event = inst.requestInstallApk(
                pkgId = currentPkg.pkgId,
                apkFile = apkFile,
                isTrusted = true,
                audit = audit
            )
            recordHistory(currentPkg, "OS_INSTALL", "REQUESTED", "Triggered Android OS installer for ${currentPkg.pkgId}")
            Result.success(event)
        }

    fun buildOsUninstallIntent(pkgId: String): Intent? = installer?.buildUninstallIntent(pkgId)

    suspend fun uninstall(pkgId: String) = withContext(Dispatchers.IO) {
        val existing = extensionDao.getPackage(pkgId)
        if (existing != null && existing.localApkPath.isNotBlank()) {
            runCatching { File(existing.localApkPath).delete() }
        }
        mihonLoader?.unloadPackage(pkgId)
        extensionDao.uninstallPackage(pkgId)
        if (existing != null) {
            if (existing.format != ExtensionFormat.KOMA_JSON_ADAPTER.id) {
                val resetFormat = if (existing.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id) {
                    ExtensionFormat.MIHON_JSON_INDEX
                } else {
                    ExtensionFormat.fromId(existing.format)
                }
                val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                    format = resetFormat,
                    versionName = existing.versionName,
                    isRepoTrusted = true,
                    signatureMatch = true,
                    explicitLibVersion = existing.libVersion.takeIf { it.isNotBlank() && it != "koma-ipc-1" },
                    pkgId = existing.pkgId,
                    extensionName = existing.name
                )
                extensionDao.upsertPackage(
                    existing.copy(
                        isInstalled = false,
                        isEnabled = false,
                        hasUpdate = false,
                        localApkPath = "",
                        installedSignatureSha256 = "",
                        installError = null,
                        compatibilityStatus = compat.status.name,
                        compatibilityReason = compat.reason
                    )
                )
            }
            recordHistory(existing, "UNINSTALL", "SUCCESS", "Removed ${existing.name}")
        }
    }

    private suspend fun recordHistory(
        pkg: ExtensionPackageEntity,
        action: String,
        outcome: String,
        details: String
    ) {
        extensionDao.insertInstallHistory(
            ExtensionInstallHistoryEntity(
                pkgId = pkg.pkgId,
                extensionName = pkg.name,
                versionName = pkg.latestVersionName.ifBlank { pkg.versionName },
                action = action,
                status = outcome,
                detail = details
            )
        )
    }

    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
