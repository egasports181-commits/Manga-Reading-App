package eu.kanade.tachiyomi.network.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Mihon-compatible OkHttp application interceptor that ensures any unexpected non-[IOException]
 * runtime exception thrown during request execution is converted into an [IOException].
 *
 * OkHttp's async dispatcher only forwards [IOException]s to `Callback.onFailure`; non-[IOException]
 * exceptions rethrown from an interceptor crash the dispatcher worker thread.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: Exception) {
            if (e is IOException) {
                throw e
            } else {
                runCatching {
                    Log.w(
                        "KiyomiNet",
                        "Uncaught non-IO exception host=${request.url.host} path=${request.url.encodedPath}: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}",
                        e
                    )
                }
                throw IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }
}
