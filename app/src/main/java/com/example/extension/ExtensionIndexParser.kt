package com.example.extension

import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionSourceMeta
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class RepoCompanionMeta(
    val name: String = "",
    val displayName: String = "",
    val website: String = "",
    val signingKeyFingerprint: String = "",
    val iconUrl: String = ""
)

data class ParsedExtensionIndex(
    val formatLabel: String,
    val repoName: String? = null,
    val packages: List<ExtensionPackageEntity>
)

/**
 * Parses Mihon/Tachiyomi `index.pb` (raw or gzipped Protobuf),
 * `index.json` / `index.min.json`, Koma declarative `koma_json_v1`,
 * and sibling `repo.json` metadata.
 */
object ExtensionIndexParser {

    private val jsonSerializer = Json { encodeDefaults = true }

    fun parseRepoJson(rawText: String): RepoCompanionMeta? {
        return runCatching {
            val root = JSONObject(rawText.trim())
            val meta = root.optJSONObject("meta") ?: root
            RepoCompanionMeta(
                name = meta.optString("name").trim(),
                displayName = meta.optString("displayName").trim(),
                website = meta.optString("website").trim(),
                signingKeyFingerprint = meta.optString("signingKeyFingerprint").trim(),
                iconUrl = meta.optString("icon").trim()
            )
        }.getOrNull()
    }

    fun parseIndexBytes(
        repoId: Long,
        indexUrl: String,
        rawBytes: ByteArray
    ): ParsedExtensionIndex {
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("Repository index response was empty.")
        }
        val bytes = maybeGunzip(rawBytes)
        val digest = sha256Hex(bytes)
        val baseRepoDir = indexUrl.substringBeforeLast("/", "")

        // Detect text JSON vs binary Protobuf cleanly
        val trimmedText = tryUtf8TextIfJson(bytes)
        if (trimmedText != null) {
            if (trimmedText.startsWith("{")) {
                val root = JSONObject(trimmedText)
                val extArr = root.optJSONArray("extensions")
                    ?: throw IllegalArgumentException("JSON object is missing 'extensions' array.")
                val packages = parseJsonExtensionsArray(
                    repoId = repoId,
                    baseRepoDir = baseRepoDir,
                    arr = extArr,
                    digest = digest,
                    isKomaWrapper = true
                )
                return ParsedExtensionIndex(
                    formatLabel = if (packages.any { it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id }) {
                        "koma_json_v1"
                    } else "index.json",
                    repoName = root.optString("name").takeIf { it.isNotBlank() },
                    packages = packages
                )
            }
            if (trimmedText.startsWith("[")) {
                val arr = JSONArray(trimmedText)
                val packages = parseJsonExtensionsArray(
                    repoId = repoId,
                    baseRepoDir = baseRepoDir,
                    arr = arr,
                    digest = digest,
                    isKomaWrapper = false
                )
                return ParsedExtensionIndex(
                    formatLabel = "index.min.json",
                    repoName = null,
                    packages = packages
                )
            }
        }

        // Binary Protobuf `index.pb`
        val packages = parseProtobufIndex(repoId, baseRepoDir, bytes, digest)
        return ParsedExtensionIndex(
            formatLabel = "index.pb",
            repoName = null,
            packages = packages
        )
    }

    private fun maybeGunzip(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var read = gz.read(buf)
                while (read >= 0) {
                    out.write(buf, 0, read)
                    read = gz.read(buf)
                }
                return out.toByteArray()
            }
        }
        return bytes
    }

    private fun tryUtf8TextIfJson(bytes: ByteArray): String? {
        // Inspect first non-whitespace byte; JSON always starts with '{' or '['
        var i = 0
        // Skip UTF-8 BOM if present
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        if (i >= bytes.size) return null
        val first = bytes[i].toInt().toChar()
        if (first != '{' && first != '[') return null
        return String(bytes, i, bytes.size - i, Charsets.UTF_8).trim()
    }

    private fun parseJsonExtensionsArray(
        repoId: Long,
        baseRepoDir: String,
        arr: JSONArray,
        digest: String,
        isKomaWrapper: Boolean
    ): List<ExtensionPackageEntity> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val adapterObj = obj.optJSONObject("adapter")
            if (isKomaWrapper && adapterObj != null) {
                val pkgId = obj.optString("id").ifBlank { obj.optString("pkg", "koma.ext.$i") }
                val vCode = obj.optInt("versionCode", obj.optInt("code", 1))
                val vName = obj.optString("versionName", obj.optString("version", "1.0.0"))
                add(
                    ExtensionPackageEntity(
                        pkgId = pkgId,
                        repoId = repoId,
                        name = obj.optString("name", pkgId),
                        versionName = vName,
                        versionCode = vCode,
                        author = obj.optString("author", "Koma Adapter"),
                        description = obj.optString("description", "Sandboxed Koma declarative REST source"),
                        language = obj.optString("lang", "en"),
                        iconUrl = obj.optString("iconUrl"),
                        format = ExtensionFormat.KOMA_JSON_ADAPTER.id,
                        adapterJson = adapterObj.toString(),
                        latestVersionCode = vCode,
                        latestVersionName = vName,
                        signatureHash = obj.optString("sha256", digest.take(16)),
                        popularity = obj.optInt("popularity", (100 - i).coerceAtLeast(1)),
                        permissionsSummary = "HTTPS requests to ${adapterObj.optString("baseUrl", "declared host")} only",
                        compatibilityStatus = ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON.name,
                        compatibilityReason = "Runs natively inside Koma's declarative JSON sandbox."
                    )
                )
            } else {
                val pkg = obj.optString("pkg").ifBlank { obj.optString("id", "mihon.ext.$i") }
                val apkName = obj.optString("apk", "")
                val vCode = obj.optInt("code", obj.optInt("versionCode", 1))
                val vName = obj.optString("version", obj.optString("versionName", "1.0"))
                val lang = obj.optString("lang", "en")
                val nsfw = obj.optInt("nsfw", 0) == 1 || obj.optBoolean("nsfw", false)
                val hasReadme = obj.optInt("hasReadme", 0) == 1 || obj.optBoolean("hasReadme", false)
                val sources = parseSourcesJsonArray(obj.optJSONArray("sources"))
                val sourceCount = sources.size
                val rawName = obj.optString("name", pkg)
                    .removePrefix("Tachiyomi: ")
                    .removePrefix("Mihon: ")
                val resolvedApkUrl = resolveApkUrl(baseRepoDir, apkName)
                val resolvedIconUrl = obj.optString("iconUrl").ifBlank {
                    resolveIconUrl(baseRepoDir, pkg)
                }
                val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                    format = ExtensionFormat.MIHON_JSON_INDEX,
                    versionName = vName,
                    isRepoTrusted = true,
                    signatureMatch = true
                )
                add(
                    ExtensionPackageEntity(
                        pkgId = pkg,
                        repoId = repoId,
                        name = rawName,
                        versionName = vName,
                        versionCode = vCode,
                        author = obj.optString("author").ifBlank { "Mihon Extension" },
                        description = buildMihonDescription(rawName, sources, nsfw),
                        language = lang,
                        iconUrl = resolvedIconUrl,
                        format = ExtensionFormat.MIHON_JSON_INDEX.id,
                        adapterJson = "",
                        latestVersionCode = vCode,
                        latestVersionName = vName,
                        signatureHash = digest.take(16),
                        popularity = (sourceCount.coerceAtLeast(1)) * 10,
                        permissionsSummary = if (nsfw) "Mihon APK • 18+ NSFW • $sourceCount source(s)" else "Mihon APK • $sourceCount source(s)",
                        apkName = apkName,
                        apkUrl = resolvedApkUrl,
                        isNsfw = nsfw,
                        hasReadme = hasReadme,
                        libVersion = compat.libVersion,
                        sourcesJson = jsonSerializer.encodeToString(sources),
                        compatibilityStatus = compat.status.name,
                        compatibilityReason = compat.reason
                    )
                )
            }
        }
    }

    private fun parseSourcesJsonArray(arr: JSONArray?): List<ExtensionSourceMeta> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                add(
                    ExtensionSourceMeta(
                        id = s.optLong("id", s.optString("id").toLongOrNull() ?: i.toLong()),
                        name = s.optString("name", "Source ${i + 1}"),
                        lang = s.optString("lang", "en"),
                        baseUrl = s.optString("baseUrl", "")
                    )
                )
            }
        }
    }

    private fun parseProtobufIndex(
        repoId: Long,
        baseRepoDir: String,
        bytes: ByteArray,
        digest: String
    ): List<ExtensionPackageEntity> {
        val reader = ProtobufWireReader(bytes)
        val extBytePayloads = mutableListOf<ByteArray>()

        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (wireType == WIRE_LEN && (fieldNumber == 1 || fieldNumber == 2)) {
                extBytePayloads.add(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        if (extBytePayloads.isEmpty()) {
            throw IllegalArgumentException("Protobuf index contained no extension records.")
        }

        val results = mutableListOf<ExtensionPackageEntity>()
        extBytePayloads.forEachIndexed { idx, payload ->
            val ext = parseProtobufExtensionObject(payload, idx) ?: return@forEachIndexed
            val resolvedApkUrl = resolveApkUrl(baseRepoDir, ext.apkName)
            val resolvedIconUrl = resolveIconUrl(baseRepoDir, ext.pkgName)
            val compat = ExtensionCompatibilityResolver.evaluateMetadata(
                format = ExtensionFormat.MIHON_PROTOBUF_INDEX,
                versionName = ext.versionName,
                isRepoTrusted = true,
                signatureMatch = true
            )
            results.add(
                ExtensionPackageEntity(
                    pkgId = ext.pkgName,
                    repoId = repoId,
                    name = ext.name,
                    versionName = ext.versionName,
                    versionCode = ext.versionCode,
                    author = "Mihon Repository",
                    description = buildMihonDescription(ext.name, ext.sources, ext.nsfw),
                    language = ext.lang,
                    iconUrl = resolvedIconUrl,
                    format = ExtensionFormat.MIHON_PROTOBUF_INDEX.id,
                    adapterJson = "",
                    latestVersionCode = ext.versionCode,
                    latestVersionName = ext.versionName,
                    signatureHash = digest.take(16),
                    popularity = (ext.sources.size.coerceAtLeast(1)) * 10,
                    permissionsSummary = if (ext.nsfw) {
                        "Mihon APK (PB) • 18+ NSFW • ${ext.sources.size} source(s)"
                    } else {
                        "Mihon APK (PB) • ${ext.sources.size} source(s)"
                    },
                    apkName = ext.apkName,
                    apkUrl = resolvedApkUrl,
                    isNsfw = ext.nsfw,
                    hasReadme = ext.hasReadme,
                    libVersion = compat.libVersion,
                    sourcesJson = jsonSerializer.encodeToString(ext.sources),
                    compatibilityStatus = compat.status.name,
                    compatibilityReason = compat.reason
                )
            )
        }
        return results
    }

    private data class ProtoExtensionRaw(
        val name: String,
        val pkgName: String,
        val apkName: String,
        val lang: String,
        val versionCode: Int,
        val versionName: String,
        val nsfw: Boolean,
        val hasReadme: Boolean,
        val sources: List<ExtensionSourceMeta>
    )

    private fun parseProtobufExtensionObject(bytes: ByteArray, fallbackIndex: Int): ProtoExtensionRaw? {
        val reader = ProtobufWireReader(bytes)
        val stringsByField = linkedMapOf<Int, MutableList<String>>()
        val subMessagesByField = linkedMapOf<Int, MutableList<ByteArray>>()
        val varintsByField = linkedMapOf<Int, MutableList<Long>>()

        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                WIRE_VARINT -> {
                    varintsByField.getOrPut(fieldNum) { mutableListOf() }.add(reader.readVarint())
                }
                WIRE_LEN -> {
                    val data = reader.readBytes()
                    // Heuristic: check if this LEN field is a nested ExtensionSourceObject or UTF-8 string
                    if (fieldNum >= 7 && looksLikeSourceProto(data)) {
                        subMessagesByField.getOrPut(fieldNum) { mutableListOf() }.add(data)
                    } else {
                        val str = String(data, Charsets.UTF_8)
                        stringsByField.getOrPut(fieldNum) { mutableListOf() }.add(str)
                    }
                }
                else -> reader.skipField(wireType)
            }
        }

        val allStrings = stringsByField.entries.flatMap { it.value }
        val pkgName = stringsByField[2]?.firstOrNull()?.takeIf { it.contains(".") }
            ?: allStrings.firstOrNull { it.startsWith("eu.kanade.") || (it.contains(".") && !it.endsWith(".apk") && !it.startsWith("http")) }
            ?: "mihon.pb.ext.$fallbackIndex"
        val apkName = stringsByField[3]?.firstOrNull()?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: allStrings.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?: ""
        val rawName = stringsByField[1]?.firstOrNull()?.takeIf { it.isNotBlank() && it != pkgName }
            ?: allStrings.firstOrNull { it != pkgName && it != apkName && it.length > 2 }
            ?: pkgName
        val name = rawName.removePrefix("Tachiyomi: ").removePrefix("Mihon: ")
        val lang = stringsByField[4]?.firstOrNull()?.takeIf { it.length in 2..12 }
            ?: allStrings.firstOrNull { it.length in 2..8 && it != name && !it.contains(".") }
            ?: "all"
        val versionName = stringsByField[6]?.firstOrNull()?.takeIf { it.any { c -> c.isDigit() } }
            ?: allStrings.firstOrNull { it.count { c -> c == '.' } >= 1 && it.first().isDigit() && it != apkName }
            ?: "1.0.0"

        val code = varintsByField[5]?.firstOrNull()?.toInt()
            ?: varintsByField.values.flatten().firstOrNull { it > 1L }?.toInt()
            ?: 1
        val nsfw = (varintsByField[7]?.firstOrNull() ?: varintsByField[6]?.firstOrNull() ?: 0L) == 1L
        val hasReadme = (varintsByField[9]?.firstOrNull() ?: 0L) == 1L

        val sources = subMessagesByField.values.flatten().mapIndexedNotNull { idx, sub ->
            parseProtobufSourceObject(sub, idx)
        }

        return ProtoExtensionRaw(
            name = name,
            pkgName = pkgName,
            apkName = apkName,
            lang = lang,
            versionCode = code,
            versionName = versionName,
            nsfw = nsfw,
            hasReadme = hasReadme,
            sources = sources
        )
    }

    private fun looksLikeSourceProto(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return runCatching {
            val r = ProtobufWireReader(data)
            var fields = 0
            while (!r.isAtEnd() && fields < 10) {
                val tag = r.readTag() ?: break
                val f = tag ushr 3
                val wt = tag and 0x07
                if (f !in 1..8) return false
                when (wt) {
                    WIRE_VARINT -> r.readVarint()
                    WIRE_LEN -> r.readBytes()
                    else -> return false
                }
                fields++
            }
            fields >= 2 && r.isAtEnd()
        }.getOrDefault(false)
    }

    private fun parseProtobufSourceObject(bytes: ByteArray, index: Int): ExtensionSourceMeta? {
        return runCatching {
            val r = ProtobufWireReader(bytes)
            val strMap = mutableMapOf<Int, String>()
            var idVal: Long = index.toLong()
            while (!r.isAtEnd()) {
                val tag = r.readTag() ?: break
                val f = tag ushr 3
                val wt = tag and 0x07
                when (wt) {
                    WIRE_VARINT -> idVal = r.readVarint()
                    WIRE_LEN -> strMap[f] = String(r.readBytes(), Charsets.UTF_8)
                    else -> r.skipField(wt)
                }
            }
            val baseUrl = strMap.values.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
            val lang = strMap[2]?.takeIf { it.length in 2..12 }
                ?: strMap.values.firstOrNull { it != baseUrl && it.length in 2..8 }
                ?: "en"
            val name = strMap[1]?.takeIf { it != baseUrl && it != lang }
                ?: strMap.values.firstOrNull { it != baseUrl && it != lang }
                ?: "Source ${index + 1}"
            ExtensionSourceMeta(
                id = idVal,
                name = name,
                lang = lang,
                baseUrl = baseUrl
            )
        }.getOrNull()
    }

    private fun buildMihonDescription(
        name: String,
        sources: List<ExtensionSourceMeta>,
        nsfw: Boolean
    ): String {
        val srcSummary = if (sources.isEmpty()) {
            "0 declared sub-sources"
        } else {
            val preview = sources.take(3).joinToString(", ") {
                if (it.baseUrl.isNotBlank()) "${it.name} (${it.lang.uppercase()})" else it.name
            }
            val extra = if (sources.size > 3) " +${sources.size - 3} more" else ""
            "$preview$extra"
        }
        val nsfwTag = if (nsfw) " • 18+" else ""
        return "Mihon APK package for $name$nsfwTag. Sources: $srcSummary."
    }

    private fun resolveApkUrl(baseRepoDir: String, apkName: String): String {
        if (apkName.isBlank()) return ""
        if (apkName.startsWith("https://")) return apkName
        if (baseRepoDir.isBlank()) return ""
        return "$baseRepoDir/apk/${apkName.trimStart('/')}"
    }

    private fun resolveIconUrl(baseRepoDir: String, pkgName: String): String {
        if (pkgName.isBlank() || baseRepoDir.isBlank()) return ""
        return "$baseRepoDir/icon/$pkgName.png"
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private class ProtobufWireReader(private val data: ByteArray) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= data.size

        fun readTag(): Int? {
            if (isAtEnd()) return null
            return readVarint().toInt()
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf varint at offset $pos")
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) {
                throw IllegalArgumentException("Malformed protobuf length $len at offset $pos")
            }
            val slice = data.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> pos = (pos + 8).coerceAtMost(data.size)
                WIRE_LEN -> {
                    val len = readVarint().toInt().coerceAtLeast(0)
                    pos = (pos + len).coerceAtMost(data.size)
                }
                WIRE_FIXED32 -> pos = (pos + 4).coerceAtMost(data.size)
                else -> throw IllegalArgumentException("Unsupported protobuf wireType=$wireType")
            }
        }
    }
}
