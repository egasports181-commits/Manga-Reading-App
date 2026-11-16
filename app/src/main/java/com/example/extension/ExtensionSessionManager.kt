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
 * Manages persistent HTTP cookies & strict per-domain session isolation for Kiyomi sources
 * and Mihon/Keiyoushi extensions.
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
            val now = System.currentTimeMillis()
            val byDomain = cookies.groupBy { it.domain.lowercase() }

            byDomain.forEach { (domainKey, domainCookies) ->
                val existing = cache.getOrPut(domainKey) { mutableListOf() }
                val entities = synchronized(existing) {
                    existing.removeAll { it.expiresAt <= now }
                    domainCookies.forEach { incoming ->
                        existing.removeAll {
                            it.name == incoming.name &&
                                it.domain.equals(incoming.domain, ignoreCase = true) &&
                                it.path == incoming.path
                        }
                        if (incoming.expiresAt > now) {
                            existing.add(incoming)
                        }
                    }
                    existing.map { c ->
                        ExtensionCookieEntity(
                            host = domainKey,
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
                if (entities.isEmpty()) {
                    cache.remove(domainKey, existing)
                }
                ioScope.launch {
                    runCatching {
                        extensionDao.deleteCookiesForHost(domainKey)
                        if (entities.isNotEmpty()) extensionDao.insertCookies(entities)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val matched = mutableListOf<Cookie>()
            for (domainKey in domainKeysForHost(url.host)) {
                val list = cache[domainKey] ?: continue
                synchronized(list) {
                    list.removeAll { it.expiresAt <= now }
                    matched.addAll(list.filter { it.matches(url) })
                }
            }
            return matched
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
                    .domain(e.domain.lowercase())
                    .path(e.path)
                    .expiresAt(e.expiresAt)
                if (e.secure) builder.secure()
                if (e.httpOnly) builder.httpOnly()
                builder.build()
            }.getOrNull() ?: return@forEach
            val domainKey = cookie.domain.lowercase()
            val list = cache.getOrPut(domainKey) { mutableListOf() }
            synchronized(list) {
                list.removeAll {
                    it.name == cookie.name &&
                        it.domain.equals(cookie.domain, ignoreCase = true) &&
                        it.path == cookie.path
                }
                list.add(cookie)
            }
        }
    }

    suspend fun clearCookiesForHost(host: String) {
        val norm = host.trim().lowercase()
        cache.remove(norm)
        cache.remove(host)
        extensionDao.deleteCookiesForHost(norm)
        if (norm != host) {
            extensionDao.deleteCookiesForHost(host)
        }
    }

    suspend fun clearAllCookies() {
        cache.clear()
        extensionDao.clearAllCookies()
    }

    fun getCookieCountForHost(host: String): Int =
        domainKeysForHost(host).sumOf { key ->
            val now = System.currentTimeMillis()
            cache[key]?.let { list ->
                synchronized(list) { list.count { it.expiresAt > now } }
            } ?: 0
        }

    companion object {
        internal fun domainKeysForHost(host: String): List<String> {
            val norm = host.trim().lowercase().trimEnd('.')
            if (norm.isEmpty()) return emptyList()
            val keys = mutableListOf(norm)
            var idx = norm.indexOf('.')
            while (idx >= 0) {
                val suffix = norm.substring(idx + 1)
                if ('.' in suffix) {
                    keys.add(suffix)
                }
                idx = norm.indexOf('.', idx + 1)
            }
            return keys
        }
    }
}
