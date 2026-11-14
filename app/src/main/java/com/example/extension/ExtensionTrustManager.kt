package com.example.extension

import android.content.Context
import org.json.JSONObject

/**
 * Persists explicit user-approved signer certificate SHA-256 fingerprints.
 * Never auto-trusts arbitrary certificates; extensions signed with an unrecognized
 * certificate require explicit user approval before installation/classloading.
 */
class ExtensionTrustManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun isSignerTrusted(signatureSha256: String): Boolean {
        val norm = ExtensionSecurityManager.normalizeFingerprint(signatureSha256)
        if (norm.isBlank()) return false
        return loadMap().keys.any { ExtensionSecurityManager.fingerprintsMatch(it, norm) }
    }

    @Synchronized
    fun trustSigner(signatureSha256: String, label: String = "") {
        val norm = ExtensionSecurityManager.normalizeFingerprint(signatureSha256)
        require(norm.isNotBlank()) { "Cannot trust a blank certificate fingerprint." }
        val current = loadMap().toMutableMap()
        current[norm] = label.ifBlank { "Trusted Signer (${norm.take(12)})" }
        saveMap(current)
    }

    @Synchronized
    fun untrustSigner(signatureSha256: String) {
        val norm = ExtensionSecurityManager.normalizeFingerprint(signatureSha256)
        if (norm.isBlank()) return
        val current = loadMap().toMutableMap()
        val matchingKeys = current.keys.filter { ExtensionSecurityManager.fingerprintsMatch(it, norm) }
        matchingKeys.forEach { current.remove(it) }
        saveMap(current)
    }

    @Synchronized
    fun getTrustedSigners(): Map<String, String> = loadMap()

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_SIGNERS_JSON).apply()
    }

    private fun loadMap(): Map<String, String> {
        val raw = prefs.getString(KEY_SIGNERS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, obj.optString(k))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveMap(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_SIGNERS_JSON, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kiyomi_extension_trust"
        private const val KEY_SIGNERS_JSON = "trusted_signers_json"
    }
}
