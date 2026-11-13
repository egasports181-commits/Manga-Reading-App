package com.example.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkInspectionResult(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val extensionClass: String,
    val isNsfw: Boolean,
    val hasReadme: Boolean,
    val signatureSha256: String,
    val fileSha256: String
)

/**
 * Validates HTTPS repository origins, computes APK SHA-256 digests,
 * extracts signing certificates, and matches against repo.json fingerprints.
 */
object ExtensionSecurityManager {

    fun isSecureHttpsUrl(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun normalizeFingerprint(fp: String): String =
        fp.replace(":", "").replace(" ", "").lowercase().trim()

    fun fingerprintsMatch(expected: String, actual: String): Boolean {
        if (expected.isBlank()) return true
        val normExpected = normalizeFingerprint(expected)
        val normActual = normalizeFingerprint(actual)
        return normExpected == normActual || normActual.startsWith(normExpected) || normExpected.startsWith(normActual)
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read = input.read(buf)
            while (read >= 0) {
                if (read > 0) digest.update(buf, 0, read)
                read = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun inspectApkArchive(context: Context, apkFile: File): ApkInspectionResult? {
        if (!apkFile.exists() || apkFile.length() == 0L) return null
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        val meta = info.applicationInfo?.metaData
        val extClass = meta?.getString("tachiyomi.extension.class").orEmpty()
        val isNsfw = (meta?.getInt("tachiyomi.extension.nsfw", 0) ?: 0) == 1
        val hasReadme = (meta?.getInt("tachiyomi.extension.hasReadme", 0) ?: 0) == 1

        val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        }
        val sigSha256 = if (sigBytes != null) {
            MessageDigest.getInstance("SHA-256").digest(sigBytes).joinToString("") { "%02x".format(it) }
        } else ""

        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

        return ApkInspectionResult(
            packageName = info.packageName.orEmpty(),
            versionCode = vCode,
            versionName = info.versionName.orEmpty(),
            extensionClass = extClass,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            signatureSha256 = sigSha256,
            fileSha256 = computeFileSha256(apkFile)
        )
    }
}
