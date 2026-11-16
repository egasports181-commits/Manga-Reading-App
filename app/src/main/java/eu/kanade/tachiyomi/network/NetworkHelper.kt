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
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeSolver
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient

/**
 * Mihon-compatible [NetworkHelper] providing `client`, `cloudflareClient` (unified alias),
 * `cookieJar`, and `defaultUserAgentProvider()` to all dynamically loaded Mihon/Keiyoushi extensions.
 *
 * Guarantees the default client preserves:
 * - index 0: [UncaughtExceptionInterceptor]
 * - index 1: [UserAgentInterceptor]
 * - [CloudflareInterceptor] (registered once)
 * while keeping prohibited interceptors (e.g. legacy `IgnoreGzipInterceptor`) absent and sharing
 * a single unified [OkHttpClient] + [AndroidCookieJar] instance across Kiyomi and extensions.
 */
open class NetworkHelper(
    val context: Context,
    baseClient: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") val isDebugBuild: Boolean = false,
    sharedCookieJar: AndroidCookieJar? = null,
    cloudflareSolver: CloudflareChallengeSolver? = null
) {
    open val cookieJar: AndroidCookieJar = sharedCookieJar
        ?: (baseClient.cookieJar as? AndroidCookieJar)
        ?: AndroidCookieJar(baseClient.cookieJar)

    open val client: OkHttpClient = ensureDefaultClient(
        baseClient = if (baseClient.cookieJar === cookieJar) {
            baseClient
        } else {
            baseClient.newBuilder().cookieJar(cookieJar).build()
        },
        userAgentProvider = ::defaultUserAgentProvider,
        context = context,
        cookieJar = cookieJar,
        cloudflareSolver = cloudflareSolver
    )

    @Deprecated("The regular client now includes CloudflareInterceptor.", ReplaceWith("client"))
    open val cloudflareClient: OkHttpClient
        get() = client

    open fun defaultUserAgentProvider(): String = KomaDns.USER_AGENT

    companion object {
        /**
         * Guarantees [UncaughtExceptionInterceptor] (index 0), [UserAgentInterceptor] (index 1),
         * and [CloudflareInterceptor] (when [context] is available or already registered) are
         * present once on [OkHttpClient.interceptors] without duplicating them across derived
         * `.newBuilder()` clients.
         */
        fun ensureDefaultClient(
            baseClient: OkHttpClient,
            userAgentProvider: () -> String = { KomaDns.USER_AGENT },
            context: Context? = null,
            cookieJar: AndroidCookieJar? = null,
            cloudflareSolver: CloudflareChallengeSolver? = null
        ): OkHttpClient {
            val interceptors = baseClient.interceptors
            val uncaughtCount = interceptors.count { it is UncaughtExceptionInterceptor }
            val uaCount = interceptors.count { it is UserAgentInterceptor }
            val cfCount = interceptors.count { it is CloudflareInterceptor }
            val hasProhibitedIgnoreGzip = interceptors.any {
                it.javaClass.simpleName.equals("IgnoreGzipInterceptor", ignoreCase = true)
            }
            val needsCloudflare = cfCount == 0 && context != null
            val alreadyCanonical = uncaughtCount == 1 &&
                uaCount == 1 &&
                cfCount <= 1 &&
                !needsCloudflare &&
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
            val existingCf = interceptors.filterIsInstance<CloudflareInterceptor>().firstOrNull()
                ?: if (context != null) {
                    val jar = cookieJar
                        ?: (baseClient.cookieJar as? AndroidCookieJar)
                        ?: AndroidCookieJar(baseClient.cookieJar)
                    CloudflareInterceptor(
                        context = context.applicationContext ?: context,
                        cookieJar = jar,
                        defaultUserAgentProvider = userAgentProvider,
                        challengeSolver = cloudflareSolver
                    )
                } else {
                    null
                }
            val remaining = interceptors.filterNot {
                it is UncaughtExceptionInterceptor ||
                    it is UserAgentInterceptor ||
                    it is CloudflareInterceptor ||
                    it.javaClass.simpleName.equals("IgnoreGzipInterceptor", ignoreCase = true)
            }

            return baseClient.newBuilder().apply {
                interceptors().clear()
                addInterceptor(existingUncaught)
                addInterceptor(existingUa)
                remaining.forEach { addInterceptor(it) }
                if (existingCf != null) {
                    addInterceptor(existingCf)
                }
            }.build().also { built ->
                runCatching {
                    Log.d(
                        "KiyomiNet",
                        "Normalized NetworkHelper default client with UncaughtExceptionInterceptor + UserAgentInterceptor + CloudflareInterceptor (interceptors=${built.interceptors.size})"
                    )
                }
            }
        }
    }
}
