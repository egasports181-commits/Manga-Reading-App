package com.example.extension

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.source.MangaSourceAdapter
import com.example.source.MihonSourceAdapter
import eu.kanade.tachiyomi.extension.util.ChildFirstPathClassLoader
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.fullType

object MihonRuntimeInitializer {
    @Volatile
    private var initialized = false

    @OptIn(ExperimentalSerializationApi::class)
    fun ensureInitialized(
        context: Context,
        httpClient: OkHttpClient,
        forceRebind: Boolean = false
    ) {
        val appCtx = context.applicationContext
        val application = appCtx as? Application
        val normalizedClient = NetworkHelper.ensureDefaultClient(httpClient)
        synchronized(this) {
            if (forceRebind || !initialized || !Injekt.hasFactory(NetworkHelper::class.java)) {
                Injekt.registerModule(object : InjektModule {
                    override fun InjektRegistrar.registerInjectables() {
                        if (application != null) {
                            addSingleton(application)
                        }
                        addSingleton(fullType<Context>(), appCtx)
                        addSingleton(normalizedClient)
                        addSingleton(NetworkHelper(appCtx, normalizedClient))
                        addSingletonFactory { JavaScriptEngine(appCtx) }
                        addSingletonFactory {
                            Json {
                                ignoreUnknownKeys = true
                                explicitNulls = false
                                isLenient = true
                                coerceInputValues = true
                            }
                        }
                        addSingletonFactory {
                            ProtoBuf {
                                encodeDefaults = false
                            }
                        }
                        addSingletonFactory {
                            XML {
                                defaultPolicy {
                                    ignoreUnknownChildren()
                                }
                                autoPolymorphic = true
                                xmlDeclMode = XmlDeclMode.Charset
                            }
                        }
                    }
                })
                initialized = true
                runCatching {
                    Log.i(
                        "KiyomiExt",
                        "Mihon runtime initialized (interceptors=${normalizedClient.interceptors.size}, forceRebind=$forceRebind)"
                    )
                }
            }
        }
    }
}

sealed class ExtensionLoadResult {
    data class Loaded(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val signatureSha256: String,
        val isNsfw: Boolean,
        val adapters: List<MangaSourceAdapter>
    ) : ExtensionLoadResult()

    data class Untrusted(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val signatureSha256: String,
        val reason: String
    ) : ExtensionLoadResult()

    data class Error(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val reason: String,
        val isApiMismatch: Boolean = false
    ) : ExtensionLoadResult()
}

data class MihonLoadedState(
    val resultsByPkg: Map<String, ExtensionLoadResult> = emptyMap(),
    val activeAdapters: List<MangaSourceAdapter> = emptyList(),
    val duplicateIdWarnings: List<String> = emptyList()
)

/**
 * Discovers OS-installed Mihon/Tachiyomi extension APKs, cryptographically verifies
 * their signatures (`apksig`), enforces explicit signer trust (`ExtensionTrustManager`),
 * checks `libVersion` compatibility (1.2–1.6), instantiates single `Source` or multi-source
 * `SourceFactory` classes via [ChildFirstPathClassLoader], and registers `MihonSourceAdapter` instances.
 */
class MihonExtensionLoader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val trustManager: ExtensionTrustManager
) {
    private val _state = MutableStateFlow(MihonLoadedState())
    val state: StateFlow<MihonLoadedState> = _state.asStateFlow()

    @Synchronized
    fun reloadInstalledExtensions(
        expectedFingerprintsByPkg: Map<String, String> = emptyMap(),
        pinnedTrustedFingerprints: Set<String> = emptySet(),
        blockedPkgIds: Set<String> = emptySet()
    ): MihonLoadedState {
        MihonRuntimeInitializer.ensureInitialized(context, httpClient)

        val discovered = ExtensionSecurityManager.discoverInstalledExtensionPackages(
            context = context,
            expectedFingerprintsByPkg = expectedFingerprintsByPkg
        )

        val results = linkedMapOf<String, ExtensionLoadResult>()
        val allAdapters = mutableListOf<MangaSourceAdapter>()
        val seenSourceIds = mutableSetOf<String>()
        val duplicateWarnings = mutableListOf<String>()

        for (installed in discovered) {
            if (installed.tachiyomiExtensionClass.isBlank()) {
                // Skip pure IPC companion apps that don't declare Mihon DEX extension classes
                continue
            }
            if (installed.packageName in blockedPkgIds) {
                continue
            }

            // 1. Cryptographic signature verification
            if (!installed.isSignatureVerified || installed.signatureSha256.isBlank()) {
                results[installed.packageName] = ExtensionLoadResult.Error(
                    packageName = installed.packageName,
                    versionCode = installed.versionCode,
                    versionName = installed.versionName,
                    reason = "Cat B (Invalid Signature): Installed APK '${installed.packageName}' has no valid cryptographic signing certificate."
                )
                continue
            }

            // 2. Explicit signer trust gate (user-approved signer cert OR user-pinned repo fingerprint)
            val normSig = ExtensionSecurityManager.normalizeFingerprint(installed.signatureSha256)
            val signerTrusted = trustManager.isSignerTrusted(normSig) ||
                pinnedTrustedFingerprints.any { ExtensionSecurityManager.fingerprintsMatch(it, normSig) }

            if (!signerTrusted) {
                results[installed.packageName] = ExtensionLoadResult.Untrusted(
                    packageName = installed.packageName,
                    versionCode = installed.versionCode,
                    versionName = installed.versionName,
                    signatureSha256 = installed.signatureSha256,
                    reason = "Untrusted Signer: APK '${installed.packageName}' certificate (${normSig.take(12)}…) is not approved yet."
                )
                continue
            }

            // 3. Extension libVersion compatibility check (1.2 .. 1.6)
            val libNum = installed.libVersion.toDoubleOrNull()
            if (libNum != null && (libNum < ExtensionCompatibilityResolver.LIB_VERSION_MIN || libNum > ExtensionCompatibilityResolver.LIB_VERSION_MAX)) {
                results[installed.packageName] = ExtensionLoadResult.Error(
                    packageName = installed.packageName,
                    versionCode = installed.versionCode,
                    versionName = installed.versionName,
                    reason = "Cat A (API Mismatch): Extension '${installed.packageName}' targets extensions-lib ${installed.libVersion} (supported: ${ExtensionCompatibilityResolver.LIB_VERSION_MIN}–${ExtensionCompatibilityResolver.LIB_VERSION_MAX}).",
                    isApiMismatch = true
                )
                continue
            }

            // 4. Load classes via ChildFirstPathClassLoader using isolated APK path and Kiyomi compatibility runtime parent ClassLoader
            val loadOutcome = loadFromInstalledApk(installed)
            results[installed.packageName] = loadOutcome

            if (loadOutcome is ExtensionLoadResult.Loaded) {
                for (adapter in loadOutcome.adapters) {
                    if (seenSourceIds.add(adapter.id)) {
                        allAdapters.add(adapter)
                    } else {
                        duplicateWarnings.add("Duplicate source ID '${adapter.id}' (${adapter.name}) in ${installed.packageName} skipped.")
                    }
                }
            }
        }

        val next = MihonLoadedState(
            resultsByPkg = results,
            activeAdapters = allAdapters,
            duplicateIdWarnings = duplicateWarnings
        )
        runCatching {
            Log.i(
                "KiyomiExt",
                "Reloaded Mihon extensions: discovered=${discovered.size}, loaded=${results.values.count { it is ExtensionLoadResult.Loaded }}, activeSources=${allAdapters.size}, errors=${results.values.count { it is ExtensionLoadResult.Error }}"
            )
            duplicateWarnings.forEach { Log.w("KiyomiExt", it) }
        }
        _state.value = next
        return next
    }

    @Synchronized
    fun unloadPackage(pkgName: String): MihonLoadedState {
        val current = _state.value
        val nextResults = current.resultsByPkg.toMutableMap().apply { remove(pkgName) }
        val nextAdapters = current.activeAdapters.filterNot {
            it is MihonSourceAdapter && it.extensionPackageName == pkgName
        }
        val next = current.copy(resultsByPkg = nextResults, activeAdapters = nextAdapters)
        _state.value = next
        return next
    }

    /**
     * Instantiates Mihon [Source] objects from an installed APK's `sourceApkPath` via
     * [ChildFirstPathClassLoader], wrapping every returned [Source] in a [MihonSourceAdapter].
     */
    private fun loadFromInstalledApk(installed: InstalledExtensionAppInfo): ExtensionLoadResult {
        val apkPath = installed.sourceApkPath
        if (apkPath.isBlank()) {
            return ExtensionLoadResult.Error(
                packageName = installed.packageName,
                versionCode = installed.versionCode,
                versionName = installed.versionName,
                reason = "Missing installed APK path for '${installed.packageName}'."
            )
        }

        val nativeLibDir = runCatching {
            context.packageManager.getApplicationInfo(installed.packageName, 0).nativeLibraryDir
        }.getOrNull()

        return try {
            val classLoader = ChildFirstPathClassLoader(
                apkPath,
                nativeLibDir,
                context.classLoader ?: javaClass.classLoader
            )
            val classNames = installed.tachiyomiExtensionClass
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { raw ->
                    if (raw.startsWith(".")) "${installed.packageName}$raw" else raw
                }

            val sources = instantiateExtensionClasses(classNames, classLoader)
            if (sources.isEmpty()) {
                return ExtensionLoadResult.Error(
                    packageName = installed.packageName,
                    versionCode = installed.versionCode,
                    versionName = installed.versionName,
                    reason = "Extension '${installed.packageName}' returned 0 Mihon Source instances from classes: ${classNames.joinToString()}."
                )
            }

            val adapters = sources.map { source ->
                MihonSourceAdapter(
                    mihonSource = source,
                    extensionPackageName = installed.packageName,
                    httpClient = NetworkHelper.ensureDefaultClient(httpClient)
                )
            }
            runCatching {
                Log.i(
                    "KiyomiExt",
                    "Loaded extension ${installed.packageName} v${installed.versionName}: ${adapters.size} source(s) -> ${adapters.joinToString { "${it.id}(${it.name})" }}"
                )
            }
            ExtensionLoadResult.Loaded(
                packageName = installed.packageName,
                versionCode = installed.versionCode,
                versionName = installed.versionName,
                signatureSha256 = installed.signatureSha256,
                isNsfw = installed.isNsfw,
                adapters = adapters
            )
        } catch (t: Throwable) {
            val kind = when (t) {
                is ClassNotFoundException -> "Extension entry class missing"
                is NoClassDefFoundError -> "Missing runtime dependency class"
                is LinkageError -> "Incompatible bytecode / ABI linkage error"
                is SecurityException -> "Security policy blocked extension loading"
                else -> "Failed initializing extension"
            }
            runCatching {
                Log.w(
                    "KiyomiExt",
                    "Extension load failed for ${installed.packageName}: $kind (${t.javaClass.simpleName}): ${t.message}",
                    t
                )
            }
            ExtensionLoadResult.Error(
                packageName = installed.packageName,
                versionCode = installed.versionCode,
                versionName = installed.versionName,
                reason = "$kind (${t.javaClass.simpleName}): ${t.message ?: "unknown error"}"
            )
        }
    }

    companion object {
        /**
         * Reflectively instantiates Mihon [Source] and [SourceFactory] classes using the given [ClassLoader].
         * Exposed cleanly so unit tests and APK classloaders share the exact same resolution logic.
         */
        fun instantiateExtensionClasses(
            classNames: List<String>,
            classLoader: ClassLoader
        ): List<Source> {
            val loadedSources = mutableListOf<Source>()
            for (fqcn in classNames) {
                val clazz = Class.forName(fqcn, false, classLoader)
                val ctor = clazz.getDeclaredConstructor().apply { isAccessible = true }
                val instance = ctor.newInstance()
                when (instance) {
                    is SourceFactory -> loadedSources.addAll(instance.createSources())
                    is Source -> loadedSources.add(instance)
                    else -> throw IllegalStateException(
                        "Class '$fqcn' implements ${instance?.javaClass?.name}, expected Mihon Source or SourceFactory."
                    )
                }
            }
            return loadedSources.distinctBy { src ->
                runCatching { src.id }.getOrElse { System.identityHashCode(src).toLong() }
            }
        }
    }
}
