package com.example.extension

import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat

data class CompatibilityEvaluation(
    val status: ExtensionCompatibilityStatus,
    val libVersion: String,
    val reason: String,
    val canExecuteInKoma: Boolean
)

/**
 * Evaluates compatibility of Koma declarative descriptors and Mihon APK extensions.
 * Strictly follows Mihon's libVersion rules (1.2..1.5) while disclosing honestly
 * why third-party DEX APKs cannot be dynamically loaded in-process.
 */
object ExtensionCompatibilityResolver {

    const val LIB_VERSION_MIN = 1.2
    const val LIB_VERSION_MAX = 1.5

    fun extractLibVersion(versionName: String): String {
        val parts = versionName.trim().split(".")
        return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else versionName.trim()
    }

    fun evaluateMetadata(
        format: ExtensionFormat,
        versionName: String,
        isRepoTrusted: Boolean,
        signatureMatch: Boolean
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
                    reason = "Executable sandboxed Koma JSON REST adapter.",
                    canExecuteInKoma = true
                )
            }
        }

        val libVerStr = extractLibVersion(versionName)
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
            reason = "Mihon APK format (libVersion $libVerStr). Archive & signatures can be verified, " +
                "but dynamic DEX classloading requires eu.kanade.tachiyomi.extensions-lib and is restricted by Android Play DCL security policy.",
            canExecuteInKoma = false
        )
    }
}
