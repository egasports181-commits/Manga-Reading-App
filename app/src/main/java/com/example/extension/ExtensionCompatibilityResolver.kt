package com.example.extension

import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ApkIncompatibilityCategory
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionLifecycleState

data class CompatibilityEvaluation(
    val status: ExtensionCompatibilityStatus,
    val libVersion: String,
    val reason: String,
    val canExecuteInKoma: Boolean,
    val category: ApkIncompatibilityCategory = ApkIncompatibilityCategory.NONE,
    val nativeEquivalentSourceId: String? = null
) {
    val canExecuteInKiyomi: Boolean get() = canExecuteInKoma
}

data class ExtensionCompatibilityMatrix(
    val repositoryImported: Boolean,
    val metadataRecognized: Boolean,
    val apkInspected: Boolean,
    val apkVerified: Boolean,
    val extensionCompatible: Boolean,
    val extensionActivated: Boolean,
    val capabilityLevel: String,
    val category: ApkIncompatibilityCategory,
    val nativeEquivalentSourceId: String? = null,
    val sourceFunctionalStatus: String
)

data class ExtensionLifecycleSummary(
    val primaryState: ExtensionLifecycleState,
    val isDownloaded: Boolean,
    val isVerified: Boolean,
    val isCompatible: Boolean,
    val isUnsupported: Boolean,
    val isActive: Boolean,
    val badges: List<String>,
    val summaryText: String,
    val incompatibilityCategory: ApkIncompatibilityCategory = ApkIncompatibilityCategory.NONE,
    val matrix: ExtensionCompatibilityMatrix? = null,
    val nativeEquivalentSourceId: String? = null
)

/**
 * Maps well-known extensions to built-in native adapters when available directly inside Kiyomi.
 */
object NativeExtensionMappingRegistry {
    data class NativeEquivalentSpec(
        val sourceId: String,
        val sourceName: String,
        val description: String
    )

    fun findNativeEquivalent(pkgId: String, extensionName: String = ""): NativeEquivalentSpec? {
        val normPkg = pkgId.trim().lowercase()
        val normName = extensionName.trim().lowercase()
        return when {
            normPkg.endsWith(".mangadex") || normPkg.contains("tachiyomi.extension.all.mangadex") ||
                normName == "mangadex" -> NativeEquivalentSpec(
                sourceId = "mangadex",
                sourceName = "MangaDex (Native Kiyomi v5 API)",
                description = "Kiyomi also natively implements the official MangaDex v5 REST API directly."
            )
            else -> null
        }
    }
}

/**
 * Evaluates compatibility of:
 * - Native Mihon / Tachiyomi APK extensions (`extensions-lib` v1.2..v1.6)
 * - Kiyomi declarative descriptors (`koma_json_v1`)
 * - Independently installed companion IPC apps (`koma_ipc_v1`)
 * - Built-in native source equivalents
 *
 * Uses `apksig` signature verification + explicit signer trust instead of rejecting
 * valid v2/v3-signed Mihon extensions.
 */
object ExtensionCompatibilityResolver {

    const val LIB_VERSION_MIN = 1.2
    const val LIB_VERSION_MAX = 1.6

    fun extractLibVersion(versionName: String): String {
        val parts = versionName.trim().split(".")
        return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else versionName.trim()
    }

    fun evaluateMetadata(
        format: ExtensionFormat,
        versionName: String,
        isRepoTrusted: Boolean,
        signatureMatch: Boolean,
        explicitLibVersion: String? = null,
        pkgId: String = "",
        extensionName: String = ""
    ): CompatibilityEvaluation {
        if (format == ExtensionFormat.KOMA_JSON_ADAPTER) {
            return if (!isRepoTrusted) {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                    libVersion = "koma-1",
                    reason = "Repository must be marked trusted before activating this declarative adapter.",
                    canExecuteInKoma = false,
                    category = ApkIncompatibilityCategory.UNTRUSTED
                )
            } else {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON,
                    libVersion = "koma-1",
                    reason = "Compatible sandboxed Kiyomi JSON REST adapter (koma_json_v1, API v1).",
                    canExecuteInKoma = true,
                    category = ApkIncompatibilityCategory.NONE
                )
            }
        }

        if (format == ExtensionFormat.COMPANION_IPC_EXTENSION) {
            return if (!isRepoTrusted) {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                    libVersion = "koma-ipc-1",
                    reason = "Repository must be marked trusted before connecting to companion IPC extension.",
                    canExecuteInKoma = false,
                    category = ApkIncompatibilityCategory.UNTRUSTED
                )
            } else if (!signatureMatch) {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH,
                    libVersion = "koma-ipc-1",
                    reason = "Installed companion APK signature does not match repository signingKeyFingerprint.",
                    canExecuteInKoma = false,
                    category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE
                )
            } else {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.COMPANION_IPC_READY,
                    libVersion = "koma-ipc-1",
                    reason = "Verified Companion IPC ContentProvider.",
                    canExecuteInKoma = true,
                    category = ApkIncompatibilityCategory.NONE
                )
            }
        }

        val libVerStr = explicitLibVersion?.takeIf { it.isNotBlank() } ?: extractLibVersion(versionName)
        val libNumeric = libVerStr.toDoubleOrNull()
        val nativeEq = if (pkgId.isNotBlank() || extensionName.isNotBlank()) {
            NativeExtensionMappingRegistry.findNativeEquivalent(pkgId, extensionName)
        } else null

        if (!signatureMatch) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH,
                libVersion = libVerStr,
                reason = "Cat B: Repository signing key fingerprint mismatch.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        if (!isRepoTrusted) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                libVersion = libVerStr,
                reason = "Repository is not marked trusted. Review signing fingerprint and trust repository first.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.UNTRUSTED,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        if (libNumeric != null && (libNumeric < LIB_VERSION_MIN || libNumeric > LIB_VERSION_MAX)) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE,
                libVersion = libVerStr,
                reason = "Cat A (API Mismatch): Extension targets Mihon extensions-lib $libVerStr, outside supported $LIB_VERSION_MIN–$LIB_VERSION_MAX range.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.API_VERSION_INCOMPATIBLE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        return CompatibilityEvaluation(
            status = ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA,
            libVersion = libVerStr,
            reason = "Compatible Mihon Extension metadata (extensions-lib $libVerStr). Tap Download & Verify to inspect APK signature & install.",
            canExecuteInKoma = true,
            category = ApkIncompatibilityCategory.NONE,
            nativeEquivalentSourceId = nativeEq?.sourceId
        )
    }

    /**
     * Evaluates an inspected APK archive using Google `apksig` verification results,
     * Android manifest markers, repository fingerprint matching, signer trust approval,
     * and extensions-lib API compatibility (1.2–1.6).
     */
    fun evaluateApkArchive(
        pkg: ExtensionPackageEntity,
        inspection: ApkInspectionResult,
        isRepoTrusted: Boolean,
        expectedFingerprint: String,
        installedCompanion: InstalledExtensionAppInfo? = null,
        isSignerTrusted: Boolean = true,
        isInstalledOnDevice: Boolean = false
    ): CompatibilityEvaluation {
        val effectiveVersionName = inspection.versionName.ifBlank { pkg.versionName }
        val libVerStr = inspection.libVersion.takeIf { it.isNotBlank() }
            ?: pkg.libVersion.takeIf { it.isNotBlank() }
            ?: extractLibVersion(effectiveVersionName)
        val libNumeric = libVerStr.toDoubleOrNull()
        val nativeEq = NativeExtensionMappingRegistry.findNativeEquivalent(
            inspection.packageName.ifBlank { pkg.pkgId },
            pkg.name
        )

        // 1. Corrupt ZIP / missing manifest / PM failure
        if (!inspection.isValidZipArchive || !inspection.hasAndroidManifestEntry || !inspection.packageManagerParsed) {
            val detail = inspection.structuralIssues.joinToString(" ").ifBlank {
                "Archive is missing valid ZIP/AndroidManifest structure."
            }
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.MALFORMED_APK,
                libVersion = libVerStr,
                reason = "Cat B (Invalid/Corrupted APK): $detail",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 2. Package ID mismatch or other non-signature structural error
        if (!pkg.pkgId.isBlank() && inspection.packageName.isNotBlank() && inspection.packageName != pkg.pkgId) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.MALFORMED_APK,
                libVersion = libVerStr,
                reason = "Cat B (Package Mismatch): APK package '${inspection.packageName}' does not match '${pkg.pkgId}'.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 3. Unsigned APK check (no v1/v2/v3 cert)
        if (inspection.isUnsignedApk || (!inspection.isSignatureCryptographicallyVerified && inspection.signatureSha256.isBlank())) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.UNSIGNED_APK,
                libVersion = libVerStr,
                reason = "Cat B (Unsigned APK): ${inspection.signatureVerificationDetail.ifBlank { "No v1/v2/v3 signing certificate found." }}",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 4. Tampered / broken cryptographic signature
        if (inspection.isTamperedApk) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.MALFORMED_APK,
                libVersion = libVerStr,
                reason = "Cat B (Tampered APK): ${inspection.signatureVerificationDetail}",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 5. Fingerprint mismatch against pinned repository signingKeyFingerprint
        if (expectedFingerprint.isNotBlank() && !ExtensionSecurityManager.fingerprintsMatch(expectedFingerprint, inspection.signatureSha256)) {
            val actualNote = "cert ${inspection.signatureSha256.take(12)}…"
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH,
                libVersion = libVerStr,
                reason = "Cat B (Signature Mismatch): APK $actualNote does not match repository signingKeyFingerprint (${expectedFingerprint.take(12)}…).",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 6. Repository trust gate
        if (!isRepoTrusted) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                libVersion = libVerStr,
                reason = "Repository is untrusted. Review signing fingerprint and trust repository first.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.UNTRUSTED,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 7. Signer trust gate
        if (!isSignerTrusted) {
            val schemes = inspection.verifiedSchemes.joinToString("+").ifBlank { "signed" }
            val subject = inspection.signerSubjects.firstOrNull()?.let { " ($it)" }.orEmpty()
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.UNTRUSTED_SIGNER,
                libVersion = libVerStr,
                reason = "Untrusted Signer: APK signature verified via [$schemes]$subject, " +
                    "SHA-256 ${inspection.signatureSha256.take(16)}…. Tap 'Trust Signer' before installing.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.UNTRUSTED,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 8. Verified Companion IPC provider already installed
        if (installedCompanion != null &&
            installedCompanion.ipcAuthority.isNotBlank() &&
            (expectedFingerprint.isBlank() || installedCompanion.matchesExpectedFingerprint)
        ) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.COMPANION_IPC_READY,
                libVersion = "koma-ipc-1",
                reason = "Verified standalone Companion IPC app '${installedCompanion.packageName}' exposing '${installedCompanion.ipcAuthority}'.",
                canExecuteInKoma = true,
                category = ApkIncompatibilityCategory.NONE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 9. Category D: Missing Mihon/Tachiyomi markers
        val hasMihonMarkers = inspection.extensionClass.isNotBlank() ||
            inspection.hasTachiyomiFeature ||
            inspection.packageName.contains(".tachiyomi.extension") ||
            inspection.ipcAuthority.isNotBlank()
        if (!hasMihonMarkers) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE,
                libVersion = libVerStr,
                reason = "Cat D (Different Architecture): Valid Android APK ('${inspection.packageName}'), " +
                    "missing 'tachiyomi.extension.class' metadata or 'tachiyomi.extension' feature.",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.DIFFERENT_ARCHITECTURE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        // 10. Category A: API version range check
        if (libNumeric != null && (libNumeric < LIB_VERSION_MIN || libNumeric > LIB_VERSION_MAX)) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE,
                libVersion = libVerStr,
                reason = "Cat A (API Version Incompatible): APK targets Mihon extensions-lib $libVerStr (supported: $LIB_VERSION_MIN–$LIB_VERSION_MAX).",
                canExecuteInKoma = false,
                category = ApkIncompatibilityCategory.API_VERSION_INCOMPATIBLE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }

        val schemeNote = inspection.verifiedSchemes.joinToString("+").ifBlank { "verified" }
        val entryNote = inspection.extensionClass.takeIf { it.isNotBlank() }?.let { " Entry class '$it'." }.orEmpty()
        return if (isInstalledOnDevice) {
            CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
                libVersion = libVerStr,
                reason = "Verified & Installed Mihon APK (schemes: $schemeNote, libVersion $libVerStr).$entryNote Active in Kiyomi Sources.",
                canExecuteInKoma = true,
                category = ApkIncompatibilityCategory.NONE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        } else {
            CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.VERIFIED_MIHON_READY,
                libVersion = libVerStr,
                reason = "Verified Trusted Mihon APK (schemes: $schemeNote, libVersion $libVerStr).$entryNote Tap Install APK to complete OS installation.",
                canExecuteInKoma = true,
                category = ApkIncompatibilityCategory.NONE,
                nativeEquivalentSourceId = nativeEq?.sourceId
            )
        }
    }

    fun resolveLifecycle(pkg: ExtensionPackageEntity): ExtensionLifecycleSummary {
        val status = runCatching {
            ExtensionCompatibilityStatus.valueOf(pkg.compatibilityStatus)
        }.getOrDefault(ExtensionCompatibilityStatus.UNKNOWN)

        val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
        val isIpc = pkg.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id ||
            status == ExtensionCompatibilityStatus.COMPANION_IPC_READY
        val nativeEq = NativeExtensionMappingRegistry.findNativeEquivalent(pkg.pkgId, pkg.name)

        val isDownloaded = pkg.localApkPath.isNotBlank() || pkg.isInstalled
        val hasCert = pkg.signatureHash.isNotBlank() || pkg.installedSignatureSha256.isNotBlank()
        val isVerified = isDownloaded &&
            hasCert &&
            status != ExtensionCompatibilityStatus.UNSIGNED_APK &&
            status != ExtensionCompatibilityStatus.SIGNATURE_MISMATCH &&
            status != ExtensionCompatibilityStatus.MALFORMED_APK &&
            status != ExtensionCompatibilityStatus.UNTRUSTED_REPO &&
            pkg.installError == null

        val isCompatible = when (status) {
            ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON,
            ExtensionCompatibilityStatus.COMPANION_IPC_READY,
            ExtensionCompatibilityStatus.VERIFIED_MIHON_READY,
            ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
            ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA -> true
            else -> false
        }
        val isActive = isCompatible && pkg.isInstalled && pkg.isEnabled &&
            status != ExtensionCompatibilityStatus.MIHON_LOAD_FAILED &&
            status != ExtensionCompatibilityStatus.UNTRUSTED_SIGNER
        val isUnsupported = !isCompatible && status != ExtensionCompatibilityStatus.UNTRUSTED_SIGNER

        val category = when (status) {
            ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON,
            ExtensionCompatibilityStatus.COMPANION_IPC_READY,
            ExtensionCompatibilityStatus.VERIFIED_MIHON_READY,
            ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE,
            ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA -> ApkIncompatibilityCategory.NONE
            ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> ApkIncompatibilityCategory.API_VERSION_INCOMPATIBLE
            ExtensionCompatibilityStatus.UNSIGNED_APK,
            ExtensionCompatibilityStatus.MALFORMED_APK,
            ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> ApkIncompatibilityCategory.MALFORMED_OR_INCOMPLETE
            ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE -> ApkIncompatibilityCategory.DIFFERENT_ARCHITECTURE
            ExtensionCompatibilityStatus.UNTRUSTED_SIGNER,
            ExtensionCompatibilityStatus.UNTRUSTED_REPO -> ApkIncompatibilityCategory.UNTRUSTED
            ExtensionCompatibilityStatus.MIHON_LOAD_FAILED,
            ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED,
            ExtensionCompatibilityStatus.NATIVE_EQUIVALENT_AVAILABLE,
            ExtensionCompatibilityStatus.UNKNOWN -> ApkIncompatibilityCategory.MIHON_DEX_POLICY_RESTRICTED
        }

        val badges = buildList {
            if (isDownloaded) add("Downloaded")
            if (isVerified) add("Verified (SHA-256)")
            if (isCompatible) add("Compatible")
            if (status == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER) add("Signer Untrusted")
            if (nativeEq != null && !isActive) add("Native Alt Available")
            if (isActive) {
                add("Active")
            } else if (pkg.isInstalled && status == ExtensionCompatibilityStatus.MIHON_LOAD_FAILED) {
                add("Load Failed")
            } else if (isCompatible && pkg.isInstalled) {
                add("Installed • Paused")
            }
            if (isUnsupported) {
                when (status) {
                    ExtensionCompatibilityStatus.UNSIGNED_APK -> add("Cat B • Unsigned APK")
                    ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> add("Cat B • Sig Mismatch")
                    ExtensionCompatibilityStatus.MALFORMED_APK -> add("Cat B • Invalid APK")
                    ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> add("Cat A • Lib ${pkg.libVersion}")
                    ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE -> add("Cat D • Non-Mihon APK")
                    ExtensionCompatibilityStatus.UNTRUSTED_REPO -> add("Untrusted Repo")
                    ExtensionCompatibilityStatus.MIHON_LOAD_FAILED -> add("Load Error")
                    ExtensionCompatibilityStatus.NATIVE_EQUIVALENT_AVAILABLE -> add("Native Alt Ready")
                    else -> add("Mihon Extension")
                }
            }
        }

        val primaryState = when {
            isActive -> ExtensionLifecycleState.ACTIVE
            status == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY || isVerified -> ExtensionLifecycleState.VERIFIED
            isCompatible -> ExtensionLifecycleState.COMPATIBLE
            isDownloaded -> ExtensionLifecycleState.DOWNLOADED
            else -> ExtensionLifecycleState.UNSUPPORTED
        }

        val summaryText = when {
            isActive && isIpc -> "Active Companion IPC (koma_ipc_v1)"
            isActive && !isKoma -> "Active Mihon Extension in Kiyomi Sources"
            isActive -> "Active sandboxed source (koma_json_v1)"
            status == ExtensionCompatibilityStatus.MIHON_LOAD_FAILED -> pkg.installError ?: "Failed loading installed Mihon extension"
            status == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER -> "Signed APK • Approve signer certificate to install"
            status == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY -> "Verified & trusted • Tap Install APK"
            isCompatible && pkg.isInstalled -> "Installed & paused (${pkg.format})"
            isCompatible && isIpc -> "Companion IPC ready • Tap Activate"
            isCompatible && isKoma -> "Compatible (koma_json_v1) • Ready to install"
            isCompatible -> "Compatible Mihon Extension (${pkg.libVersion.ifBlank { "1.4" }}) • Tap Download & Verify"
            status == ExtensionCompatibilityStatus.UNSIGNED_APK -> "Cat B • Unsigned APK (rejected)"
            status == ExtensionCompatibilityStatus.MALFORMED_APK -> "Cat B • Malformed or tampered APK"
            status == ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE -> "Cat D • Missing Mihon manifest"
            status == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> "Cat B • APK signature mismatch"
            status == ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> "Cat A • Incompatible libVersion ${pkg.libVersion}"
            status == ExtensionCompatibilityStatus.UNTRUSTED_REPO -> "Blocked • Repository untrusted"
            nativeEq != null -> "Native '${nativeEq.sourceName}' built-in"
            else -> "Indexed Mihon Extension (${pkg.libVersion.ifBlank { "1.x" }})"
        }

        val matrix = ExtensionCompatibilityMatrix(
            repositoryImported = pkg.repoId != null,
            metadataRecognized = pkg.pkgId.isNotBlank() && pkg.name.isNotBlank(),
            apkInspected = isDownloaded || status == ExtensionCompatibilityStatus.MALFORMED_APK ||
                status == ExtensionCompatibilityStatus.UNSIGNED_APK ||
                status == ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE,
            apkVerified = isVerified,
            extensionCompatible = isCompatible,
            extensionActivated = isActive,
            capabilityLevel = when {
                isActive && isIpc -> "Active Companion IPC"
                isActive && !isKoma -> "Active Mihon Extension (${pkg.libVersion})"
                isActive -> "Active Declarative Adapter"
                status == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY -> "Verified • Ready for OS Install"
                status == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER -> "Signer Trust Approval Required"
                isCompatible -> "Ready to Download & Install"
                else -> category.label
            },
            category = category,
            nativeEquivalentSourceId = nativeEq?.sourceId,
            sourceFunctionalStatus = when {
                isActive -> "Active in Kiyomi Sources (Browse / Search / Chapters / Read)"
                status == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY -> "APK verified; awaiting OS installation confirmation"
                status == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER -> "Awaiting user approval for signer certificate"
                isCompatible -> "Ready to download & verify APK"
                else -> pkg.installError ?: summaryText
            }
        )

        return ExtensionLifecycleSummary(
            primaryState = primaryState,
            isDownloaded = isDownloaded,
            isVerified = isVerified,
            isCompatible = isCompatible,
            isUnsupported = isUnsupported,
            isActive = isActive,
            badges = badges,
            summaryText = summaryText,
            incompatibilityCategory = category,
            matrix = matrix,
            nativeEquivalentSourceId = nativeEq?.sourceId
        )
    }
}
