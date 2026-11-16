/*
 * Copyright (C) 2015 Javier Tomás and Mihon contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured [IOException] thrown when a source responds with a Cloudflare anti-bot challenge
 * (HTTP 200/403/429/503) and automated/cached clearance cookies are insufficient.
 */
class CloudflareBypassRequiredException(
    val url: String,
    val statusCode: Int,
    val verificationAttempted: Boolean = false,
    message: String = "Cloudflare protection (HTTP $statusCode) on $url. Open the source in WebView or verify clearance cookies."
) : IOException(message)

/**
 * Hook allowing the Android UI layer to solve interactive Cloudflare Turnstile/JS
 * challenges via WebView and persist the resulting cookies into [AndroidCookieJar].
 */
fun interface CloudflareChallengeSolver {
    fun attemptResolve(context: Context, request: Request, cookieJar: AndroidCookieJar): Boolean
}

/**
 * Mihon-compatible [Interceptor] registered on the unified default HTTP client.
 *
 * Detects Cloudflare anti-bot challenges across HTTP 200, 403, 429, and 503 responses
 * (`cf-mitigated: challenge` header or Cloudflare challenge HTML markers), serializes resolution
 * per host so concurrent calls share fresh clearance cookies without redundant prompts, invokes
 * the interactive WebView [CloudflareChallengeSolver] when a foreground UI is active, and retries
 * the request once with synced cookies and the exact same `User-Agent`.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
    private val challengeSolver: CloudflareChallengeSolver? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val oldClearanceValue = cookieJar.get(url)
            .firstOrNull { it.name.equals("cf_clearance", ignoreCase = true) }
            ?.value

        val response = chain.proceed(originalRequest)
        if (!isCloudflareChallenge(response)) {
            return response
        }

        val statusCode = response.code
        runCatching {
            Log.w("KiyomiNet", "Cloudflare challenge detected (HTTP $statusCode) on ${url.host}${url.encodedPath}")
        }

        val hostLock = hostLocks.computeIfAbsent(url.host.lowercase()) { Any() }
        synchronized(hostLock) {
            // If another concurrent request on the same host already solved Cloudflare, reuse its cookie
            val latestClearanceValue = cookieJar.get(url)
                .firstOrNull { it.name.equals("cf_clearance", ignoreCase = true) }
                ?.value
            if (!latestClearanceValue.isNullOrBlank() && latestClearanceValue != oldClearanceValue) {
                response.close()
                val retryReq = rebuildWithCookies(originalRequest, url)
                val retryResp = chain.proceed(retryReq)
                if (!isCloudflareChallenge(retryResp)) {
                    return retryResp
                }
                val retryCode = retryResp.code
                retryResp.close()
                throw CloudflareBypassRequiredException(
                    url = url.toString(),
                    statusCode = retryCode,
                    verificationAttempted = true,
                    message = "Cloudflare protection (HTTP $retryCode) on ${url.host} persisted after verification. Open WebView to verify again."
                )
            }

            // Invalidate stale clearance cookies before prompting interactive WebView verification
            cookieJar.remove(url, CLOUDFLARE_COOKIE_NAMES, maxAge = 0)
            response.close()

            val solver = challengeSolver
            val effectiveRequest = ensureRequestUserAgent(originalRequest)
            val resolved = solver != null && runCatching {
                solver.attemptResolve(context, effectiveRequest, cookieJar)
            }.getOrDefault(false)

            if (resolved) {
                val retryReq = rebuildWithCookies(effectiveRequest, url)
                val retryResp = chain.proceed(retryReq)
                if (!isCloudflareChallenge(retryResp)) {
                    runCatching {
                        Log.i("KiyomiNet", "Cloudflare challenge cleared on retry for ${url.host} (HTTP ${retryResp.code})")
                    }
                    return retryResp
                }
                val retryCode = retryResp.code
                retryResp.close()
                throw CloudflareBypassRequiredException(
                    url = url.toString(),
                    statusCode = retryCode,
                    verificationAttempted = true,
                    message = "Cloudflare protection (HTTP $retryCode) on ${url.host} could not be cleared. Please retry verification in WebView."
                )
            }

            throw CloudflareBypassRequiredException(
                url = url.toString(),
                statusCode = statusCode,
                verificationAttempted = false
            )
        }
    }

    private fun ensureRequestUserAgent(request: Request): Request {
        return if (request.header("User-Agent").isNullOrBlank()) {
            request.newBuilder()
                .header("User-Agent", defaultUserAgentProvider())
                .build()
        } else {
            request
        }
    }

    private fun rebuildWithCookies(original: Request, url: HttpUrl): Request {
        val cookies = cookieJar.get(url)
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", defaultUserAgentProvider())
        }
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        } else {
            builder.removeHeader("Cookie")
        }
        return builder.build()
    }

    companion object {
        private val CLOUDFLARE_CHALLENGE_CODES = setOf(200, 403, 429, 503)
        private val CLOUDFLARE_HTTP_ERROR_CODES = setOf(403, 429, 503)
        private val CLOUDFLARE_SERVER_NAMES = setOf("cloudflare-nginx", "cloudflare")
        private val CLOUDFLARE_COOKIE_NAMES = listOf("cf_clearance")
        private val TITLE_REGEX = Regex("(?is)<title[^>]*>(.*?)</title>")
        private val NON_TEXT_CONTENT_TYPES = listOf(
            "image/",
            "video/",
            "audio/",
            "font/",
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "application/x-protobuf",
            "application/protobuf"
        )
        private val hostLocks = ConcurrentHashMap<String, Any>()

        fun isCloudflareChallenge(response: Response): Boolean {
            val cfMitigated = response.header("cf-mitigated").orEmpty()
            if (cfMitigated.equals("challenge", ignoreCase = true)) {
                return true
            }

            val statusCode = response.code
            if (statusCode !in CLOUDFLARE_CHALLENGE_CODES) {
                return false
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (NON_TEXT_CONTENT_TYPES.any { contentType.startsWith(it) || contentType.contains(it) }) {
                return false
            }

            val serverHeader = response.header("Server").orEmpty()
            val bodyPreview = runCatching { response.peekBody(65_536).string() }.getOrDefault("")
            return isCloudflareChallengePayload(
                statusCode = statusCode,
                serverHeader = serverHeader,
                cfMitigated = cfMitigated,
                contentType = contentType,
                bodySample = bodyPreview
            )
        }

        fun isCloudflareChallengePayload(
            statusCode: Int,
            serverHeader: String,
            cfMitigated: String,
            contentType: String?,
            bodySample: String
        ): Boolean {
            if (cfMitigated.equals("challenge", ignoreCase = true)) {
                return true
            }
            if (statusCode !in CLOUDFLARE_CHALLENGE_CODES) {
                return false
            }

            val ctLower = contentType.orEmpty().lowercase()
            if (NON_TEXT_CONTENT_TYPES.any { ctLower.startsWith(it) || ctLower.contains(it) }) {
                return false
            }

            val trimmed = bodySample.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (trimmed.isEmpty()) {
                return false
            }
            // Fast path: JSON/array payloads are never HTML challenge interstitials unless cf-mitigated=challenge
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return false
            }

            val lower = trimmed.lowercase()
            val isCfServer = CLOUDFLARE_SERVER_NAMES.any { serverHeader.contains(it, ignoreCase = true) }
            val title = TITLE_REGEX.find(lower.take(8192))?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val hasStrongChallengeTitle =
                title.contains("just a moment") ||
                    title.contains("attention required! | cloudflare")

            val hasStrongInterstitialMarker =
                lower.contains("/cdn-cgi/challenge-platform/") ||
                    lower.contains("window._cf_chl_opt") ||
                    lower.contains("_cf_chl_ctx") ||
                    lower.contains("id=\"challenge-running\"") ||
                    lower.contains("id='challenge-running'") ||
                    lower.contains("id=\"cf-challenge-running\"") ||
                    lower.contains("class=\"cf-browser-verification\"") ||
                    lower.contains("cf-browser-verification") ||
                    lower.contains("_cf_chl_")

            if (hasStrongChallengeTitle || hasStrongInterstitialMarker) {
                return true
            }

            if (lower.contains("enable javascript and cookies to continue") &&
                (isCfServer || lower.contains("cloudflare") || lower.contains("challenge-platform") || lower.contains("ray id"))
            ) {
                return true
            }

            if (statusCode in CLOUDFLARE_HTTP_ERROR_CODES && isCfServer) {
                return lower.contains("challenge-platform") ||
                    lower.contains("checking your browser") ||
                    lower.contains("one more step") ||
                    lower.contains("just a moment")
            }

            return false
        }
    }
}
