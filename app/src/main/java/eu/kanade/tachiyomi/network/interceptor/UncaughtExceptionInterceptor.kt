package eu.kanade.tachiyomi.network.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Mihon-compatible OkHttp application interceptor that ensures any unexpected non-[IOException]
 * runtime exception thrown during request execution is converted into an [IOException].
 *
 * OkHttp's async dispatcher only forwards [IOException]s to `Callback.onFailure`; unchecked
 * exceptions otherwise crash the dispatcher thread. Real Mihon extensions (e.g. MangaDex) also
 * verify that an instance of this interceptor is registered on `network.client`.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                Log.w(
                    "KiyomiNet",
                    "Uncaught non-IO exception host=${request.url.host} path=${request.url.encodedPath}: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                )
            }
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
