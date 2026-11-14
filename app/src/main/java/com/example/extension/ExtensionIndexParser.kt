package com.example.extension

import com.example.data.local.entity.ExtensionPackageEntity
import com.example.domain.model.ExtensionCompatibilityStatus
import com.example.domain.model.ExtensionFormat
import com.example.domain.model.ExtensionSourceMeta
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONException
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
    val repoShortName: String? = null,
    val repoWebsite: String? = null,
    val repoFingerprint: String? = null,
    val packages: List<ExtensionPackageEntity>
)

/**
 * Parses Mihon/Keiyoushi `index.pb` (both `index_v2` Repo schema and legacy `index_v1` schema,
 * raw or gzipped), `index.json` / `index.min.json`, Koma declarative `koma_json_v1`
 * repositories or standalone manifests, and sibling `repo.json` metadata.
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
        rawBytes: ByteArray,
        contentType: String? = null
    ): ParsedExtensionIndex {
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException(
                "Repository index response from '$indexUrl' was empty (Content-Type: ${contentType ?: "unknown"})."
            )
        }

        val bytes = try {
            maybeGunzip(rawBytes)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to gunzip index from '$indexUrl' (Content-Type: ${contentType ?: "unknown"}): ${e.message}",
                e
            )
        }

        if (bytes.isEmpty()) {
            throw IllegalArgumentException("Decompressed repository index from '$indexUrl' was empty.")
        }

        val digest = sha256Hex(bytes)
        val baseRepoDir = indexUrl.substringBeforeLast("/", "")

        // Reject HTML / XML error pages cleanly instead of feeding them into JSON/Protobuf decoders
        val htmlOrXmlPreview = sniffHtmlOrXml(bytes)
        if (htmlOrXmlPreview != null) {
            throw IllegalArgumentException(
                "Expected repository index from '$indexUrl' but received HTML/XML " +
                    "(Content-Type: ${contentType ?: "unknown"}): $htmlOrXmlPreview"
            )
        }

        // Detect UTF-8 JSON (object wrapper, standalone koma_json_v1 manifest, or JSON array)
        val trimmedText = tryUtf8TextIfJson(bytes)
        if (trimmedText != null) {
            return parseJsonIndex(
                repoId = repoId,
                indexUrl = indexUrl,
                baseRepoDir = baseRepoDir,
                trimmedText = trimmedText,
                digest = digest,
                contentType = contentType
            )
        }

        // Binary Protobuf `index.pb` (supports both Keiyoushi index_v2 and legacy index_v1)
        return try {
            parseProtobufIndex(
                repoId = repoId,
                baseRepoDir = baseRepoDir,
                bytes = bytes,
                digest = digest
            )
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed parsing Protobuf index from '$indexUrl' (Content-Type: ${contentType ?: "unknown"}, ${bytes.size} bytes): ${e.message}",
                e
            )
        }
    }

    private fun parseJsonIndex(
        repoId: Long,
        indexUrl: String,
        baseRepoDir: String,
        trimmedText: String,
        digest: String,
        contentType: String?
    ): ParsedExtensionIndex {
        return try {
            if (trimmedText.startsWith("{")) {
                val root = JSONObject(trimmedText)
                val extArr = root.optJSONArray("extensions")
                if (extArr != null) {
                    val packages = parseJsonExtensionsArray(
                        repoId = repoId,
                        baseRepoDir = baseRepoDir,
                        arr = extArr,
                        digest = digest,
                        isKomaWrapper = true
                    )
                    ParsedExtensionIndex(
                        formatLabel = if (packages.any { it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id }) {
                            "koma_json_v1"
                        } else {
                            "index.json"
                        },
                        repoName = root.optString("name").takeIf { it.isNotBlank() },
                        repoWebsite = root.optString("website").takeIf { it.isNotBlank() },
                        repoFingerprint = root.optString("signingKeyFingerprint").takeIf { it.isNotBlank() },
                        packages = packages
                    )
                } else if (root.optJSONObject("adapter") != null || root.optJSONObject("komaAdapter") != null) {
                    // Standalone single-extension `koma_json_v1` manifest wrapped as a 1-item index
                    val singleArr = JSONArray().put(root)
                    val packages = parseJsonExtensionsArray(
                        repoId = repoId,
                        baseRepoDir = baseRepoDir,
                        arr = singleArr,
                        digest = digest,
                        isKomaWrapper = true
                    )
                    ParsedExtensionIndex(
                        formatLabel = "koma_json_v1",
                        repoName = root.optString("name").takeIf { it.isNotBlank() },
                        packages = packages
                    )
                } else {
                    throw IllegalArgumentException(
                        "JSON object from '$indexUrl' is missing 'extensions' array or 'adapter' manifest."
                    )
                }
            } else {
                val arr = JSONArray(trimmedText)
                val packages = parseJsonExtensionsArray(
                    repoId = repoId,
                    baseRepoDir = baseRepoDir,
                    arr = arr,
                    digest = digest,
                    isKomaWrapper = false
                )
                ParsedExtensionIndex(
                    formatLabel = "index.min.json",
                    packages = packages
                )
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException(
                "Malformed JSON repository index from '$indexUrl' (Content-Type: ${contentType ?: "unknown"}): ${e.message}",
                e
            )
        }
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

    private fun sniffHtmlOrXml(bytes: ByteArray): String? {
        val sampleLen = minOf(bytes.size, 256)
        var i = skipBomAndWhitespace(bytes, sampleLen)
        if (i >= sampleLen) return null
        val first = bytes[i].toInt().toChar()
        if (first != '<') return null
        val snippet = String(bytes, i, minOf(bytes.size - i, 120), Charsets.UTF_8)
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
        val lower = snippet.lowercase()
        return if (
            lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.startsWith("<head") ||
            lower.startsWith("<?xml") ||
            lower.startsWith("<error")
        ) {
            snippet
        } else {
            null
        }
    }

    private fun tryUtf8TextIfJson(bytes: ByteArray): String? {
        val i = skipBomAndWhitespace(bytes, bytes.size)
        if (i >= bytes.size) return null
        val first = bytes[i].toInt().toChar()
        if (first != '{' && first != '[') return null
        return String(bytes, i, bytes.size - i, Charsets.UTF_8).trim()
    }

    private fun skipBomAndWhitespace(bytes: ByteArray, limit: Int): Int {
        var i = 0
        if (limit >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < limit && bytes[i].toInt().toChar().isWhitespace()) i++
        return i
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
            val adapterObj = obj.optJSONObject("adapter") ?: obj.optJSONObject("komaAdapter")
            if (adapterObj != null && (isKomaWrapper || obj.optString("format").equals("koma_json_v1", ignoreCase = true))) {
                val pkgId = obj.optString("id").ifBlank { obj.optString("pkg", "koma.ext.$i") }
                val vCode = obj.optInt("versionCode", obj.optInt("code", 1))
                val vName = obj.optString("versionName", obj.optString("version", "1.0.0"))
                val baseUrl = adapterObj.optString("baseUrl", "").trim()
                val manifestCheck = KomaManifestValidator.validateDescriptor(adapterObj)
                val status = if (manifestCheck.isValid) {
                    ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON
                } else {
                    ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE
                }
                val sourceList = if (baseUrl.isNotBlank()) {
                    listOf(
                        ExtensionSourceMeta(
                            id = pkgId.hashCode().toLong(),
                            name = obj.optString("name", pkgId),
                            lang = obj.optString("lang", "en"),
                            baseUrl = baseUrl
                        )
                    )
                } else {
                    parseSourcesJsonArray(obj.optJSONArray("sources"))
                }
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
                        permissionsSummary = "HTTPS requests to ${baseUrl.ifBlank { "declared host" }} only",
                        libVersion = "koma-1",
                        sourcesJson = jsonSerializer.encodeToString(sourceList),
                        compatibilityStatus = status.name,
                        compatibilityReason = if (manifestCheck.isValid) {
                            "Compatible with Koma declarative JSON API (koma_json_v1). Ready to install & activate."
                        } else {
                            "Invalid koma_json_v1 manifest: ${manifestCheck.errorReason}"
                        }
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
                        permissionsSummary = if (nsfw) {
                            "Native APK • 18+ NSFW • $sourceCount source(s)"
                        } else {
                            "Native APK • $sourceCount source(s)"
                        },
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

    /**
     * Decodes binary Protobuf `index.pb`:
     * - **Keiyoushi / Mihon `index_v2`**: Root `Repo` message with:
     *   - field 1 (LEN): repo name (string, e.g. "Keiyoushi")
     *   - field 2 (LEN): repo shortName (string, e.g. "KEI")
     *   - field 3 (LEN): signingKeyFingerprint (64-hex SHA-256 string)
     *   - field 4 (LEN): links submessage (1: website, 2: support/discord)
     *   - field 101 (LEN): extensions wrapper (`1: repeated ExtensionV2`)
     * - **Legacy `index_v1`**: Root message containing `1: repeated ExtensionV1`
     *   (where each field 1 payload is a nested Protobuf Extension message, not a UTF-8 repo name).
     */
    private fun parseProtobufIndex(
        repoId: Long,
        baseRepoDir: String,
        bytes: ByteArray,
        digest: String
    ): ParsedExtensionIndex {
        val reader = ProtobufWireReader(bytes)
        val rootLenFields = linkedMapOf<Int, MutableList<ByteArray>>()

        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber <= 0) {
                throw IllegalArgumentException("Invalid protobuf fieldNumber=$fieldNumber at offset ${reader.position}")
            }
            if (wireType == WIRE_LEN) {
                rootLenFields.getOrPut(fieldNumber) { mutableListOf() }.add(reader.readBytes())
            } else {
                reader.skipField(fieldNumber, wireType)
            }
        }

        // Branch 1: Keiyoushi / Mihon `index_v2` (wrapper submessage at field 101, or v2 repo header fields)
        val v2WrapperPayloads = rootLenFields[101].orEmpty()
        if (v2WrapperPayloads.isNotEmpty() || looksLikeV2RepoHeader(rootLenFields)) {
            val repoName = rootLenFields[1]?.firstOrNull()?.let { String(it, Charsets.UTF_8).trim() }
                ?.takeIf { it.isNotBlank() }
            val repoShortName = rootLenFields[2]?.firstOrNull()?.let { String(it, Charsets.UTF_8).trim() }
                ?.takeIf { it.isNotBlank() }
            val repoFingerprint = rootLenFields[3]?.firstOrNull()?.let { String(it, Charsets.UTF_8).trim() }
                ?.takeIf { it.isNotBlank() }
            val repoWebsite = rootLenFields[4]?.firstOrNull()?.let { parseProtoRepoWebsite(it) }

            val extPayloadsV2 = mutableListOf<ByteArray>()
            v2WrapperPayloads.forEach { wrapper ->
                val wr = ProtobufWireReader(wrapper)
                while (!wr.isAtEnd()) {
                    val tag = wr.readTag() ?: break
                    val fn = tag ushr 3
                    val wt = tag and 0x07
                    if (fn <= 0) {
                        throw IllegalArgumentException("Invalid extension wrapper fieldNumber=$fn at offset ${wr.position}")
                    }
                    if (wt == WIRE_LEN && (fn == 1 || fn == 2)) {
                        extPayloadsV2.add(wr.readBytes())
                    } else {
                        wr.skipField(fn, wt)
                    }
                }
            }

            if (extPayloadsV2.isEmpty()) {
                throw IllegalArgumentException(
                    "Protobuf index (index_v2${repoName?.let { " for $it" }.orEmpty()}) contained 0 extension records."
                )
            }

            val packages = extPayloadsV2.mapIndexedNotNull { idx, payload ->
                parseProtobufV2ExtensionObject(payload, idx)?.let { ext ->
                    buildProtobufExtensionEntity(
                        repoId = repoId,
                        baseRepoDir = baseRepoDir,
                        ext = ext,
                        digest = digest,
                        isV2 = true
                    )
                }
            }

            return ParsedExtensionIndex(
                formatLabel = "index.pb (v2)",
                repoName = repoName,
                repoShortName = repoShortName,
                repoWebsite = repoWebsite,
                repoFingerprint = repoFingerprint,
                packages = packages
            )
        }

        // Branch 2: Legacy `index_v1` (repeated Extension submessages at root field 1 or field 2)
        val v1Payloads = (rootLenFields[1].orEmpty() + rootLenFields[2].orEmpty())
            .filter { looksLikeProtobufMessage(it) }

        if (v1Payloads.isEmpty()) {
            throw IllegalArgumentException("Protobuf index contained no extension records.")
        }

        val packages = v1Payloads.mapIndexedNotNull { idx, payload ->
            parseProtobufV1ExtensionObject(payload, idx)?.let { ext ->
                buildProtobufExtensionEntity(
                    repoId = repoId,
                    baseRepoDir = baseRepoDir,
                    ext = ext,
                    digest = digest,
                    isV2 = false
                )
            }
        }

        return ParsedExtensionIndex(
            formatLabel = "index.pb",
            packages = packages
        )
    }

    private fun looksLikeV2RepoHeader(rootLenFields: Map<Int, List<ByteArray>>): Boolean {
        val f1 = rootLenFields[1]?.firstOrNull() ?: return false
        val f3 = rootLenFields[3]?.firstOrNull()
        // In v2, field 1 is a short UTF-8 repo name (not a protobuf message), and field 3 is a 64-char hex fingerprint
        if (f3 != null && f3.size in 32..128) {
            val fpStr = String(f3, Charsets.UTF_8)
            if (fpStr.all { it.isLetterOrDigit() }) return true
        }
        return !looksLikeProtobufMessage(f1) && isMostlyPrintableUtf8(f1)
    }

    private fun isMostlyPrintableUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val str = String(bytes, Charsets.UTF_8)
        return str.all { !it.isISOControl() }
    }

    private fun parseProtoRepoWebsite(bytes: ByteArray): String? {
        return runCatching {
            val r = ProtobufWireReader(bytes)
            while (!r.isAtEnd()) {
                val tag = r.readTag() ?: break
                val fn = tag ushr 3
                val wt = tag and 0x07
                if (wt == WIRE_LEN && fn == 1) {
                    return@runCatching String(r.readBytes(), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
                } else {
                    r.skipField(fn, wt)
                }
            }
            null
        }.getOrNull()
    }

    private data class ProtoExtensionRaw(
        val name: String,
        val pkgName: String,
        val apkName: String,
        val apkUrl: String,
        val iconUrl: String,
        val lang: String,
        val versionCode: Int,
        val versionName: String,
        val explicitLibVersion: String?,
        val nsfw: Boolean,
        val hasReadme: Boolean,
        val sources: List<ExtensionSourceMeta>
    )

    private fun buildProtobufExtensionEntity(
        repoId: Long,
        baseRepoDir: String,
        ext: ProtoExtensionRaw,
        digest: String,
        isV2: Boolean
    ): ExtensionPackageEntity {
        val resolvedApkUrl = ext.apkUrl.ifBlank { resolveApkUrl(baseRepoDir, ext.apkName) }
        val resolvedIconUrl = ext.iconUrl.ifBlank { resolveIconUrl(baseRepoDir, ext.pkgName) }
        val compat = ExtensionCompatibilityResolver.evaluateMetadata(
            format = ExtensionFormat.MIHON_PROTOBUF_INDEX,
            versionName = ext.versionName,
            isRepoTrusted = true,
            signatureMatch = true,
            explicitLibVersion = ext.explicitLibVersion
        )
        return ExtensionPackageEntity(
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
                "Native APK (PB${if (isV2) " v2" else ""}) • 18+ NSFW • ${ext.sources.size} source(s)"
            } else {
                "Native APK (PB${if (isV2) " v2" else ""}) • ${ext.sources.size} source(s)"
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
    }

    /**
     * Parses Keiyoushi/Mihon `index_v2` Extension record:
     * 1: name (string)
     * 2: pkgName (string)
     * 3: artifacts submessage -> 1: apkUrl, 2: iconUrl, 501: jarUrl
     * 4: libVersion (string, e.g. "1.6")
     * 5: versionCode (varint)
     * 6: versionName (string, e.g. "1.6.33")
     * 7: flags bitmask (varint: bit 0 = hasReadme, bit 1 = nsfw)
     * 8: repeated SourceV2 -> 1: id (varint), 2: name (string), 3: lang (string), 4: baseUrl (string), 5: mirrors (repeated string)
     */
    private fun parseProtobufV2ExtensionObject(bytes: ByteArray, fallbackIndex: Int): ProtoExtensionRaw? {
        val reader = ProtobufWireReader(bytes)
        val stringsByField = linkedMapOf<Int, MutableList<String>>()
        val bytesByField = linkedMapOf<Int, MutableList<ByteArray>>()
        val varintsByField = linkedMapOf<Int, MutableList<Long>>()

        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNum <= 0) {
                throw IllegalArgumentException("Invalid v2 extension fieldNumber=$fieldNum at offset ${reader.position}")
            }
            when (wireType) {
                WIRE_VARINT -> varintsByField.getOrPut(fieldNum) { mutableListOf() }.add(reader.readVarint())
                WIRE_LEN -> {
                    val data = reader.readBytes()
                    bytesByField.getOrPut(fieldNum) { mutableListOf() }.add(data)
                    if (fieldNum in setOf(1, 2, 4, 6)) {
                        stringsByField.getOrPut(fieldNum) { mutableListOf() }.add(String(data, Charsets.UTF_8))
                    }
                }
                else -> reader.skipField(fieldNum, wireType)
            }
        }

        val pkgName = stringsByField[2]?.firstOrNull()?.trim().orEmpty()
            .ifBlank { "mihon.pb.ext.$fallbackIndex" }
        val rawName = stringsByField[1]?.firstOrNull()?.trim().orEmpty()
            .ifBlank { pkgName }
        val name = rawName.removePrefix("Tachiyomi: ").removePrefix("Mihon: ")

        // Parse field 3 artifacts (apkUrl, iconUrl)
        var apkUrl = ""
        var iconUrl = ""
        var apkName = ""
        bytesByField[3]?.firstOrNull()?.let { f3Bytes ->
            if (looksLikeProtobufMessage(f3Bytes)) {
                val artReader = ProtobufWireReader(f3Bytes)
                while (!artReader.isAtEnd()) {
                    val tag = artReader.readTag() ?: break
                    val fn = tag ushr 3
                    val wt = tag and 0x07
                    if (wt == WIRE_LEN) {
                        val s = String(artReader.readBytes(), Charsets.UTF_8).trim()
                        when (fn) {
                            1 -> apkUrl = s
                            2 -> iconUrl = s
                        }
                    } else {
                        artReader.skipField(fn, wt)
                    }
                }
            } else {
                val raw = String(f3Bytes, Charsets.UTF_8).trim()
                if (raw.startsWith("https://")) apkUrl = raw else apkName = raw
            }
        }
        if (apkName.isBlank() && apkUrl.isNotBlank()) {
            apkName = apkUrl.substringAfterLast("/", "")
        }

        val explicitLibVersion = stringsByField[4]?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val versionCode = varintsByField[5]?.firstOrNull()?.toInt() ?: 1
        val versionName = stringsByField[6]?.firstOrNull()?.trim().orEmpty()
            .ifBlank { explicitLibVersion ?: "1.0.0" }
        val flags = varintsByField[7]?.firstOrNull() ?: 0L
        val hasReadme = (flags and 0x01L) != 0L
        val nsfw = (flags and 0x02L) != 0L

        val sources = bytesByField[8].orEmpty().mapIndexedNotNull { idx, subBytes ->
            parseProtobufV2SourceObject(subBytes, idx)
        }

        val distinctLangs = sources.map { it.lang.trim() }.filter { it.isNotBlank() }.distinct()
        val lang = when {
            distinctLangs.size == 1 -> distinctLangs.first()
            distinctLangs.size > 1 -> "all"
            else -> inferLangFromPackageName(pkgName) ?: "all"
        }

        return ProtoExtensionRaw(
            name = name,
            pkgName = pkgName,
            apkName = apkName,
            apkUrl = apkUrl,
            iconUrl = iconUrl,
            lang = lang,
            versionCode = versionCode,
            versionName = versionName,
            explicitLibVersion = explicitLibVersion,
            nsfw = nsfw,
            hasReadme = hasReadme,
            sources = sources
        )
    }

    private fun parseProtobufV2SourceObject(bytes: ByteArray, index: Int): ExtensionSourceMeta? {
        return runCatching {
            val r = ProtobufWireReader(bytes)
            var idVal = index.toLong()
            var srcName = ""
            var lang = ""
            var baseUrl = ""
            while (!r.isAtEnd()) {
                val tag = r.readTag() ?: break
                val fn = tag ushr 3
                val wt = tag and 0x07
                when {
                    wt == WIRE_VARINT && fn == 1 -> idVal = r.readVarint()
                    wt == WIRE_LEN && fn == 2 -> srcName = String(r.readBytes(), Charsets.UTF_8).trim()
                    wt == WIRE_LEN && fn == 3 -> lang = String(r.readBytes(), Charsets.UTF_8).trim()
                    wt == WIRE_LEN && fn == 4 -> baseUrl = String(r.readBytes(), Charsets.UTF_8).trim()
                    wt == WIRE_LEN && fn == 5 -> {
                        val mirror = String(r.readBytes(), Charsets.UTF_8).trim()
                        if (baseUrl.isBlank() && mirror.startsWith("http")) baseUrl = mirror
                    }
                    else -> r.skipField(fn, wt)
                }
            }
            ExtensionSourceMeta(
                id = idVal,
                name = srcName.ifBlank { "Source ${index + 1}" },
                lang = lang.ifBlank { "en" },
                baseUrl = baseUrl
            )
        }.getOrNull()
    }

    private fun inferLangFromPackageName(pkgName: String): String? {
        // e.g. eu.kanade.tachiyomi.extension.zh.baozimanhua -> "zh"
        val parts = pkgName.split('.')
        val extIdx = parts.indexOf("extension")
        return if (extIdx >= 0 && extIdx + 1 < parts.size) {
            parts[extIdx + 1].takeIf { it.length in 2..10 }
        } else {
            null
        }
    }

    private fun parseProtobufV1ExtensionObject(bytes: ByteArray, fallbackIndex: Int): ProtoExtensionRaw? {
        val reader = ProtobufWireReader(bytes)
        val stringsByField = linkedMapOf<Int, MutableList<String>>()
        val subMessagesByField = linkedMapOf<Int, MutableList<ByteArray>>()
        val varintsByField = linkedMapOf<Int, MutableList<Long>>()

        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNum <= 0) {
                throw IllegalArgumentException("Invalid v1 extension fieldNumber=$fieldNum at offset ${reader.position}")
            }
            when (wireType) {
                WIRE_VARINT -> {
                    varintsByField.getOrPut(fieldNum) { mutableListOf() }.add(reader.readVarint())
                }
                WIRE_LEN -> {
                    val data = reader.readBytes()
                    if (fieldNum >= 7 && looksLikeSourceProto(data)) {
                        subMessagesByField.getOrPut(fieldNum) { mutableListOf() }.add(data)
                    } else {
                        val str = String(data, Charsets.UTF_8)
                        stringsByField.getOrPut(fieldNum) { mutableListOf() }.add(str)
                    }
                }
                else -> reader.skipField(fieldNum, wireType)
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
            ?: inferLangFromPackageName(pkgName)
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
            parseProtobufV1SourceObject(sub, idx)
        }

        return ProtoExtensionRaw(
            name = name,
            pkgName = pkgName,
            apkName = apkName,
            apkUrl = "",
            iconUrl = "",
            lang = lang,
            versionCode = code,
            versionName = versionName,
            explicitLibVersion = null,
            nsfw = nsfw,
            hasReadme = hasReadme,
            sources = sources
        )
    }

    private fun looksLikeProtobufMessage(data: ByteArray): Boolean {
        if (data.size < 2) return false
        return runCatching {
            val r = ProtobufWireReader(data)
            var fields = 0
            while (!r.isAtEnd()) {
                val tag = r.readTag() ?: break
                val fn = tag ushr 3
                val wt = tag and 0x07
                if (fn <= 0 || fn > 10_000) return false
                when (wt) {
                    WIRE_VARINT, WIRE_FIXED64, WIRE_LEN, WIRE_START_GROUP, WIRE_FIXED32 -> r.skipField(fn, wt)
                    else -> return false
                }
                fields++
            }
            fields >= 2 && r.isAtEnd()
        }.getOrDefault(false)
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

    private fun parseProtobufV1SourceObject(bytes: ByteArray, index: Int): ExtensionSourceMeta? {
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
                    else -> r.skipField(f, wt)
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
        return "Native Mihon APK for $name$nsfwTag. Sources: $srcSummary."
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

    internal const val WIRE_VARINT = 0
    internal const val WIRE_FIXED64 = 1
    internal const val WIRE_LEN = 2
    internal const val WIRE_START_GROUP = 3
    internal const val WIRE_END_GROUP = 4
    internal const val WIRE_FIXED32 = 5
    private const val MAX_GROUP_DEPTH = 32

    /**
     * Strict, bounds-checked Protocol Buffers wire reader supporting all standard wire types:
     * 0 (Varint), 1 (64-bit), 2 (Length-delimited), 3 (Start group), 4 (End group), and 5 (32-bit).
     */
    internal class ProtobufWireReader(private val data: ByteArray) {
        private var pos = 0

        val position: Int get() = pos

        fun isAtEnd(): Boolean = pos >= data.size

        fun readTag(): Int? {
            if (isAtEnd()) return null
            val raw = readVarint()
            if (raw <= 0L || raw > 0xFFFFFFFFL) {
                throw IllegalArgumentException("Malformed protobuf tag 0x${raw.toString(16)} at offset $pos")
            }
            return raw.toInt()
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed or truncated protobuf varint at offset $pos")
        }

        fun readBytes(): ByteArray {
            val lenLong = readVarint()
            if (lenLong < 0 || lenLong > Int.MAX_VALUE) {
                throw IllegalArgumentException("Malformed protobuf length=$lenLong at offset $pos")
            }
            val len = lenLong.toInt()
            if (pos + len > data.size) {
                throw IllegalArgumentException(
                    "Truncated protobuf length-delimited field: need $len bytes at offset $pos, only ${data.size - pos} remaining"
                )
            }
            val slice = data.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun skipField(fieldNumber: Int, wireType: Int, groupDepth: Int = 0) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> {
                    if (pos + 8 > data.size) {
                        throw IllegalArgumentException("Truncated protobuf fixed64 field=$fieldNumber at offset $pos")
                    }
                    pos += 8
                }
                WIRE_LEN -> {
                    val lenLong = readVarint()
                    if (lenLong < 0 || lenLong > Int.MAX_VALUE || pos + lenLong.toInt() > data.size) {
                        throw IllegalArgumentException(
                            "Truncated protobuf LEN field=$fieldNumber len=$lenLong at offset $pos (size=${data.size})"
                        )
                    }
                    pos += lenLong.toInt()
                }
                WIRE_START_GROUP -> skipGroup(fieldNumber, groupDepth + 1)
                WIRE_END_GROUP -> throw IllegalArgumentException(
                    "Unexpected unmatched protobuf END_GROUP (wireType=4) for field=$fieldNumber at offset $pos"
                )
                WIRE_FIXED32 -> {
                    if (pos + 4 > data.size) {
                        throw IllegalArgumentException("Truncated protobuf fixed32 field=$fieldNumber at offset $pos")
                    }
                    pos += 4
                }
                else -> throw IllegalArgumentException(
                    "Unsupported protobuf wireType=$wireType for field=$fieldNumber at offset $pos"
                )
            }
        }

        private fun skipGroup(expectedFieldNumber: Int, depth: Int) {
            if (depth > MAX_GROUP_DEPTH) {
                throw IllegalArgumentException(
                    "Protobuf group nesting exceeded max depth ($MAX_GROUP_DEPTH) at offset $pos"
                )
            }
            while (!isAtEnd()) {
                val tag = readTag() ?: break
                val fn = tag ushr 3
                val wt = tag and 0x07
                if (wt == WIRE_END_GROUP) {
                    if (fn != expectedFieldNumber) {
                        throw IllegalArgumentException(
                            "Mismatched protobuf END_GROUP field=$fn (expected=$expectedFieldNumber) at offset $pos"
                        )
                    }
                    return
                }
                skipField(fn, wt, depth)
            }
            throw IllegalArgumentException(
                "Unterminated protobuf START_GROUP (wireType=3) for field=$expectedFieldNumber at offset $pos"
            )
        }
    }
}
