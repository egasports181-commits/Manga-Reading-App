package com.example.network

import android.util.Log
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassRequiredException
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

enum class ResponseFailureKind {
    CLOUDFLARE_CHALLENGE,
    HTML_PAGE,
    HTTP_ERROR,
    EMPTY_BODY,
    INVALID_JSON,
    UNSUPPORTED_FORMAT
}

class UnexpectedResponseFormatException(
    val url: String,
    val statusCode: Int,
    val contentType: String?,
    val kind: ResponseFailureKind,
    val bodyPreview: String,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Centralized HTTP response validation and diagnostics for repository index fetches and JSON APIs.
 *
 * Ensures every HTTP response is logged (status, final redirected URL, Content-Type, safe body preview)
 * and checked for Cloudflare challenges, HTML/XML pages, HTTP 4xx/5xx errors, and empty payloads
 * BEFORE invoking `JSONObject(...)`, `JSONArray(...)`, or Protobuf/kotlinx.serialization decoders.
 */
object HttpResponseValidator {

    private val HTML_TAG_PREFIXES = listOf(
        "<!doctype",
        "<html",
        "<head",
        "<body",
        "<title",
        "<meta",
        "<script",
        "<style",
        "<div",
        "<svg",
        "<?xml",
        "<error"
    )

    private val CLOUDFLARE_MARKERS = listOf(
        "just a moment...",
        "_cf_chl_",
        "challenge-platform",
        "cf-browser-verification",
        "attention required! | cloudflare",
        "enable javascript and cookies to continue"
    )

    private val TITLE_REGEX = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val TAG_STRIP_REGEX = Regex("<[^>]+>")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val AUTH_REDACT_REGEX = Regex("(?i)(bearer\\s+|token=|key=|password=|secret=)[^\\s&\"']+")

    fun inspectAndReadJsonText(response: Response, sourceLabel: String = "Server"): String {
        return response.use { resp ->
            val statusCode = resp.code
            val finalUrl = resp.request.url.toString()
            val safeUrl = sanitizeUrlForLog(finalUrl)
            val contentType = resp.header("Content-Type")?.trim()
            val serverHeader = resp.header("Server").orEmpty()
            val cfMitigated = resp.header("cf-mitigated").orEmpty()
            val bodyText = resp.body?.string().orEmpty()
            val preview = safeBodyPreview(bodyText)

            if (isCloudflareResponse(statusCode, serverHeader, cfMitigated, contentType, bodyText)) {
                logWarn(
                    "HTTP JSON [$sourceLabel] Cloudflare challenge status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
                )
                throw CloudflareBypassRequiredException(
                    url = finalUrl,
                    statusCode = statusCode,
                    message = "Cloudflare anti-bot challenge (HTTP $statusCode) from $safeUrl"
                )
            }

            if (!resp.isSuccessful) {
                val htmlTitle = if (isHtmlOrXmlPayload(bodyText, contentType)) extractHtmlTitle(bodyText) else null
                val detail = htmlTitle?.takeIf { it.isNotBlank() } ?: preview
                logWarn(
                    "HTTP JSON [$sourceLabel] error status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
                )
                throw UnexpectedResponseFormatException(
                    url = finalUrl,
                    statusCode = statusCode,
                    contentType = contentType,
                    kind = ResponseFailureKind.HTTP_ERROR,
                    bodyPreview = preview,
                    message = "$sourceLabel returned HTTP $statusCode from $safeUrl" +
                        " (Content-Type: ${contentType ?: "unknown"})" +
                        if (detail.isNotBlank()) ": $detail" else ""
                )
            }

            val trimmed = stripLeadingBomAndWhitespace(bodyText)
            if (trimmed.isEmpty()) {
                logWarn(
                    "HTTP JSON [$sourceLabel] empty body status=$statusCode finalUrl=$safeUrl contentType=$contentType"
                )
                throw UnexpectedResponseFormatException(
                    url = finalUrl,
                    statusCode = statusCode,
                    contentType = contentType,
                    kind = ResponseFailureKind.EMPTY_BODY,
                    bodyPreview = "",
                    message = "$sourceLabel returned an empty response from $safeUrl (HTTP $statusCode, Content-Type: ${contentType ?: "unknown"})."
                )
            }

            if (isHtmlOrXmlPayload(trimmed, contentType)) {
                val htmlTitle = extractHtmlTitle(trimmed)
                val hint = when {
                    trimmed.contains("login", ignoreCase = true) ||
                        trimmed.contains("sign in", ignoreCase = true) ||
                        safeUrl.contains("login", ignoreCase = true) ->
                        "Server redirected to a login/auth page"
                    htmlTitle != null -> "Page title: \"$htmlTitle\""
                    else -> "Preview: \"$preview\""
                }
                logWarn(
                    "HTTP JSON [$sourceLabel] received HTML/XML instead of JSON status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
                )
                throw UnexpectedResponseFormatException(
                    url = finalUrl,
                    statusCode = statusCode,
                    contentType = contentType,
                    kind = ResponseFailureKind.HTML_PAGE,
                    bodyPreview = preview,
                    message = "$sourceLabel expected JSON from $safeUrl but received HTML/XML " +
                        "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}). $hint"
                )
            }

            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                logWarn(
                    "HTTP JSON [$sourceLabel] non-JSON payload status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
                )
                throw UnexpectedResponseFormatException(
                    url = finalUrl,
                    statusCode = statusCode,
                    contentType = contentType,
                    kind = ResponseFailureKind.UNSUPPORTED_FORMAT,
                    bodyPreview = preview,
                    message = "$sourceLabel expected JSON from $safeUrl but received unsupported content " +
                        "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}): $preview"
                )
            }

            logDebug(
                "HTTP JSON [$sourceLabel] ok status=$statusCode finalUrl=$safeUrl contentType=$contentType bytes=${bodyText.length}"
            )
            trimmed
        }
    }

    fun parseJsonObjectStrict(response: Response, sourceLabel: String = "Server"): JSONObject {
        val statusCode = response.code
        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type")?.trim()
        val jsonText = inspectAndReadJsonText(response, sourceLabel)
        return try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            val preview = safeBodyPreview(jsonText)
            logWarn(
                "HTTP JSON [$sourceLabel] JSONObject parse failed status=$statusCode finalUrl=${sanitizeUrlForLog(finalUrl)} contentType=$contentType err=${e.message}"
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.INVALID_JSON,
                bodyPreview = preview,
                message = "$sourceLabel returned malformed JSON from ${sanitizeUrlForLog(finalUrl)} " +
                    "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}): ${e.message}",
                cause = e
            )
        }
    }

    fun parseJsonRootStrict(response: Response, sourceLabel: String = "Server"): Any {
        val statusCode = response.code
        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type")?.trim()
        val jsonText = inspectAndReadJsonText(response, sourceLabel)
        return try {
            if (jsonText.startsWith("[")) JSONArray(jsonText) else JSONObject(jsonText)
        } catch (e: JSONException) {
            val preview = safeBodyPreview(jsonText)
            logWarn(
                "HTTP JSON [$sourceLabel] JSON root parse failed status=$statusCode finalUrl=${sanitizeUrlForLog(finalUrl)} contentType=$contentType err=${e.message}"
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.INVALID_JSON,
                bodyPreview = preview,
                message = "$sourceLabel returned malformed JSON from ${sanitizeUrlForLog(finalUrl)} " +
                    "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}): ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Validates an extension repository HTTP response (`.pb` binary, gzipped `.pb`, or `.json` index).
     * Logs status, final URL after redirects, Content-Type, and a safe preview, and rejects
     * Cloudflare challenges, HTTP 4xx/5xx error pages, and HTML pages cleanly.
     */
    fun validateHttpIndexResponse(
        response: Response,
        repoLabel: String
    ): Pair<ByteArray, String?> {
        val statusCode = response.code
        val finalUrl = response.request.url.toString()
        val safeUrl = sanitizeUrlForLog(finalUrl)
        val contentType = response.header("Content-Type")?.trim()
        val serverHeader = response.header("Server").orEmpty()
        val cfMitigated = response.header("cf-mitigated").orEmpty()
        val rawBytes = response.body?.bytes() ?: ByteArray(0)

        val preview = buildSafeBytesPreview(rawBytes, contentType)
        val textSample = decodeHeadUtf8(rawBytes)

        if (isCloudflareResponse(statusCode, serverHeader, cfMitigated, contentType, textSample)) {
            logWarn(
                "Repo fetch [$repoLabel] Cloudflare challenge status=$statusCode finalUrl=$safeUrl contentType=$contentType bytes=${rawBytes.size} preview=\"$preview\""
            )
            throw CloudflareBypassRequiredException(
                url = finalUrl,
                statusCode = statusCode,
                message = "Cloudflare anti-bot challenge (HTTP $statusCode) on repository '$repoLabel' ($safeUrl)"
            )
        }

        if (!response.isSuccessful) {
            val htmlTitle = if (isHtmlOrXmlPayload(textSample, contentType)) extractHtmlTitle(textSample) else null
            val detail = htmlTitle?.takeIf { it.isNotBlank() } ?: preview
            logWarn(
                "Repo fetch [$repoLabel] HTTP error status=$statusCode finalUrl=$safeUrl contentType=$contentType bytes=${rawBytes.size} preview=\"$preview\""
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.HTTP_ERROR,
                bodyPreview = preview,
                message = "Repository '$repoLabel' returned HTTP $statusCode from $safeUrl" +
                    " (Content-Type: ${contentType ?: "unknown"})" +
                    if (detail.isNotBlank()) ": $detail" else ""
            )
        }

        if (rawBytes.isEmpty()) {
            logWarn(
                "Repo fetch [$repoLabel] empty response status=$statusCode finalUrl=$safeUrl contentType=$contentType"
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.EMPTY_BODY,
                bodyPreview = "",
                message = "Repository '$repoLabel' returned an empty index response from $safeUrl (HTTP $statusCode)."
            )
        }

        if (isHtmlOrXmlPayload(textSample, contentType)) {
            val htmlTitle = extractHtmlTitle(textSample)
            val detail = htmlTitle ?: preview
            logWarn(
                "Repo fetch [$repoLabel] received HTML/XML status=$statusCode finalUrl=$safeUrl contentType=$contentType bytes=${rawBytes.size} preview=\"$preview\""
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.HTML_PAGE,
                bodyPreview = preview,
                message = "Expected repository index (.pb or .json) from $safeUrl but received HTML " +
                    "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}): $detail"
            )
        }

        logDebug(
            "Repo fetch [$repoLabel] ok status=$statusCode finalUrl=$safeUrl contentType=$contentType bytes=${rawBytes.size} preview=\"$preview\""
        )
        return rawBytes to contentType
    }

    /**
     * Validates an OkHttp [Response] before `parseAs<T>()` JSON decoding inside Mihon/Keiyoushi extensions.
     * Allows `application/json`, `text/plain`, and custom API media types while intercepting HTTP errors,
     * Cloudflare challenges, and HTML/XML payloads cleanly before JSON decoding fails.
     */
    fun validateResponseBeforeParseAs(response: Response, targetType: String): String {
        return inspectAndReadJsonText(response, "Extension ($targetType)")
    }

    /**
     * Validates an OkHttp [Response] via `peekBody` before streaming `decodeFromBufferedSource`
     * so valid large JSON responses still stream without full String allocation while HTML/error
     * pages are logged and rejected cleanly before kotlinx.serialization fails.
     */
    fun assertValidJsonResponseForStreaming(response: Response, targetType: String): String {
        val statusCode = response.code
        val finalUrl = response.request.url.toString()
        val safeUrl = sanitizeUrlForLog(finalUrl)
        val contentType = response.header("Content-Type")?.trim()
        val serverHeader = response.header("Server").orEmpty()
        val cfMitigated = response.header("cf-mitigated").orEmpty()
        val peekText = runCatching { response.peekBody(4096).string() }.getOrDefault("")
        val preview = safeBodyPreview(peekText)

        if (isCloudflareResponse(statusCode, serverHeader, cfMitigated, contentType, peekText)) {
            logWarn(
                "Extension parseAs<$targetType> Cloudflare challenge status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
            )
            throw CloudflareBypassRequiredException(
                url = finalUrl,
                statusCode = statusCode,
                message = "Cloudflare anti-bot challenge (HTTP $statusCode) on $safeUrl"
            )
        }

        if (!response.isSuccessful) {
            val htmlTitle = if (isHtmlOrXmlPayload(peekText, contentType)) extractHtmlTitle(peekText) else null
            val detail = htmlTitle?.takeIf { it.isNotBlank() } ?: preview
            logWarn(
                "Extension parseAs<$targetType> HTTP error status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.HTTP_ERROR,
                bodyPreview = preview,
                message = "Extension ($targetType) returned HTTP $statusCode from $safeUrl" +
                    " (Content-Type: ${contentType ?: "unknown"})" +
                    if (detail.isNotBlank()) ": $detail" else ""
            )
        }

        val trimmed = stripLeadingBomAndWhitespace(peekText)
        if (trimmed.isEmpty()) {
            logWarn(
                "Extension parseAs<$targetType> empty body status=$statusCode finalUrl=$safeUrl contentType=$contentType"
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.EMPTY_BODY,
                bodyPreview = "",
                message = "Extension ($targetType) returned an empty response from $safeUrl (HTTP $statusCode)."
            )
        }

        if (isHtmlOrXmlPayload(trimmed, contentType)) {
            val htmlTitle = extractHtmlTitle(trimmed)
            val detail = htmlTitle ?: preview
            logWarn(
                "Extension parseAs<$targetType> HTML/XML body status=$statusCode finalUrl=$safeUrl contentType=$contentType preview=\"$preview\""
            )
            throw UnexpectedResponseFormatException(
                url = finalUrl,
                statusCode = statusCode,
                contentType = contentType,
                kind = ResponseFailureKind.HTML_PAGE,
                bodyPreview = preview,
                message = "Expected JSON for $targetType from $safeUrl but received HTML/XML " +
                    "(HTTP $statusCode, Content-Type: ${contentType ?: "unknown"}): $detail"
            )
        }

        logDebug(
            "Extension parseAs<$targetType> ok status=$statusCode finalUrl=$safeUrl contentType=$contentType"
        )
        return preview
    }

    fun isCloudflareResponse(
        statusCode: Int,
        serverHeader: String,
        cfMitigated: String,
        contentType: String?,
        bodySample: String
    ): Boolean {
        val isCfServer = serverHeader.contains("cloudflare", ignoreCase = true)
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true
        val lower = bodySample.lowercase()
        val hasCfMarker = CLOUDFLARE_MARKERS.any { lower.contains(it) }
        if (hasCfMarker && (isCfServer || isHtmlOrXmlPayload(bodySample, contentType))) {
            return true
        }
        return (statusCode == 403 || statusCode == 503) && isCfServer && isHtmlOrXmlPayload(bodySample, contentType)
    }

    fun isHtmlOrXmlPayload(text: String, contentType: String? = null): Boolean {
        val normalized = stripLeadingHtmlCommentsAndWhitespace(text)
        if (normalized.isEmpty()) {
            val ctLower = contentType.orEmpty().lowercase()
            return ctLower.contains("text/html") || ctLower.contains("application/xhtml+xml")
        }
        val lower = normalized.lowercase()
        if (HTML_TAG_PREFIXES.any { lower.startsWith(it) }) {
            return true
        }
        val ctLower = contentType.orEmpty().lowercase()
        if ((ctLower.contains("text/html") || ctLower.contains("application/xhtml+xml") || ctLower.contains("text/xml")) &&
            lower.startsWith("<")
        ) {
            return true
        }
        return false
    }

    fun extractHtmlTitle(html: String): String? {
        val match = TITLE_REGEX.find(html.take(8192)) ?: return null
        val cleaned = match.groupValues[1]
            .replace(TAG_STRIP_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }?.take(100)
    }

    fun sanitizeUrlForLog(rawUrl: String): String {
        return AUTH_REDACT_REGEX.replace(rawUrl) { m -> "${m.groupValues[1]}***" }
            .replace(Regex("https?://[^/@\\s]+@"), "https://***@")
    }

    fun safeBodyPreview(raw: String, maxLen: Int = 160): String {
        val cleaned = AUTH_REDACT_REGEX.replace(raw.take(1024)) { m -> "${m.groupValues[1]}***" }
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen) + "…"
    }

    private fun buildSafeBytesPreview(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""
        val ct = contentType.orEmpty().lowercase()
        val headText = decodeHeadUtf8(bytes)
        if (ct.contains("protobuf") || ct.contains("octet-stream") || (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte())) {
            val hex = bytes.take(12).joinToString(" ") { "%02X".format(it) }
            if (!isHtmlOrXmlPayload(headText, contentType)) {
                return "binary(${bytes.size}B, head=[$hex])"
            }
        }
        return safeBodyPreview(headText)
    }

    private fun decodeHeadUtf8(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
    }

    private fun stripLeadingBomAndWhitespace(text: String): String {
        var idx = 0
        if (text.isNotEmpty() && text[0] == '\uFEFF') idx = 1
        while (idx < text.length && text[idx].isWhitespace()) idx++
        return if (idx == 0) text else text.substring(idx)
    }

    internal fun stripLeadingHtmlCommentsAndWhitespace(text: String): String {
        var s = stripLeadingBomAndWhitespace(text)
        var guard = 0
        while (s.startsWith("<!--") && guard < 8) {
            val endIdx = s.indexOf("-->")
            if (endIdx < 0) break
            s = stripLeadingBomAndWhitespace(s.substring(endIdx + 3))
            guard++
        }
        return s
    }

    private fun logDebug(msg: String) {
        runCatching { Log.d("KiyomiHttp", msg) }
    }

    private fun logWarn(msg: String) {
        runCatching { Log.w("KiyomiHttp", msg) }
    }
}
