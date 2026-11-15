/*
 * Copyright (C) 2015 Javier Tomás and Mihon / Keiyoushi contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package keiyoushi.utils

import com.example.network.HttpResponseValidator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.io.IOException
import java.io.InputStream

val jsonInstance: Json
    get() = Injekt.getInstanceOrElse(fullType<Json>().type) {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
    }

inline fun <reified T> String.parseAs(json: Json = jsonInstance): T =
    json.decodeFromString(this)

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T = use { resp ->
    HttpResponseValidator.assertValidJsonResponseForStreaming(resp, T::class.java.simpleName)
    val body = resp.body ?: throw IOException("Response body was null when decoding ${T::class.java.simpleName}")
    json.decodeFromBufferedSource(body.source())
}

inline fun <reified T> InputStream.parseAs(json: Json = jsonInstance): T = use { stream ->
    json.decodeFromString(stream.bufferedReader().readText())
}

inline fun <reified T> JsonElement.parseAs(json: Json = jsonInstance): T =
    json.decodeFromJsonElement(this)
