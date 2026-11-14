package com.example.extension

import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionCookieEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages persistent HTTP cookies & session isolation per source/extension host.
 */
class ExtensionSessionManager(
    private val extensionDao: ExtensionDao
) {
    private val cache = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var loadedFromDb = false

    val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val existing = cache.getOrPut(host) { mutableListOf() }
            val entities = synchronized(existing) {
                cookies.forEach { incoming ->
                    existing.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                    if (incoming.expiresAt > System.currentTimeMillis()) {
                        existing.add(incoming)
                    }
                }
                existing.map { c ->
                    ExtensionCookieEntity(
                        host = host,
                        name = c.name,
                        value = c.value,
                        domain = c.domain,
                        path = c.path,
                        expiresAt = c.expiresAt,
                        secure = c.secure,
                        httpOnly = c.httpOnly
                    )
                }
            }
            ioScope.launch {
                runCatching {
                    extensionDao.deleteCookiesForHost(host)
                    if (entities.isNotEmpty()) extensionDao.insertCookies(entities)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val list = cache[url.host] ?: return emptyList()
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                return list.filter { it.matches(url) }
            }
        }
    }

    suspend fun preloadCookies() {
        if (loadedFromDb) return
        loadedFromDb = true
        val all = runCatching { extensionDao.getAllCookiesOnce() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        all.filter { it.expiresAt > now }.forEach { e ->
            val cookie = runCatching {
                val builder = Cookie.Builder()
                    .name(e.name)
                    .value(e.value)
                    .domain(e.domain)
                    .path(e.path)
                    .expiresAt(e.expiresAt)
                if (e.secure) builder.secure()
                if (e.httpOnly) builder.httpOnly()
                builder.build()
            }.getOrNull() ?: return@forEach
            cache.getOrPut(e.host) { mutableListOf() }.add(cookie)
        }
    }

    suspend fun clearCookiesForHost(host: String) {
        cache.remove(host)
        extensionDao.deleteCookiesForHost(host)
    }

    suspend fun clearAllCookies() {
        cache.clear()
        extensionDao.clearAllCookies()
    }

    fun getCookieCountForHost(host: String): Int = cache[host]?.size ?: 0
}
