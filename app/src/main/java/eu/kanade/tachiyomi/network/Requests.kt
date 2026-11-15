/*
 * Copyright (C) 2015 Javier Tomás and Mihon contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.okio.decodeFromBufferedSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

fun PATCH(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .patch(body)
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
        subscriber.add(
            rx.subscriptions.Subscriptions.create {
                runCatching { call.cancel() }
            }
        )
        try {
            val response = call.execute()
            if (!subscriber.isUnsubscribed) {
                subscriber.onNext(response)
                subscriber.onCompleted()
            } else {
                response.close()
            }
        } catch (t: Throwable) {
            rx.exceptions.Exceptions.throwIfFatal(t)
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

@PublishedApi
internal val sharedMihonJson: Json
    get() = Injekt.getInstanceOrElse(fullType<Json>().type) {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
    }

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Response.parseAs(json: Json = sharedMihonJson): T = use { resp ->
    val body = resp.body ?: throw IOException("Response body was null when decoding ${T::class.java.simpleName}")
    json.decodeFromBufferedSource(body.source())
}

inline fun <reified T> String.parseAs(json: Json = sharedMihonJson): T =
    json.decodeFromString(this)

inline fun <reified T> InputStream.parseAs(json: Json = sharedMihonJson): T = use { stream ->
    json.decodeFromString(stream.bufferedReader().readText())
}

inline fun <reified T> JsonElement.parseAs(json: Json = sharedMihonJson): T =
    json.decodeFromJsonElement(this)
