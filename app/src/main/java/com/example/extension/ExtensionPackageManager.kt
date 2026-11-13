package com.example.extension

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.network.NetworkErrorFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages extension installation, APK verification downloads, updates,
 * cached APK storage, and audit history.
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
            val msg = "Mihon/Tachiyomi APK plugins cannot execute DEX code inside Koma's sandboxed runtime. Inspect or verify the APK instead."
            recordHistory(pkg, "INSTALL", "REJECTED", msg)
            return Result.failure(UnsupportedOperationException(msg))
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
            compatibilityReason = "Installed and active inside Koma's declarative JSON sandbox.",
            installError = null,
            updatedAt = System.currentTimeMillis()
        )
        extensionDao.upsertPackage(installed)
        recordHistory(installed, "INSTALL", "SUCCESS", "Activated Koma declarative source v${installed.versionName}")
        return Result.success(Unit)
    }

    /**
     * Downloads a Mihon APK over HTTPS into `filesDir/extensions_cache/`, computes SHA-256,
     * inspects manifest + signing certificate against repo fingerprint, and persists verified audit metadata.
     * Does NOT execute downloaded DEX/APK bytecode.
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

                val fileSha = ExtensionSecurityManager.computeFileSha256(destFile)
                val apkAudit = ExtensionSecurityManager.inspectApkArchive(appCtx, destFile)
                val expectedFp = repo?.signingKeyFingerprint.orEmpty()
                val actualSig = apkAudit?.signatureSha256.orEmpty()
                val sigMatches = if (actualSig.isBlank()) {
                    true
                } else {
                    ExtensionSecurityManager.fingerprintsMatch(expectedFp, actualSig)
                }

                val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                    format = ExtensionFormat.fromId(pkg.format),
                    versionName = apkAudit?.versionName?.ifBlank { pkg.versionName } ?: pkg.versionName,
                    isRepoTrusted = repo?.isTrusted ?: true,
                    signatureMatch = sigMatches
                )

                val verified = pkg.copy(
                    localApkPath = destFile.absolutePath,
                    signatureHash = fileSha.take(16),
                    installedSignatureSha256 = actualSig,
                    isNsfw = apkAudit?.isNsfw ?: pkg.isNsfw,
                    hasReadme = apkAudit?.hasReadme ?: pkg.hasReadme,
                    libVersion = compat.libVersion,
                    compatibilityStatus = compat.status.name,
                    compatibilityReason = buildString {
                        append(compat.reason)
                        if (apkAudit?.extensionClass?.isNotBlank() == true) {
                            append(" Entry class: ${apkAudit.extensionClass}.")
                        }
                        append(" File SHA-256: ${fileSha.take(16)}…")
                    },
                    installError = if (compat.status == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH) {
                        compat.reason
                    } else null,
                    updatedAt = System.currentTimeMillis()
                )
                extensionDao.upsertPackage(verified)
                recordHistory(
                    verified,
                    action = "VERIFY_APK",
                    outcome = if (sigMatches) "VERIFIED" else "SIGNATURE_MISMATCH",
                    details = verified.compatibilityReason
                )
                _downloadProgress.update { it - pkg.pkgId }
                if (!sigMatches) {
                    Result.failure(SecurityException(compat.reason))
                } else {
                    Result.success(verified)
                }
            } catch (e: Exception) {
                tempFile.delete()
                _downloadProgress.update { it - pkg.pkgId }
                val message = NetworkErrorFormatter.describe(e)
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
