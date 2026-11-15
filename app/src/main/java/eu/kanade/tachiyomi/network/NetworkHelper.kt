/*
 * Copyright (C) 2015 Javier Tomás and Mihon contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import com.example.network.KomaDns
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient

/**
 * Mihon-compatible [NetworkHelper] providing `client`, `cloudflareClient`, `cookieJar`,
 * and `defaultUserAgentProvider()` to all dynamically loaded Mihon/Keiyoushi extensions.
 *
 * Guarantees both `client` and `cloudflareClient` preserve:
 * - index 0: [UncaughtExceptionInterceptor]
 * - index 1: [UserAgentInterceptor]
 * while keeping prohibited interceptors (e.g. legacy `IgnoreGzipInterceptor`) absent.
 */
open class NetworkHelper(
    val context: Context,
    baseClient: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") val isDebugBuild: Boolean = false
) {
    open val cookieJar: AndroidCookieJar = AndroidCookieJar(baseClient.cookieJar)

    open val client: OkHttpClient = ensureDefaultClient(
        baseClient = baseClient.newBuilder().cookieJar(cookieJar).build(),
        userAgentProvider = ::defaultUserAgentProvider
    )

    open val cloudflareClient: OkHttpClient by lazy {
        val cloudflareInterceptor = CloudflareInterceptor(
            context = context,
            cookieJar = cookieJar,
            defaultUserAgentProvider = ::defaultUserAgentProvider
        )
        ensureDefaultClient(
            baseClient = client.newBuilder()
                .apply {
                    val existing = interceptors().filterIsInstance<CloudflareInterceptor>()
                    if (existing.isEmpty()) {
                        addInterceptor(cloudflareInterceptor)
                    }
                }
                .build(),
            userAgentProvider = ::defaultUserAgentProvider
        )
    }

    open fun defaultUserAgentProvider(): String = KomaDns.USER_AGENT

    companion object {
        /**
         * Guarantees [UncaughtExceptionInterceptor] (index 0) and [UserAgentInterceptor] (index 1)
         * are present once in canonical Mihon order on [OkHttpClient.interceptors] without
         * duplicating them across derived `.newBuilder()` clients.
         */
        fun ensureDefaultClient(
            baseClient: OkHttpClient,
            userAgentProvider: () -> String = { KomaDns.USER_AGENT }
        ): OkHttpClient {
            val interceptors = baseClient.interceptors
            val uncaughtCount = interceptors.count { it is UncaughtExceptionInterceptor }
            val uaCount = interceptors.count { it is UserAgentInterceptor }
            val hasProhibitedIgnoreGzip = interceptors.any {
                it.javaClass.simpleName.equals("IgnoreGzipInterceptor", ignoreCase = true)
            }
            val alreadyCanonical = uncaughtCount == 1 &&
                uaCount == 1 &&
                !hasProhibitedIgnoreGzip &&
                interceptors.getOrNull(0) is UncaughtExceptionInterceptor &&
                interceptors.getOrNull(1) is UserAgentInterceptor
            if (alreadyCanonical) {
                return baseClient
            }

            val existingUncaught = interceptors.filterIsInstance<UncaughtExceptionInterceptor>().firstOrNull()
                ?: UncaughtExceptionInterceptor()
            val existingUa = interceptors.filterIsInstance<UserAgentInterceptor>().firstOrNull()
                ?: UserAgentInterceptor(userAgentProvider)
            val remaining = interceptors.filterNot {
                it is UncaughtExceptionInterceptor ||
                    it is UserAgentInterceptor ||
                    it.javaClass.simpleName.equals("IgnoreGzipInterceptor", ignoreCase = true)
            }

            return baseClient.newBuilder().apply {
                interceptors().clear()
                addInterceptor(existingUncaught)
                addInterceptor(existingUa)
                remaining.forEach { addInterceptor(it) }
            }.build().also { built ->
                runCatching {
                    Log.d(
                        "KiyomiNet",
                        "Normalized NetworkHelper default client with UncaughtExceptionInterceptor + UserAgentInterceptor (interceptors=${built.interceptors.size})"
                    )
                }
            }
        }
    }
}
