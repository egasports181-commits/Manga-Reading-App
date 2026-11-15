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

    private val webCookieManager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return

        backingCookieJar?.saveFromResponse(url, cookies)

        val hostList = memoryFallback.getOrPut(url.host) { mutableListOf() }
        synchronized(hostList) {
            cookies.forEach { incoming ->
                hostList.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) {
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

        // 1. WebView CookieManager cookies (e.g., cf_clearance or user WebView login)
        runCatching {
            val mgr = webCookieManager ?: return@runCatching
            val raw = mgr.getCookie(url.toString())
            if (!raw.isNullOrBlank()) {
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        Cookie.parse(url, trimmed)?.let { cookie ->
                            if (!cookie.name.endsWith("deleted", ignoreCase = true)) {
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
            merged.putIfAbsent("${cookie.name}|${cookie.domain}|${cookie.path}", cookie)
        }

        val now = System.currentTimeMillis()
        val mem = memoryFallback[url.host]
        if (mem != null) {
            synchronized(mem) {
                mem.removeAll { it.expiresAt <= now }
                mem.filter { it.matches(url) }.forEach { cookie ->
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

        existing.filter { cookie ->
            cookieNames == null || cookie.name in cookieNames
        }.forEach { cookie ->
            removedCount++
            runCatching {
                webCookieManager?.setCookie(urlString, "${cookie.name}=; Max-Age=$maxAge; Path=${cookie.path}")
            }
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
        runCatching {
            webCookieManager?.removeAllCookies(null)
            webCookieManager?.flush()
        }
    }
}
