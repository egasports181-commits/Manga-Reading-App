package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import com.example.network.KomaDns
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import okhttp3.OkHttpClient

open class NetworkHelper(
    val context: Context,
    baseClient: OkHttpClient
) {
    open val client: OkHttpClient = ensureDefaultClient(baseClient)

    open val cloudflareClient: OkHttpClient
        get() = client

    open fun defaultUserAgentProvider(): String = KomaDns.USER_AGENT

    companion object {
        /**
         * Guarantees [UncaughtExceptionInterceptor] is present once at index 0 of
         * [OkHttpClient.interceptors] without duplicating it across derived builders.
         */
        fun ensureDefaultClient(baseClient: OkHttpClient): OkHttpClient {
            val interceptors = baseClient.interceptors
            val count = interceptors.count { it is UncaughtExceptionInterceptor }
            if (count == 1 && interceptors.firstOrNull() is UncaughtExceptionInterceptor) {
                return baseClient
            }
            val remaining = interceptors.filterNot { it is UncaughtExceptionInterceptor }
            return baseClient.newBuilder().apply {
                interceptors().clear()
                addInterceptor(UncaughtExceptionInterceptor())
                remaining.forEach { addInterceptor(it) }
            }.build().also { built ->
                runCatching {
                    Log.d(
                        "KiyomiNet",
                        "Normalized NetworkHelper default client with UncaughtExceptionInterceptor (interceptors=${built.interceptors.size})"
                    )
                }
            }
        }
    }
}

