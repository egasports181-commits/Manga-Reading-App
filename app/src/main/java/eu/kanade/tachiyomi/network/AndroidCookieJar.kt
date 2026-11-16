/*
 * Copyright (C) 2015 Javier Tomás and Mihon contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import com.example.extension.ExtensionSessionManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over Android's [CookieManager] so production builds talk directly to
 * `CookieManager.getInstance()` while JVM/Robolectric tests can verify exact cookie bridge behavior
 * even when Chromium native WebView libraries are absent.
 */
interface WebCookieBridge {
    fun getCookie(url: String): String?
    fun setCookie(url: String, value: String)
    fun flush()
    fun removeAllCookies()
}

internal object SystemWebCookieBridge : WebCookieBridge {
    private val manager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun getCookie(url: String): String? =
        runCatching { manager?.getCookie(url) }.getOrNull()

    override fun setCookie(url: String, value: String) {
        runCatching {
            val mgr = manager ?: return@runCatching
            mgr.setCookie(url, value)
        }
    }

    override fun flush() {
        runCatching { manager?.flush() }
    }

    override fun removeAllCookies() {
        runCatching {
            manager?.removeAllCookies(null)
            manager?.flush()
        }
    }
}

/**
 * Mihon-compatible [CookieJar] bridging Android's [CookieManager] (for WebView Cloudflare/login sync)
 * and Kiyomi's persistent [CookieJar] delegate (Room + in-memory cache so Robolectric/JVM tests and
 * devices without WebView initialized continue operating seamlessly).
 */
class AndroidCookieJar(
    private val backingCookieJar: CookieJar? = null,
    private val webCookieBridge: WebCookieBridge = SystemWebCookieBridge
) : CookieJar {

    private val memoryFallback = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val clearedCookieNamesByHost = ConcurrentHashMap<String, MutableSet<String>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        val now = System.currentTimeMillis()
        val hostKeys = ExtensionSessionManager.domainKeysForHost(url.host)
        cookies.forEach { incoming ->
            if (incoming.expiresAt > now && incoming.value.isNotEmpty()) {
                hostKeys.forEach { hk -> clearedCookieNamesByHost[hk]?.remove(incoming.name) }
                clearedCookieNamesByHost[incoming.domain.lowercase()]?.remove(incoming.name)
            }
        }

        backingCookieJar?.saveFromResponse(url, cookies)

        val byDomain = cookies.groupBy { it.domain.lowercase() }
        byDomain.forEach { (domainKey, domainCookies) ->
            val hostList = memoryFallback.getOrPut(domainKey) { mutableListOf() }
            synchronized(hostList) {
                hostList.removeAll { it.expiresAt <= now }
                domainCookies.forEach { incoming ->
                    hostList.removeAll {
                        it.name == incoming.name &&
                            it.domain.equals(incoming.domain, ignoreCase = true) &&
                            it.path == incoming.path
                    }
                    if (incoming.expiresAt > now) {
                        hostList.add(incoming)
                    }
                }
            }
        }

        val urlString = url.toString()
        cookies.forEach { cookie ->
            if (cookie.expiresAt <= now) {
                webCookieBridge.setCookie(
                    urlString,
                    "${cookie.name}=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=${cookie.path}"
                )
            } else {
                webCookieBridge.setCookie(urlString, cookie.toString())
            }
        }
        webCookieBridge.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val merged = linkedMapOf<String, Cookie>()
        val clearedNames = collectClearedNamesForHost(url.host)

        // 1. WebView CookieManager cookies (isolated strictly to url host and its domain parents)
        readWebViewCookiesForUrl(url, extraUrls = emptyList())
            .filter { it.expiresAt > now && it.name !in clearedNames && it.matches(url) }
            .forEach { cookie ->
                merged.putIfAbsent("${cookie.name}|${cookie.path}", cookie)
            }

        // 2. Kiyomi persistent Room-backed CookieJar
        val delegated = backingCookieJar?.loadForRequest(url).orEmpty()
        delegated.forEach { cookie ->
            if (cookie.expiresAt > now && cookie.name !in clearedNames && cookie.matches(url)) {
                merged.putIfAbsent("${cookie.name}|${cookie.path}", cookie)
            }
        }

        // 3. In-memory fallback store scoped by matching domain keys
        for (domainKey in ExtensionSessionManager.domainKeysForHost(url.host)) {
            val mem = memoryFallback[domainKey] ?: continue
            synchronized(mem) {
                mem.removeAll { it.expiresAt <= now }
                mem.filter { it.matches(url) && it.name !in clearedNames }.forEach { cookie ->
                    merged.putIfAbsent("${cookie.name}|${cookie.path}", cookie)
                }
            }
        }

        return merged.values.toList()
    }

    /**
     * Synchronizes cookies set in Android WebView's [CookieManager] for [url] (and any matching
     * [extraUrls] belonging to the same domain hierarchy) into Kiyomi's persistent [backingCookieJar]
     * and memory store, unblocking previously invalidated cookie names such as `cf_clearance`.
     */
    fun syncFromWebView(url: HttpUrl, extraUrls: List<HttpUrl> = emptyList()): List<Cookie> {
        webCookieBridge.flush()
        val extracted = readWebViewCookiesForUrl(url, extraUrls)
        if (extracted.isEmpty()) {
            return emptyList()
        }

        val hostKeys = ExtensionSessionManager.domainKeysForHost(url.host)
        extracted.forEach { cookie ->
            hostKeys.forEach { hk -> clearedCookieNamesByHost[hk]?.remove(cookie.name) }
            clearedCookieNamesByHost[cookie.domain.lowercase()]?.remove(cookie.name)
        }

        backingCookieJar?.saveFromResponse(url, extracted)

        val now = System.currentTimeMillis()
        val byDomain = extracted.groupBy { it.domain.lowercase() }
        byDomain.forEach { (domainKey, domainCookies) ->
            val hostList = memoryFallback.getOrPut(domainKey) { mutableListOf() }
            synchronized(hostList) {
                hostList.removeAll { it.expiresAt <= now }
                domainCookies.forEach { incoming ->
                    hostList.removeAll {
                        it.name == incoming.name &&
                            it.domain.equals(incoming.domain, ignoreCase = true) &&
                            it.path == incoming.path
                    }
                    if (incoming.expiresAt > now) {
                        hostList.add(incoming)
                    }
                }
            }
        }

        return extracted
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val existing = get(url)
        var removedCount = 0

        val clearedSet = clearedCookieNamesByHost.getOrPut(url.host.lowercase()) {
            ConcurrentHashMap.newKeySet()
        }
        val namesToClear = cookieNames ?: existing.map { it.name }
        clearedSet.addAll(namesToClear)

        val matching = existing.filter { cookie ->
            cookieNames == null || cookie.name in cookieNames
        }
        val targetUrls = buildDomainCandidateUrls(url, extraUrls = emptyList())
        matching.forEach { cookie ->
            removedCount++
            val deletionDirective = buildString {
                append(cookie.name)
                append("=; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                if (maxAge >= 0) {
                    append("; Max-Age=").append(maxAge)
                }
                append("; Path=").append(cookie.path)
            }
            targetUrls.forEach { candidate ->
                val candidateStr = candidate.toString()
                webCookieBridge.setCookie(candidateStr, deletionDirective)
                webCookieBridge.setCookie(candidateStr, "$deletionDirective; Domain=${cookie.domain}")
            }
        }
        if (matching.isNotEmpty()) {
            webCookieBridge.flush()
            backingCookieJar?.saveFromResponse(
                url,
                matching.map { cookie ->
                    Cookie.Builder()
                        .name(cookie.name)
                        .value("")
                        .domain(cookie.domain)
                        .path(cookie.path)
                        .expiresAt(0L)
                        .build()
                }
            )
        }

        for (domainKey in ExtensionSessionManager.domainKeysForHost(url.host)) {
            memoryFallback[domainKey]?.let { list ->
                synchronized(list) {
                    list.removeAll { cookieNames == null || it.name in cookieNames }
                }
            }
        }
        return removedCount
    }

    fun removeAll() {
        memoryFallback.clear()
        clearedCookieNamesByHost.clear()
        webCookieBridge.removeAllCookies()
    }

    private fun collectClearedNamesForHost(host: String): Set<String> {
        val keys = ExtensionSessionManager.domainKeysForHost(host)
        if (keys.isEmpty()) return emptySet()
        val result = hashSetOf<String>()
        keys.forEach { key ->
            clearedCookieNamesByHost[key]?.let { result.addAll(it) }
        }
        return result
    }

    private fun readWebViewCookiesForUrl(
        url: HttpUrl,
        extraUrls: List<HttpUrl>
    ): List<Cookie> {
        val merged = linkedMapOf<String, Cookie>()
        val candidates = buildDomainCandidateUrls(url, extraUrls)

        for (candidate in candidates) {
            val raw = webCookieBridge.getCookie(candidate.toString()) ?: continue
            if (raw.isBlank()) continue
            val isParentDomain = !candidate.host.equals(url.host, ignoreCase = true)
            raw.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty() && '=' in trimmed) {
                    val cookieSpec = if (isParentDomain) {
                        "$trimmed; Domain=${candidate.host.lowercase()}; Path=/"
                    } else {
                        trimmed
                    }
                    val parseTarget = if (isParentDomain) url else candidate
                    val parsed = Cookie.parse(parseTarget, cookieSpec) ?: return@forEach
                    if (parsed.value.isNotEmpty() &&
                        !parsed.value.equals("deleted", ignoreCase = true) &&
                        !parsed.name.endsWith("deleted", ignoreCase = true) &&
                        (parsed.matches(url) || parsed.matches(candidate))
                    ) {
                        merged.putIfAbsent("${parsed.name}|${parsed.path}", parsed)
                    }
                }
            }
        }
        return merged.values.toList()
    }

    private fun buildDomainCandidateUrls(url: HttpUrl, extraUrls: List<HttpUrl>): List<HttpUrl> {
        val ordered = linkedMapOf<String, HttpUrl>()
        ordered[url.toString()] = url
        extraUrls.forEach { extra ->
            // Enforce strict domain isolation: only inspect extraUrls sharing the same domain family
            if (isSameDomainFamily(url.host, extra.host)) {
                ordered.putIfAbsent(extra.toString(), extra)
            }
        }
        for (domainKey in ExtensionSessionManager.domainKeysForHost(url.host)) {
            val scheme = if (url.isHttps) "https" else url.scheme
            "$scheme://$domainKey/".toHttpUrlOrNull()?.let { candidate ->
                ordered.putIfAbsent(candidate.toString(), candidate)
            }
        }
        return ordered.values.toList()
    }

    private fun isSameDomainFamily(requestHost: String, candidateHost: String): Boolean {
        val reqNorm = requestHost.trim().lowercase()
        val candNorm = candidateHost.trim().lowercase()
        if (reqNorm == candNorm) return true
        return reqNorm.endsWith(".$candNorm") || candNorm.endsWith(".$reqNorm")
    }
}
