package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Mihon `extensions-lib` compatible sliding-window rate limiter for [OkHttpClient.Builder].
 */
class RateLimitInterceptor(
    private val host: String?,
    private val permits: Int,
    private val periodMillis: Long
) : Interceptor {
    private val timestamps = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val shouldLimit = host == null || request.url.host.equals(host, ignoreCase = true)
        if (!shouldLimit || permits <= 0 || periodMillis <= 0L) {
            return chain.proceed(request)
        }

        val waitMs = synchronized(timestamps) {
            val now = System.currentTimeMillis()
            val windowStart = now - periodMillis
            while (true) {
                val head = timestamps.peekFirst() ?: break
                if (head <= windowStart) timestamps.removeFirst() else break
            }
            if (timestamps.size < permits) {
                timestamps.addLast(now)
                0L
            } else {
                val oldest = timestamps.peekFirst() ?: now
                val target = oldest + periodMillis
                timestamps.addLast(target)
                (target - now).coerceAtLeast(0L)
            }
        }

        if (waitMs > 0L) {
            Thread.sleep(waitMs)
        }
        return chain.proceed(request)
    }
}

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1L,
    unit: TimeUnit = TimeUnit.SECONDS
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(null, permits, unit.toMillis(period)))

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(null, permits, period.toJavaDuration().toMillis()))

fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Long = 1L,
    unit: TimeUnit = TimeUnit.SECONDS
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(url.host, permits, unit.toMillis(period)))

fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Duration
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(url.host, permits, period.toJavaDuration().toMillis()))

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1L,
    unit: TimeUnit = TimeUnit.SECONDS
): OkHttpClient.Builder = rateLimitHost(url.toHttpUrl(), permits, period, unit)

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration
): OkHttpClient.Builder = rateLimitHost(url.toHttpUrl(), permits, period)
