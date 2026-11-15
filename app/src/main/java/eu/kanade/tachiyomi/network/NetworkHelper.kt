package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import com.example.network.KomaDns
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.CookieJar
import okhttp3.OkHttpClient

open class NetworkHelper(
    val context: Context,
    baseClient: OkHttpClient
) {
    open val client: OkHttpClient = ensureDefaultClient(baseClient, ::defaultUserAgentProvider)

    open val cloudflareClient: OkHttpClient
        get() = client

    open val cookieJar: CookieJar
        get() = client.cookieJar

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
            val alreadyCanonical = uncaughtCount == 1 &&
                uaCount == 1 &&
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
                it is UncaughtExceptionInterceptor || it is UserAgentInterceptor
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

