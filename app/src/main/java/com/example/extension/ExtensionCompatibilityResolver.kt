package com.example.extension

import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionLifecycleState

data class CompatibilityEvaluation(
    val status: ExtensionCompatibilityStatus,
    val libVersion: String,
    val reason: String,
    val canExecuteInKoma: Boolean
)

data class ExtensionLifecycleSummary(
    val primaryState: ExtensionLifecycleState,
    val isDownloaded: Boolean,
    val isVerified: Boolean,
    val isCompatible: Boolean,
    val isUnsupported: Boolean,
    val isActive: Boolean,
    val badges: List<String>,
    val summaryText: String
)

/**
 * Evaluates compatibility of Koma declarative descriptors (`koma_json_v1`) and native
 * Mihon/Tachiyomi APK extensions (`extensions-lib` v1.2..v1.6).
 * Never executes downloaded DEX code in-process; native APKs are honestly reported
 * as unsupported for execution even after APK download & SHA-256 signature verification.
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
        explicitLibVersion: String? = null
    ): CompatibilityEvaluation {
        if (format == ExtensionFormat.KOMA_JSON_ADAPTER) {
            return if (!isRepoTrusted) {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                    libVersion = "koma-1",
                    reason = "Repository must be marked trusted before activating this declarative adapter.",
                    canExecuteInKoma = false
                )
            } else {
                CompatibilityEvaluation(
                    status = ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON,
                    libVersion = "koma-1",
                    reason = "Compatible sandboxed Koma JSON REST adapter (koma_json_v1).",
                    canExecuteInKoma = true
                )
            }
        }

        val libVerStr = explicitLibVersion?.takeIf { it.isNotBlank() } ?: extractLibVersion(versionName)
        val libNumeric = libVerStr.toDoubleOrNull()

        if (!signatureMatch) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.SIGNATURE_MISMATCH,
                libVersion = libVerStr,
                reason = "APK signing certificate does not match repository signingKeyFingerprint.",
                canExecuteInKoma = false
            )
        }

        if (!isRepoTrusted) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.UNTRUSTED_REPO,
                libVersion = libVerStr,
                reason = "Repository is not marked trusted. Inspect fingerprint and trust repository first.",
                canExecuteInKoma = false
            )
        }

        if (libNumeric != null && (libNumeric < LIB_VERSION_MIN || libNumeric > LIB_VERSION_MAX)) {
            return CompatibilityEvaluation(
                status = ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE,
                libVersion = libVerStr,
                reason = "Extension targets Mihon libVersion $libVerStr, outside supported $LIB_VERSION_MIN–$LIB_VERSION_MAX range.",
                canExecuteInKoma = false
            )
        }

        return CompatibilityEvaluation(
            status = ExtensionCompatibilityStatus.VERIFIED_MIHON_RUNTIME_RESTRICTED,
            libVersion = libVerStr,
            reason = "Native Mihon APK (libVersion $libVerStr). Unsupported for in-app execution: compiled DEX bytecode cannot run safely inside Koma per Google Play DCL & Android security policy (requires a koma_json_v1 declarative manifest).",
            canExecuteInKoma = false
        )
    }

    fun resolveLifecycle(pkg: ExtensionPackageEntity): ExtensionLifecycleSummary {
        val status = runCatching {
            ExtensionCompatibilityStatus.valueOf(pkg.compatibilityStatus)
        }.getOrDefault(ExtensionCompatibilityStatus.UNKNOWN)

        val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
        val isDownloaded = pkg.localApkPath.isNotBlank()
        val isVerified = isDownloaded &&
            pkg.signatureHash.isNotBlank() &&
            status != ExtensionCompatibilityStatus.SIGNATURE_MISMATCH &&
            status != ExtensionCompatibilityStatus.UNTRUSTED_REPO &&
            pkg.installError == null
        val isCompatible = isKoma && status == ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON
        val isActive = isCompatible && pkg.isInstalled && pkg.isEnabled
        val isUnsupported = !isCompatible

        val badges = buildList {
            if (isDownloaded) add("Downloaded")
            if (isVerified) add("Verified (SHA-256)")
            if (isCompatible) add("Compatible")
            if (isActive) {
                add("Active")
            } else if (isCompatible && pkg.isInstalled) {
                add("Installed • Disabled")
            }
            if (isUnsupported) {
                when (status) {
                    ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> add("Unsupported • Sig Mismatch")
                    ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> add("Unsupported • Lib ${pkg.libVersion}")
                    ExtensionCompatibilityStatus.UNTRUSTED_REPO -> add("Untrusted Repo")
                    else -> add("Unsupported (Native APK/DEX)")
                }
            }
        }

        val primaryState = when {
            isActive -> ExtensionLifecycleState.ACTIVE
            isCompatible -> ExtensionLifecycleState.COMPATIBLE
            isVerified -> ExtensionLifecycleState.VERIFIED
            isDownloaded -> ExtensionLifecycleState.DOWNLOADED
            else -> ExtensionLifecycleState.UNSUPPORTED
        }

        val summaryText = when {
            isActive -> "Active sandboxed source (koma_json_v1)"
            isCompatible && pkg.isInstalled -> "Installed & paused (koma_json_v1)"
            isCompatible -> "Compatible (koma_json_v1) • Ready to install"
            isVerified -> "Downloaded & SHA-256 verified • Unsupported native DEX APK (not installed)"
            isDownloaded -> "Downloaded • Verification failed or pending"
            status == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH -> "Unsupported • APK signature mismatch"
            status == ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE -> "Unsupported • libVersion ${pkg.libVersion}"
            status == ExtensionCompatibilityStatus.UNTRUSTED_REPO -> "Blocked • Repository untrusted"
            else -> "Unsupported • Native Mihon APK (${pkg.libVersion.ifBlank { "DEX" }})"
        }

        return ExtensionLifecycleSummary(
            primaryState = primaryState,
            isDownloaded = isDownloaded,
            isVerified = isVerified,
            isCompatible = isCompatible,
            isUnsupported = isUnsupported,
            isActive = isActive,
            badges = badges,
            summaryText = summaryText
        )
    }
}
