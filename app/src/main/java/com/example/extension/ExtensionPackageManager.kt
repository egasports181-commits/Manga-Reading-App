package com.example.extension

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ApkIncompatibilityCategory
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages extension installation, APK structural/certificate inspection, Option A Companion IPC
 * activation/discovery, cached APK storage, and audit history.
 *
 * Strictly forbids in-process DEX execution (`DexClassLoader`) and silent APK installation.
 */
class ExtensionPackageManager(
    private val context: Context?,
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient
) {
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    suspend fun installKomaAdapter(pkg: ExtensionPackageEntity): Result<Unit> {
        if (pkg.format != ExtensionFormat.KOMA_JSON_ADAPTER.id) {
            val msg = "Native Mihon/Tachiyomi APKs cannot execute DEX code inside Koma's sandboxed runtime. Inspect the APK, use Companion IPC (Option A), or add a koma_json_v1 adapter."
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
            compatibilityReason = "Compatible, installed, and active inside Koma's declarative JSON sandbox (koma_json_v1, API v1).",
            installError = null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(installed)
        recordHistory(installed, "INSTALL", "ACTIVE", "Activated Koma declarative source v${installed.versionName}")
        return Result.success(Unit)
    }

    /**
     * Option A: Activates a verified out-of-process Companion IPC extension (`koma_ipc_v1`).
     * Communicates solely via Android ContentResolver IPC boundaries; zero DEX loading.
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
            val discovered = ExtensionSecurityManager.discoverInstalledCompanionPackages(
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
            compatibilityReason = "Active Option A Companion IPC ('$cleanAuthority' via '$targetPackageName'). Zero in-process DEX.",
            installError = null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(activated)
        recordHistory(activated, "ACTIVATE_IPC", "ACTIVE", activated.compatibilityReason)
        Result.success(Unit)
    }

    /**
     * Scans explicitly declared `<queries>` IPC/extension packages installed cleanly by the user.
     * Verified Option A companions become IPC-ready; standard Mihon APKs remain restricted.
     */
    suspend fun syncInstalledCompanionApps(): Int = withContext(Dispatchers.IO) {
        val appCtx = context ?: return@withContext 0
        val reposById = extensionDao.getReposOnce().associateBy { it.id }
        val existingPkgs = extensionDao.getPackagesOnce()
        val expectedFps = existingPkgs.mapNotNull { pkg ->
            val fp = pkg.repoId?.let { reposById[it]?.signingKeyFingerprint }.orEmpty()
            if (fp.isNotBlank()) pkg.pkgId to fp else null
        }.toMap()

        val discovered = ExtensionSecurityManager.discoverInstalledCompanionPackages(appCtx, expectedFps)
        if (discovered.isEmpty()) return@withContext 0

        var updatedCount = 0
        val byPkgId = existingPkgs.associateBy { it.pkgId }
        discovered.forEach { info ->
            val pkg = byPkgId[info.packageName] ?: return@forEach
            val repo = pkg.repoId?.let { reposById[it] }
            val expectedFp = repo?.signingKeyFingerprint.orEmpty()
            val sigOk = expectedFp.isBlank() || info.matchesExpectedFingerprint

            if (info.ipcAuthority.isNotBlank() && sigOk && (repo?.isTrusted != false)) {
                val cfg = JSONObject()
                    .put("authority", info.ipcAuthority)
                    .put("packageName", info.packageName)
                    .toString()
                val next = pkg.copy(
                    format = ExtensionFormat.COMPANION_IPC_EXTENSION.id,
                    adapterJson = cfg,
                    libVersion = "koma-ipc-1",
                    installedSignatureSha256 = info.signatureSha256,
                    compatibilityStatus = ExtensionCompatibilityStatus.COMPANION_IPC_READY.name,
                    compatibilityReason = "Option A Companion IPC detected on device ('${info.ipcAuthority}'). Tap Activate IPC to enable source.",
                    installError = null,
                    updatedAt = System.currentTimeMillis()
                )
                if (next.compatibilityStatus != pkg.compatibilityStatus || next.adapterJson != pkg.adapterJson) {
                    extensionDao.upsertPackage(next)
                    updatedCount++
                }
            } else if (!sigOk) {
                extensionDao.upsertPackage(
                    pkg.copy(
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
        updatedCount
    }

    /**
     * Downloads a Mihon APK over HTTPS into `filesDir/extensions_cache/`, performs Layer 1 ZIP +
     * Layer 2 PackageManager structural inspection, checks signing certificate against repository
     * fingerprint, and classifies into Category A / B / C / D (plus Option A / Option C availability).
     * NEVER sets `isInstalled = true` for native DEX APKs and NEVER executes foreign DEX bytecode.
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

                val expectedFp = repo?.signingKeyFingerprint.orEmpty()
                val apkAudit = ExtensionSecurityManager.inspectApkStructural(
                    context = appCtx,
                    apkFile = destFile,
                    expectedPkgId = pkg.pkgId
                )
                val companionInfo = ExtensionSecurityManager.discoverInstalledCompanionPackages(
                    context = appCtx,
                    expectedFingerprintsByPkg = if (expectedFp.isNotBlank()) mapOf(pkg.pkgId to expectedFp) else emptyMap()
                ).firstOrNull {
                    it.packageName == pkg.pkgId ||
                        (apkAudit.ipcAuthority.isNotBlank() && it.ipcAuthority == apkAudit.ipcAuthority)
                }

                val compat = ExtensionCompatibilityResolver.evaluateApkArchive(
                    pkg = pkg,
                    inspection = apkAudit,
                    isRepoTrusted = repo?.isTrusted ?: true,
                    expectedFingerprint = expectedFp,
                    installedCompanion = companionInfo
                )

                val isFailureCategory = compat.category == ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE
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
                    isInstalled = false,
                    isEnabled = false,
                    localApkPath = destFile.absolutePath,
                    signatureHash = if (isFailureCategory) "" else apkAudit.fileSha256.take(16),
                    installedSignatureSha256 = apkAudit.signatureSha256,
                    isNsfw = if (apkAudit.packageManagerParsed) apkAudit.isNsfw else pkg.isNsfw,
                    hasReadme = if (apkAudit.packageManagerParsed) apkAudit.hasReadme else pkg.hasReadme,
                    libVersion = compat.libVersion,
                    compatibilityStatus = compat.status.name,
                    compatibilityReason = buildString {
                        append(compat.reason)
                        if (apkAudit.fileSha256.isNotBlank()) {
                            append(" SHA-256: ${apkAudit.fileSha256.take(16)}…")
                        }
                        if (apkAudit.minSdkVersion > 0) {
                            append(" (minSdk=${apkAudit.minSdkVersion}, targetSdk=${apkAudit.targetSdkVersion})")
                        }
                    },
                    installError = if (isFailureCategory) compat.reason else null,
                    updatedAt = System.currentTimeMillis()
                )
                extensionDao.upsertPackage(inspected)

                val auditOutcome = when (compat.status) {
                    ExtensionCompatibilityStatus.COMPANION_IPC_READY -> "IPC_COMPANION_READY"
                    ExtensionCompatibilityStatus.NATIVE_EQUIVALENT_AVAILABLE -> "VERIFIED_NATIVE_ALT_AVAILABLE"
                    ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED -> "VERIFIED_MIHON_DEX_RESTRICTED"
                    ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> "CAT_A_API_MISMATCH"
                    ExtensionCompatibilityStatus.MALFORMED_APK -> "CAT_B_MALFORMED_APK"
                    ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> "CAT_B_SIGNATURE_MISMATCH"
                    ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE -> "CAT_D_DIFFERENT_ARCH"
                    else -> compat.status.name
                }
                recordHistory(inspected, action = "VERIFY_APK", outcome = auditOutcome, details = inspected.compatibilityReason)
                _downloadProgress.update { it - pkg.pkgId }

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
            } catch (e: Exception) {
                tempFile.delete()
                _downloadProgress.update { it - pkg.pkgId }
                val message = NetworkErrorFormatter.describe(e, apkUrl.toHttpUrlOrNull()?.host)
                extensionDao.upsertPackage(pkg.copy(installError = message, updatedAt = System.currentTimeMillis()))
                recordHistory(pkg, "VERIFY_APK", "FAILED", message)
                Result.failure(IOException(message, e))
            }
        }

    suspend fun uninstall(pkgId: String) = withContext(Dispatchers.IO) {
        val existing = extensionDao.getPackage(pkgId)
        if (existing != null && existing.localApkPath.isNotBlank()) {
            runCatching { File(existing.localApkPath).delete() }
        }
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
}
