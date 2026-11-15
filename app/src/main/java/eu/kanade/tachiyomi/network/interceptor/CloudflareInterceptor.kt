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

/**
 * Structured [IOException] thrown when a source responds with a Cloudflare anti-bot challenge
 * (HTTP 403/503) and automated/cached clearance cookies are insufficient.
 */
class CloudflareBypassRequiredException(
    val url: String,
    val statusCode: Int,
    message: String = "Cloudflare protection (HTTP $statusCode) on $url. Open source in WebView or refresh cookies to bypass."
) : IOException(message)

/**
 * Optional hook allowing the Android UI layer to solve interactive Cloudflare Turnstile/JS
 * challenges via WebView and persist the resulting `cf_clearance` cookies into [AndroidCookieJar].
 */
fun interface CloudflareChallengeSolver {
    fun attemptResolve(context: Context, request: Request, cookieJar: AndroidCookieJar): Boolean
}

/**
 * Mihon-compatible [Interceptor] for `network.cloudflareClient` that detects Cloudflare
 * anti-bot challenges (403/503 with `cloudflare` server headers or `cf-mitigated` / HTML markers),
 * clears stale challenge cookies, invokes a registered WebView solver if available, and otherwise
 * fails cleanly with [CloudflareBypassRequiredException] without hanging background threads.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
    private val challengeSolver: CloudflareChallengeSolver? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        if (!isCloudflareChallenge(response)) {
            return response
        }

        val url = originalRequest.url
        val statusCode = response.code
        runCatching {
            Log.w("KiyomiNet", "Cloudflare challenge detected (${statusCode}) on ${url.host}")
        }

        // Remove stale clearance cookies before retrying or throwing
        cookieJar.remove(url, CLOUDFLARE_COOKIE_NAMES, maxAge = 0)

        val solver = challengeSolver
        val resolved = solver != null && runCatching {
            solver.attemptResolve(context, originalRequest, cookieJar)
        }.getOrDefault(false)

        if (resolved) {
            response.close()
            val retryReq = rebuildWithCookies(originalRequest, url)
            val retryResp = chain.proceed(retryReq)
            if (!isCloudflareChallenge(retryResp)) {
                return retryResp
            }
            retryResp.close()
            throw CloudflareBypassRequiredException(url.toString(), retryResp.code)
        }

        response.close()
        throw CloudflareBypassRequiredException(url.toString(), statusCode)
    }

    private fun rebuildWithCookies(original: Request, url: HttpUrl): Request {
        val cookies = cookieJar.get(url)
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", defaultUserAgentProvider())
        }
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
        return builder.build()
    }

    companion object {
        private val CLOUDFLARE_ERROR_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVER_NAMES = setOf("cloudflare-nginx", "cloudflare")
        private val CLOUDFLARE_COOKIE_NAMES = listOf("cf_clearance")

        fun isCloudflareChallenge(response: Response): Boolean {
            if (response.code !in CLOUDFLARE_ERROR_CODES) return false
            val serverHeader = response.header("Server").orEmpty().lowercase()
            if (CLOUDFLARE_SERVER_NAMES.none { serverHeader.contains(it) }) {
                return false
            }
            if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                return true
            }
            val bodyPreview = runCatching { response.peekBody(16_384).string() }.getOrDefault("")
            return bodyPreview.contains("Just a moment...", ignoreCase = true) ||
                bodyPreview.contains("_cf_chl_", ignoreCase = true) ||
                bodyPreview.contains("challenge-platform", ignoreCase = true) ||
                bodyPreview.contains("cf-browser-verification", ignoreCase = true)
        }
    }
}
