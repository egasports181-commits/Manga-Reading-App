package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import rx.Observable
import rx.Subscriber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .cacheControl(cache)
        .get()
        .build()
}

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .post(body)
        .cacheControl(cache)
        .build()
}

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .put(body)
        .cacheControl(cache)
        .build()
}

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .delete(body)
        .cacheControl(cache)
        .build()
}

fun HEAD(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .head()
        .cacheControl(cache)
        .build()
}

class HttpException(val code: Int) : IllegalStateException("HTTP error $code")

fun Call.asObservable(): Observable<Response> {
    return Observable.create { subscriber: Subscriber<in Response> ->
        val call = this.clone()
        try {
            val response = call.execute()
            if (!subscriber.isUnsubscribed) {
                subscriber.onNext(response)
                subscriber.onCompleted()
            } else {
                response.close()
            }
        } catch (t: Throwable) {
            if (!subscriber.isUnsubscribed) {
                subscriber.onError(t)
            }
        }
    }
}

fun Call.asObservableSuccess(): Observable<Response> {
    return asObservable().doOnNext { response ->
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    val call = this
    cont.invokeOnCancellation {
        runCatching { call.cancel() }
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
}

suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}

suspend fun <T> Observable<T>.awaitSingle(): T = suspendCancellableCoroutine { cont ->
    var emitted = false
    val subscription = take(1).subscribe(
        { value ->
            emitted = true
            if (cont.isActive) cont.resume(value)
        },
        { err ->
            if (cont.isActive) cont.resumeWithException(err)
        },
        {
            if (!emitted && cont.isActive) {
                cont.resumeWithException(NoSuchElementException("Observable completed without emitting an item"))
            }
        }
    )
    cont.invokeOnCancellation {
        subscription.unsubscribe()
    }
}
