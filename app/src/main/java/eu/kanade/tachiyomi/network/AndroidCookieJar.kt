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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Mihon-compatible [CookieJar] bridging Android's [CookieManager] (for WebView Cloudflare/login sync)
 * and Kiyomi's persistent [CookieJar] delegate (Room + in-memory cache so Robolectric/JVM tests and
 * devices without WebView initialized continue operating seamlessly).
 */
class AndroidCookieJar(
    private val backingCookieJar: CookieJar? = null
) : CookieJar {

    private val memoryFallback = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val clearedCookieNamesByHost = ConcurrentHashMap<String, MutableSet<String>>()

    private val webCookieManager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        val hostKey = url.host.lowercase()
        val now = System.currentTimeMillis()
        val clearedSet = clearedCookieNamesByHost[hostKey]
        cookies.forEach { incoming ->
            if (incoming.expiresAt > now && incoming.value.isNotEmpty()) {
                clearedSet?.remove(incoming.name)
            }
        }

        backingCookieJar?.saveFromResponse(url, cookies)

        val hostList = memoryFallback.getOrPut(url.host) { mutableListOf() }
        synchronized(hostList) {
            cookies.forEach { incoming ->
                hostList.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > now) {
                    hostList.add(incoming)
                }
            }
        }

        val urlString = url.toString()
        runCatching {
            val mgr = webCookieManager ?: return@runCatching
            cookies.forEach { mgr.setCookie(urlString, it.toString()) }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val merged = linkedMapOf<String, Cookie>()
        val clearedNames = clearedCookieNamesByHost[url.host.lowercase()].orEmpty()

        // 1. WebView CookieManager cookies (e.g., cf_clearance or user WebView login)
        runCatching {
            val mgr = webCookieManager ?: return@runCatching
            val raw = mgr.getCookie(url.toString())
            if (!raw.isNullOrBlank()) {
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        Cookie.parse(url, trimmed)?.let { cookie ->
                            if (cookie.name !in clearedNames &&
                                cookie.value.isNotEmpty() &&
                                !cookie.value.equals("deleted", ignoreCase = true) &&
                                !cookie.name.endsWith("deleted", ignoreCase = true)
                            ) {
                                merged["${cookie.name}|${cookie.domain}|${cookie.path}"] = cookie
                            }
                        }
                    }
                }
            }
        }

        // 2. Kiyomi persistent Room-backed CookieJar (or fallback memory store)
        val delegated = backingCookieJar?.loadForRequest(url).orEmpty()
        delegated.forEach { cookie ->
            if (cookie.name !in clearedNames) {
                merged.putIfAbsent("${cookie.name}|${cookie.domain}|${cookie.path}", cookie)
            }
        }

        val now = System.currentTimeMillis()
        val mem = memoryFallback[url.host]
        if (mem != null) {
            synchronized(mem) {
                mem.removeAll { it.expiresAt <= now }
                mem.filter { it.matches(url) && it.name !in clearedNames }.forEach { cookie ->
                    merged.putIfAbsent("${cookie.name}|${cookie.domain}|${cookie.path}", cookie)
                }
            }
        }

        return merged.values.toList()
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
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
        matching.forEach { cookie ->
            removedCount++
            runCatching {
                val deletionDirective = buildString {
                    append(cookie.name)
                    append("=; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    if (maxAge >= 0) {
                        append("; Max-Age=").append(maxAge)
                    }
                    append("; Path=").append(cookie.path)
                }
                webCookieManager?.setCookie(urlString, deletionDirective)
                if (!cookie.hostOnly) {
                    webCookieManager?.setCookie(urlString, "$deletionDirective; Domain=${cookie.domain}")
                }
            }
        }
        if (matching.isNotEmpty()) {
            runCatching { webCookieManager?.flush() }
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

        memoryFallback[url.host]?.let { list ->
            synchronized(list) {
                list.removeAll { cookieNames == null || it.name in cookieNames }
            }
        }
        return removedCount
    }

    fun removeAll() {
        memoryFallback.clear()
        clearedCookieNamesByHost.clear()
        runCatching {
            webCookieManager?.removeAllCookies(null)
            webCookieManager?.flush()
        }
    }
}
