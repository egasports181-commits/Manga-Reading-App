package com.example.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkInspectionResult(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val extensionClass: String,
    val isNsfw: Boolean,
    val hasReadme: Boolean,
    val signatureSha256: String,
    val fileSha256: String,
    val isValidZipArchive: Boolean = true,
    val hasAndroidManifestEntry: Boolean = false,
    val hasDexBytecode: Boolean = false,
    val hasSigningBlockOrCert: Boolean = false,
    val packageManagerParsed: Boolean = false,
    val minSdkVersion: Int = 0,
    val targetSdkVersion: Int = 0,
    val hasTachiyomiFeature: Boolean = false,
    val ipcAuthority: String = "",
    val requestedPermissions: List<String> = emptyList(),
    val structuralIssues: List<String> = emptyList()
)

data class InstalledExtensionAppInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signatureSha256: String,
    val tachiyomiExtensionClass: String = "",
    val ipcAuthority: String = "",
    val matchesExpectedFingerprint: Boolean = false
)

/**
 * Validates HTTPS repository origins, verifies APK ZIP/AXML/DEX structure,
 * extracts signing certificates, matches against `repo.json` fingerprints,
 * and safely discovers explicitly queried Option A companion packages.
 */
object ExtensionSecurityManager {

    const val ACTION_KOMA_IPC_PROVIDER = "com.koma.extension.IPC_PROVIDER"
    const val ACTION_TACHIYOMI_EXTENSION = "tachiyomi.extension"

    fun isSecureHttpsUrl(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    fun normalizeFingerprint(fp: String): String =
        fp.replace(":", "").replace(" ", "").lowercase().trim()

    fun fingerprintsMatch(expected: String, actual: String): Boolean {
        if (expected.isBlank()) return true
        if (actual.isBlank()) return false
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

    fun inspectApkArchive(context: Context, apkFile: File): ApkInspectionResult? {
        if (!apkFile.exists() || apkFile.length() == 0L) return null
        val detailed = inspectApkStructural(context, apkFile, expectedPkgId = null)
        return if (!detailed.isValidZipArchive && !detailed.packageManagerParsed) null else detailed
    }

    @Suppress("DEPRECATION")
    fun inspectApkStructural(
        context: Context,
        apkFile: File,
        expectedPkgId: String? = null
    ): ApkInspectionResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return ApkInspectionResult(
                packageName = "",
                versionCode = 0L,
                versionName = "",
                extensionClass = "",
                isNsfw = false,
                hasReadme = false,
                signatureSha256 = "",
                fileSha256 = "",
                isValidZipArchive = false,
                structuralIssues = listOf("APK file is missing or 0 bytes.")
            )
        }

        val fileSha = computeFileSha256(apkFile)
        val issues = mutableListOf<String>()

        // Layer 1: Verify ZIP container, AndroidManifest.xml, classes.dex, and JAR/V1/V2 signature presence
        var validZip = false
        var hasManifestEntry = false
        var hasDex = false
        var hasCertEntry = false

        try {
            ZipFile(apkFile).use { zip ->
                validZip = true
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    when {
                        name == "AndroidManifest.xml" -> hasManifestEntry = true
                        name.startsWith("classes") && name.endsWith(".dex") -> hasDex = true
                        name.startsWith("META-INF/") && (
                            name.endsWith(".RSA", true) ||
                                name.endsWith(".DSA", true) ||
                                name.endsWith(".EC", true)
                            ) -> hasCertEntry = true
                    }
                }
            }
        } catch (e: Exception) {
            issues.add("Corrupt or non-ZIP APK container (${e.message ?: "invalid archive"}). Download may be truncated or HTML error page.")
        }

        if (validZip && !hasManifestEntry) {
            issues.add("Missing AndroidManifest.xml inside APK archive.")
        }

        // Layer 2: PackageManager manifest + metadata + signing certificate extraction
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNING_CERTIFICATES or
                PackageManager.GET_CONFIGURATIONS or
                PackageManager.GET_PERMISSIONS
        } else {
            PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNATURES or
                PackageManager.GET_CONFIGURATIONS or
                PackageManager.GET_PERMISSIONS
        }
        val info: PackageInfo? = runCatching { pm.getPackageArchiveInfo(apkFile.absolutePath, flags) }.getOrNull()

        if (info == null) {
            if (validZip) {
                issues.add("Android PackageManager could not parse AndroidManifest.xml (malformed AXML or incomplete APK build).")
            }
            return ApkInspectionResult(
                packageName = "",
                versionCode = 0L,
                versionName = "",
                extensionClass = "",
                isNsfw = false,
                hasReadme = false,
                signatureSha256 = "",
                fileSha256 = fileSha,
                isValidZipArchive = validZip,
                hasAndroidManifestEntry = hasManifestEntry,
                hasDexBytecode = hasDex,
                hasSigningBlockOrCert = hasCertEntry,
                packageManagerParsed = false,
                structuralIssues = issues
            )
        }

        val pkgName = info.packageName.orEmpty()
        if (!expectedPkgId.isNullOrBlank() && pkgName.isNotBlank() && pkgName != expectedPkgId) {
            issues.add("APK package '$pkgName' does not match expected repository package '$expectedPkgId'.")
        }

        val appInfo = info.applicationInfo
        val meta = appInfo?.metaData
        val extClass = (meta?.getString("tachiyomi.extension.class")
            ?: meta?.getString("tachiyomi.extension.factory"))
            .orEmpty()
        val isNsfw = (meta?.getInt("tachiyomi.extension.nsfw", 0) ?: 0) == 1
        val hasReadme = (meta?.getInt("tachiyomi.extension.hasReadme", 0) ?: 0) == 1
        val ipcAuthority = meta?.getString("koma.extension.ipc.authority").orEmpty().trim()
        val hasFeature = info.reqFeatures?.any { it.name == ACTION_TACHIYOMI_EXTENSION } == true

        val sigBytes: ByteArray? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
        } else {
            null
        } ?: info.signatures?.firstOrNull())?.toByteArray()
        val sigSha256 = if (sigBytes != null) {
            MessageDigest.getInstance("SHA-256").digest(sigBytes).joinToString("") { "%02x".format(it) }
        } else {
            ""
        }

        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appInfo?.minSdkVersion ?: 0
        } else {
            0
        }
        val targetSdk = appInfo?.targetSdkVersion ?: 0

        if (sigSha256.isBlank() && !hasCertEntry) {
            issues.add("APK contains no readable signing certificate (unsigned APK).")
        }

        return ApkInspectionResult(
            packageName = pkgName,
            versionCode = vCode,
            versionName = info.versionName.orEmpty(),
            extensionClass = extClass,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            signatureSha256 = sigSha256,
            fileSha256 = fileSha,
            isValidZipArchive = validZip,
            hasAndroidManifestEntry = hasManifestEntry,
            hasDexBytecode = hasDex,
            hasSigningBlockOrCert = sigSha256.isNotBlank() || hasCertEntry,
            packageManagerParsed = true,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            hasTachiyomiFeature = hasFeature,
            ipcAuthority = ipcAuthority,
            requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
            structuralIssues = issues
        )
    }

    /**
     * Option A: Queries ONLY explicitly declared `<queries>` extension/IPC intents (never `QUERY_ALL_PACKAGES`),
     * verifying installed package identity and SHA-256 signing certificate.
     */
    @Suppress("DEPRECATION")
    fun discoverInstalledCompanionPackages(
        context: Context,
        expectedFingerprintsByPkg: Map<String, String> = emptyMap()
    ): List<InstalledExtensionAppInfo> {
        val pm = context.packageManager
        val discoveredPkgNames = linkedMapOf<String, String>() // pkg -> ipcAuthority hint

        // 1) Koma documented IPC ContentProvider companions
        val ipcIntent = Intent(ACTION_KOMA_IPC_PROVIDER)
        val ipcProviders = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentContentProviders(ipcIntent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                pm.queryIntentContentProviders(ipcIntent, PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())
        ipcProviders.forEach { ri ->
            val pInfo = ri.providerInfo ?: return@forEach
            val authority = pInfo.metaData?.getString("koma.extension.ipc.authority")
                ?.takeIf { it.isNotBlank() }
                ?: pInfo.authority.orEmpty()
            discoveredPkgNames[pInfo.packageName] = authority
        }
        val ipcActivities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(ipcIntent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                pm.queryIntentActivities(ipcIntent, PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())
        ipcActivities.forEach { ri ->
            val pInfo = ri.providerInfo
            val aInfo = ri.activityInfo
            val pkg = pInfo?.packageName ?: aInfo?.packageName ?: return@forEach
            val authority = pInfo?.metaData?.getString("koma.extension.ipc.authority")
                ?.takeIf { it.isNotBlank() }
                ?: pInfo?.authority
                ?: aInfo?.metaData?.getString("koma.extension.ipc.authority")
                ?: ""
            discoveredPkgNames.putIfAbsent(pkg, authority)
        }

        // 2) Independently installed tachiyomi.extension APKs
        val extIntent = Intent(ACTION_TACHIYOMI_EXTENSION)
        val activities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(extIntent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                pm.queryIntentActivities(extIntent, PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())
        activities.forEach { ri ->
            val aInfo = ri.activityInfo ?: return@forEach
            discoveredPkgNames.putIfAbsent(aInfo.packageName, "")
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PROVIDERS
        } else {
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or PackageManager.GET_PROVIDERS
        }

        return discoveredPkgNames.entries.mapNotNull { (pkgName, authorityHint) ->
            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    pm.getPackageInfo(pkgName, flags)
                }
            }.getOrNull() ?: return@mapNotNull null

            val meta = pkgInfo.applicationInfo?.metaData
            val extClass = meta?.getString("tachiyomi.extension.class").orEmpty()
            val authority = authorityHint.ifBlank {
                meta?.getString("koma.extension.ipc.authority").orEmpty()
            }.ifBlank {
                pkgInfo.providers?.firstOrNull()?.let { prov ->
                    prov.metaData?.getString("koma.extension.ipc.authority")
                        ?.takeIf { it.isNotBlank() }
                        ?: prov.authority.orEmpty()
                }.orEmpty()
            }
            val sigBytes: ByteArray? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                    ?: pkgInfo.signingInfo?.signingCertificateHistory?.firstOrNull()
            } else {
                null
            } ?: pkgInfo.signatures?.firstOrNull())?.toByteArray()
            val sigSha = if (sigBytes != null) {
                MessageDigest.getInstance("SHA-256").digest(sigBytes).joinToString("") { "%02x".format(it) }
            } else ""

            val expectedFp = expectedFingerprintsByPkg[pkgName].orEmpty()
            val fpMatch = sigSha.isNotBlank() && fingerprintsMatch(expectedFp, sigSha)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()

            InstalledExtensionAppInfo(
                packageName = pkgName,
                versionCode = vCode,
                versionName = pkgInfo.versionName.orEmpty(),
                signatureSha256 = sigSha,
                tachiyomiExtensionClass = extClass,
                ipcAuthority = authority,
                matchesExpectedFingerprint = expectedFp.isNotBlank() && fpMatch
            )
        }
    }
}
