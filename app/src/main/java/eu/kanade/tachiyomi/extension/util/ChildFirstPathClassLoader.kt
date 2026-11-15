/*
 * Copyright (C) 2015 Javier Tomás and Mihon contributors
 * Adapted for Kiyomi under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package eu.kanade.tachiyomi.extension.util

import dalvik.system.PathClassLoader

/**
 * Mihon's Child-First [PathClassLoader] for APK extensions.
 *
 * Unlike Android's default parent-first [PathClassLoader], extension APKs often bundle their own
 * library versions (e.g., helper utilities, older parsers, custom serializers). Loading those classes
 * child-first from the extension's DEX prevents [NoSuchMethodError] / [LinkageError] conflicts with
 * Kiyomi's dependencies, while core Android/Kotlin/host-extension-API packages remain parent-first
 * so [Source], [OkHttpClient], [Injekt], [Json], and [NetworkHelper] share identical runtime types.
 */
class ChildFirstPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader?
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // 1. Check already loaded classes in this ClassLoader
        findLoadedClass(name)?.let { return it }

        // 2. Always delegate core system, Android framework, and shared host-API packages parent-first
        if (shouldDelegateParentFirst(name)) {
            return super.loadClass(name, resolve)
        }

        // 3. Child-first: attempt to load directly from this extension APK's DEX
        try {
            return findClass(name)
        } catch (_: ClassNotFoundException) {
            // Fall through to parent ClassLoader
        }

        // 4. Fallback to parent ClassLoader
        return super.loadClass(name, resolve)
    }

    internal fun shouldDelegateParentFirst(name: String): Boolean {
        return PARENT_FIRST_PREFIXES.any { name.startsWith(it) }
    }

    companion object {
        private val PARENT_FIRST_PREFIXES = arrayOf(
            "java.",
            "javax.",
            "sun.",
            "dalvik.",
            "android.",
            "androidx.",
            "kotlin.",
            "kotlinx.",
            "eu.kanade.tachiyomi.",
            "tachiyomi.",
            "keiyoushi.utils.",
            "uy.kohesive.injekt.",
            "okhttp3.",
            "okio.",
            "org.jsoup.",
            "rx.",
            "org.xml.",
            "org.w3c.",
            "org.json.",
            "nl.adaptivity.xmlutil.",
            "app.cash.quickjs.",
            "org.conscrypt."
        )
    }
}
