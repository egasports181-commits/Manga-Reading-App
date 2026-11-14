package com.example.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
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
    val isSignatureCryptographicallyVerified: Boolean = false,
    val isUnsignedApk: Boolean = false,
    val isTamperedApk: Boolean = false,
    val verifiedSchemes: List<String> = emptyList(),
    val signerSubjects: List<String> = emptyList(),
    val signatureVerificationDetail: String = "",
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
    val sourceApkPath: String = "",
    val signatureSha256: String,
    val signerSubject: String = "",
    val verifiedSchemes: List<String> = emptyList(),
    val isSignatureVerified: Boolean = false,
    val tachiyomiExtensionClass: String = "",
    val isNsfw: Boolean = false,
    val ipcAuthority: String = "",
    val libVersion: String = "",
    val hasTachiyomiFeature: Boolean = false,
    val matchesExpectedFingerprint: Boolean = false
)

/**
 * Validates HTTPS origins, cryptographically verifies APK v1/v2/v3/v3.1/v4 signatures
 * using Google's `apksig` [ApkVerifier], parses Android manifest & Mihon metadata, and
 * discovers installed Mihon / Kiyomi IPC companion extensions via `<queries>`.
 */
object ExtensionSecurityManager {

    const val ACTION_KIYOMI_IPC_PROVIDER = "com.kiyomi.extension.IPC_PROVIDER"
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

        // Layer 1: Verify ZIP container, AndroidManifest.xml, classes*.dex, and v1 JAR entries
        var validZip = false
        var hasManifestEntry = false
        var hasDex = false
        var hasV1CertEntry = false

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
                            ) -> hasV1CertEntry = true
                    }
                }
            }
        } catch (e: Exception) {
            issues.add("Corrupt or non-ZIP APK container (${e.message ?: "invalid archive"}). Download may be truncated or an HTML error page.")
        }

        if (validZip && !hasManifestEntry) {
            issues.add("Missing AndroidManifest.xml inside APK archive.")
        }

        // Layer 2: Official Android `apksig` cryptographic signature verification (v1 / v2 / v3 / v3.1 / optional v4)
        var sigVerified = false
        var isUnsigned = false
        var isTampered = false
        val verifiedSchemes = mutableListOf<String>()
        val signerSubjects = mutableListOf<String>()
        var apksigCertSha256 = ""
        var sigDetail = ""

        if (validZip && hasManifestEntry) {
            val v4SigFile = File(apkFile.parentFile, "${apkFile.name}.idsig").takeIf { it.exists() }
            runCatching {
                val builder = ApkVerifier.Builder(apkFile)
                    .setMinCheckedPlatformVersion(24)
                if (v4SigFile != null) {
                    builder.setV4SignatureFile(v4SigFile)
                }
                val result = builder.build().verify()
                if (result.isVerifiedUsingV1Scheme) verifiedSchemes.add("v1")
                if (result.isVerifiedUsingV2Scheme) verifiedSchemes.add("v2")
                if (result.isVerifiedUsingV3Scheme) verifiedSchemes.add("v3")
                if (result.isVerifiedUsingV31Scheme) verifiedSchemes.add("v3.1")
                if (result.isVerifiedUsingV4Scheme) verifiedSchemes.add("v4")

                if (result.isVerified && result.signerCertificates.isNotEmpty()) {
                    sigVerified = true
                    val firstCert = result.signerCertificates.first()
                    apksigCertSha256 = sha256Hex(firstCert.encoded)
                    result.signerCertificates.forEach { cert ->
                        signerSubjects.add(cert.subjectX500Principal?.name ?: "Unknown Subject")
                    }
                    sigDetail = "Verified (${verifiedSchemes.joinToString("+").ifEmpty { "signed" }})"
                } else {
                    val errorStrings = collectApksigErrors(result)
                    val joined = errorStrings.joinToString("; ")
                    val noSigPattern = joined.contains("NO_SIGNATURES", ignoreCase = true) ||
                        joined.contains("JAR_SIG_NO_SIGNATURES", ignoreCase = true) ||
                        joined.contains("NO_SIG_FOR_TARGET", ignoreCase = true) ||
                        (!hasV1CertEntry && !hasApkSigningBlockMagic(apkFile))
                    if (noSigPattern) {
                        isUnsigned = true
                        sigDetail = "APK contains no v1/v2/v3 signing certificate (unsigned APK)."
                        issues.add(sigDetail)
                    } else {
                        isTampered = true
                        sigDetail = "APK signature verification failed (tampered or corrupt signature): ${joined.ifBlank { "unknown apksig error" }}"
                        issues.add(sigDetail)
                    }
                }
            }.onFailure { err ->
                isTampered = true
                sigDetail = "APK signature verification threw error: ${err.message ?: err.javaClass.simpleName}"
                issues.add(sigDetail)
            }
        }

        // Layer 3: PackageManager manifest + metadata parsing
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
                signatureSha256 = apksigCertSha256,
                fileSha256 = fileSha,
                isValidZipArchive = validZip,
                hasAndroidManifestEntry = hasManifestEntry,
                hasDexBytecode = hasDex,
                hasSigningBlockOrCert = sigVerified || hasV1CertEntry,
                isSignatureCryptographicallyVerified = sigVerified,
                isUnsignedApk = isUnsigned,
                isTamperedApk = isTampered,
                verifiedSchemes = verifiedSchemes,
                signerSubjects = signerSubjects,
                signatureVerificationDetail = sigDetail,
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
            .trim()
        val isNsfw = (meta?.getInt("tachiyomi.extension.nsfw", 0) ?: 0) == 1
        val hasReadme = (meta?.getInt("tachiyomi.extension.hasReadme", 0) ?: 0) == 1
        val ipcAuthority = (meta?.getString("kiyomi.extension.ipc.authority")
            ?: meta?.getString("koma.extension.ipc.authority"))
            .orEmpty()
            .trim()
        val hasFeature = info.reqFeatures?.any { it.name == ACTION_TACHIYOMI_EXTENSION } == true

        val pmSigBytes: ByteArray? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
        } else {
            null
        } ?: info.signatures?.firstOrNull())?.toByteArray()

        val finalSigSha256 = apksigCertSha256.ifBlank {
            if (pmSigBytes != null) sha256Hex(pmSigBytes) else ""
        }
        if (signerSubjects.isEmpty() && pmSigBytes != null) {
            extractX509Subject(pmSigBytes)?.let { signerSubjects.add(it) }
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

        if (!sigVerified && !isTampered && finalSigSha256.isBlank() && !hasV1CertEntry) {
            isUnsigned = true
            val msg = "APK contains no readable signing certificate (unsigned APK)."
            if (msg !in issues && sigDetail.isBlank()) issues.add(msg)
        }

        return ApkInspectionResult(
            packageName = pkgName,
            versionCode = vCode,
            versionName = info.versionName.orEmpty(),
            extensionClass = extClass,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            signatureSha256 = finalSigSha256,
            fileSha256 = fileSha,
            isValidZipArchive = validZip,
            hasAndroidManifestEntry = hasManifestEntry,
            hasDexBytecode = hasDex,
            hasSigningBlockOrCert = sigVerified || finalSigSha256.isNotBlank() || hasV1CertEntry,
            isSignatureCryptographicallyVerified = sigVerified,
            isUnsignedApk = isUnsigned,
            isTamperedApk = isTampered,
            verifiedSchemes = verifiedSchemes,
            signerSubjects = signerSubjects,
            signatureVerificationDetail = sigDetail,
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
     * Queries explicitly declared `<queries>` Mihon/Tachiyomi extension APKs and Kiyomi/Koma IPC
     * companions installed on the device, verifying APK signature via `apksig` and `PackageManager`.
     */
    @Suppress("DEPRECATION")
    fun discoverInstalledExtensionPackages(
        context: Context,
        expectedFingerprintsByPkg: Map<String, String> = emptyMap()
    ): List<InstalledExtensionAppInfo> {
        val pm = context.packageManager
        val discoveredPkgNames = linkedMapOf<String, String>() // pkg -> ipcAuthority hint

        // 1) Kiyomi & legacy Koma IPC ContentProvider companions
        listOf(ACTION_KIYOMI_IPC_PROVIDER, ACTION_KOMA_IPC_PROVIDER).forEach { action ->
            val ipcIntent = Intent(action)
            val ipcProviders = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentContentProviders(ipcIntent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                } else {
                    pm.queryIntentContentProviders(ipcIntent, PackageManager.GET_META_DATA)
                }
            }.getOrDefault(emptyList())
            ipcProviders.forEach { ri ->
                val pInfo = ri.providerInfo ?: return@forEach
                val authority = (pInfo.metaData?.getString("kiyomi.extension.ipc.authority")
                    ?: pInfo.metaData?.getString("koma.extension.ipc.authority"))
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
                val authority = (pInfo?.metaData?.getString("kiyomi.extension.ipc.authority")
                    ?: pInfo?.metaData?.getString("koma.extension.ipc.authority"))
                    ?.takeIf { it.isNotBlank() }
                    ?: pInfo?.authority
                    ?: aInfo?.metaData?.getString("kiyomi.extension.ipc.authority")
                    ?: aInfo?.metaData?.getString("koma.extension.ipc.authority")
                    ?: ""
                discoveredPkgNames.putIfAbsent(pkg, authority)
            }
        }

        // 2) Independently installed Mihon / Tachiyomi extension packages (activity, service, or app feature)
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

        // 3) Check explicitly installed packages declared via tachiyomi.extension feature in PackageManager
        val installedPkgs = runCatching {
            val baseFlags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(baseFlags.toLong()))
            } else {
                pm.getInstalledPackages(baseFlags)
            }
        }.getOrDefault(emptyList())
        installedPkgs.forEach { pi ->
            val meta = pi.applicationInfo?.metaData
            val hasMihonMeta = meta?.containsKey("tachiyomi.extension.class") == true ||
                meta?.containsKey("tachiyomi.extension.factory") == true ||
                pi.reqFeatures?.any { it.name == ACTION_TACHIYOMI_EXTENSION } == true ||
                meta?.containsKey("kiyomi.extension.ipc.authority") == true ||
                meta?.containsKey("koma.extension.ipc.authority") == true
            if (hasMihonMeta) {
                discoveredPkgNames.putIfAbsent(pi.packageName, "")
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNING_CERTIFICATES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_CONFIGURATIONS
        } else {
            PackageManager.GET_META_DATA or
                PackageManager.GET_SIGNATURES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_CONFIGURATIONS
        }

        return discoveredPkgNames.entries.mapNotNull { (pkgName, authorityHint) ->
            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    pm.getPackageInfo(pkgName, flags)
                }
            }.getOrNull() ?: return@mapNotNull null

            val appInfo = pkgInfo.applicationInfo
            val meta = appInfo?.metaData
            val extClass = (meta?.getString("tachiyomi.extension.class")
                ?: meta?.getString("tachiyomi.extension.factory"))
                .orEmpty()
                .trim()
            val isNsfw = (meta?.getInt("tachiyomi.extension.nsfw", 0) ?: 0) == 1
            val authority = authorityHint.ifBlank {
                meta?.getString("kiyomi.extension.ipc.authority")
                    ?: meta?.getString("koma.extension.ipc.authority")
                    ?: ""
            }.ifBlank {
                pkgInfo.providers?.firstOrNull()?.let { prov ->
                    (prov.metaData?.getString("kiyomi.extension.ipc.authority")
                        ?: prov.metaData?.getString("koma.extension.ipc.authority"))
                        ?.takeIf { it.isNotBlank() }
                        ?: prov.authority.orEmpty()
                }.orEmpty()
            }
            val sourceApkPath = appInfo?.sourceDir.orEmpty()
            val apkFile = sourceApkPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            val apksigAudit = apkFile?.let { inspectApkStructural(context, it, pkgName) }

            val pmSigBytes: ByteArray? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                    ?: pkgInfo.signingInfo?.signingCertificateHistory?.firstOrNull()
            } else {
                null
            } ?: pkgInfo.signatures?.firstOrNull())?.toByteArray()

            val sigSha = apksigAudit?.signatureSha256?.takeIf { it.isNotBlank() }
                ?: if (pmSigBytes != null) sha256Hex(pmSigBytes) else ""
            val subject = apksigAudit?.signerSubjects?.firstOrNull()
                ?: pmSigBytes?.let { extractX509Subject(it) }
                ?: ""

            val expectedFp = expectedFingerprintsByPkg[pkgName].orEmpty()
            val fpMatch = sigSha.isNotBlank() && fingerprintsMatch(expectedFp, sigSha)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
            val vName = pkgInfo.versionName.orEmpty()
            val libVer = ExtensionCompatibilityResolver.extractLibVersion(vName)
            val hasFeature = pkgInfo.reqFeatures?.any { it.name == ACTION_TACHIYOMI_EXTENSION } == true ||
                apksigAudit?.hasTachiyomiFeature == true
            val isVerified = apksigAudit?.isSignatureCryptographicallyVerified ?: sigSha.isNotBlank()

            InstalledExtensionAppInfo(
                packageName = pkgName,
                versionCode = vCode,
                versionName = vName,
                sourceApkPath = sourceApkPath,
                signatureSha256 = sigSha,
                signerSubject = subject,
                verifiedSchemes = apksigAudit?.verifiedSchemes ?: if (sigSha.isNotBlank()) listOf("pm") else emptyList(),
                isSignatureVerified = isVerified,
                tachiyomiExtensionClass = extClass,
                isNsfw = isNsfw,
                ipcAuthority = authority,
                libVersion = libVer,
                hasTachiyomiFeature = hasFeature,
                matchesExpectedFingerprint = expectedFp.isNotBlank() && fpMatch
            )
        }
    }

    @Deprecated(
        "Use discoverInstalledExtensionPackages instead",
        replaceWith = ReplaceWith("discoverInstalledExtensionPackages(context, expectedFingerprintsByPkg)")
    )
    fun discoverInstalledCompanionPackages(
        context: Context,
        expectedFingerprintsByPkg: Map<String, String> = emptyMap()
    ): List<InstalledExtensionAppInfo> = discoverInstalledExtensionPackages(context, expectedFingerprintsByPkg)

    private fun collectApksigErrors(result: ApkVerifier.Result): List<String> {
        val out = mutableListOf<String>()
        result.errors.forEach { out.add(it.toString()) }
        result.v1SchemeSigners.forEach { s -> s.errors.forEach { out.add(it.toString()) } }
        result.v2SchemeSigners.forEach { s -> s.errors.forEach { out.add(it.toString()) } }
        result.v3SchemeSigners.forEach { s -> s.errors.forEach { out.add(it.toString()) } }
        return out.distinct()
    }

    private fun hasApkSigningBlockMagic(apkFile: File): Boolean {
        return runCatching {
            val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
            val len = apkFile.length()
            if (len < 64L) return false
            // Search last 64 KiB for APK Signing Block 42 magic
            val windowSize = minOf(len, 65_536L).toInt()
            val buf = ByteArray(windowSize)
            java.io.RandomAccessFile(apkFile, "r").use { raf ->
                raf.seek(len - windowSize)
                raf.readFully(buf)
            }
            for (i in 0..(buf.size - magic.size)) {
                var matched = true
                for (j in magic.indices) {
                    if (buf[i + j] != magic[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return true
            }
            false
        }.getOrDefault(false)
    }

    private fun extractX509Subject(certBytes: ByteArray): String? {
        return runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as? X509Certificate
            cert?.subjectX500Principal?.name
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
