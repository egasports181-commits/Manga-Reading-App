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
import app.cash.quickjs.QuickJs

/**
 * Mihon-compatible [JavaScriptEngine] injected by extensions via `Injekt` / `injectLazy()`
 * to evaluate obfuscated page scripts and token decoders inside QuickJS (`quickjs-android`).
 */
@Suppress("UNUSED_PARAMETER")
class JavaScriptEngine(private val context: Context) {

    @Suppress("UNCHECKED_CAST")
    fun <T> evaluate(script: String): T {
        return try {
            QuickJs.create().use { quickJs ->
                quickJs.evaluate(script) as T
            }
        } catch (err: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "QuickJS native engine is unavailable in this JVM test environment: ${err.message}",
                err
            )
        }
    }
}
