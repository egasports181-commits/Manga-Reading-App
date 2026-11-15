package eu.kanade.tachiyomi.network.interceptor

import com.example.network.KomaDns
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Mihon-compatible OkHttp application interceptor that injects a default `User-Agent`
 * header via [defaultUserAgentProvider] whenever the outgoing request does not specify one.
 */
class UserAgentInterceptor(
    private val defaultUserAgentProvider: () -> String
) : Interceptor {

    constructor() : this({ KomaDns.USER_AGENT })

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        return if (originalRequest.header("User-Agent").isNullOrEmpty()) {
            val newRequest = originalRequest
                .newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", defaultUserAgentProvider())
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
