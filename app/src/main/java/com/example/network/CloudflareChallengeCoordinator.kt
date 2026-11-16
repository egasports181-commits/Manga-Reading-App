package com.example.network

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.example.ui.screens.webview.CloudflareWebViewActivity
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeSolver
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates interactive Cloudflare WebView verification on Android foreground [Activity]
 * instances when [eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor] encounters a
 * challenge, while synchronizing verified cookies into the shared [AndroidCookieJar].
 *
 * Designed after Mihon's `CloudflareInterceptor` synchronous-wait pattern:
 * - Never blocks the Android Main/UI thread (fails fast if invoked on main looper).
 * - Never hangs background `WorkManager` / headless tasks when no resumed foreground [Activity] exists.
 * - Preserves the exact OkHttp `User-Agent` between request and WebView session.
 */
class CloudflareChallengeCoordinator(
    private val cookieJar: AndroidCookieJar,
    private val waitTimeoutSeconds: Long = 90L,
    private val activityLauncher: ((Activity, Intent) -> Unit)? = null
) : CloudflareChallengeSolver {

    private val currentResumedActivity = AtomicReference<WeakReference<Activity>?>(null)

    @Volatile
    private var attachedApp: Application? = null

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is CloudflareWebViewActivity) {
                currentResumedActivity.set(WeakReference(activity))
            }
        }

        override fun onActivityPaused(activity: Activity) {
            val current = currentResumedActivity.get()?.get()
            if (current === activity) {
                currentResumedActivity.set(null)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            val current = currentResumedActivity.get()?.get()
            if (current === activity) {
                currentResumedActivity.set(null)
            }
        }
    }

    init {
        activeCookieJarRef.set(cookieJar)
    }

    fun attachToApplication(application: Application) {
        if (attachedApp === application) return
        attachedApp?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        attachedApp = application
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        activeCookieJarRef.set(cookieJar)
    }

    fun setForegroundActivityForTesting(activity: Activity?) {
        currentResumedActivity.set(activity?.let { WeakReference(it) })
    }

    override fun attemptResolve(
        context: Context,
        request: Request,
        cookieJar: AndroidCookieJar
    ): Boolean {
        activeCookieJarRef.compareAndSet(null, cookieJar)

        // Never block the main/UI thread waiting for another Activity
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching {
                Log.w("KiyomiCf", "Skipping blocking WebView verification on main thread for ${request.url.host}")
            }
            return false
        }

        val hostActivity = currentResumedActivity.get()?.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
        if (hostActivity == null) {
            runCatching {
                Log.d("KiyomiCf", "No foreground Activity available for Cloudflare WebView on ${request.url.host}; failing cleanly")
            }
            return false
        }

        val challengeId = UUID.randomUUID().toString()
        val entry = PendingChallenge(
            latch = CountDownLatch(1),
            succeeded = AtomicBoolean(false),
            targetUrl = request.url
        )
        pendingChallenges[challengeId] = entry

        val userAgent = request.header("User-Agent")?.takeIf { it.isNotBlank() } ?: KomaDns.USER_AGENT
        val intent = createVerificationIntent(
            context = hostActivity,
            url = request.url.toString(),
            sourceName = request.url.host,
            userAgent = userAgent,
            referer = request.header("Referer").orEmpty(),
            challengeId = challengeId,
            isManual = false
        )

        val launched = runCatching {
            val launcher = activityLauncher
            if (launcher != null) {
                launcher(hostActivity, intent)
            } else {
                hostActivity.startActivity(intent)
            }
            true
        }.getOrElse { err ->
            runCatching { Log.w("KiyomiCf", "Failed launching CloudflareWebViewActivity: ${err.message}") }
            false
        }

        if (!launched) {
            pendingChallenges.remove(challengeId)
            return false
        }

        val finished = runCatching {
            entry.latch.await(waitTimeoutSeconds, TimeUnit.SECONDS)
        }.getOrDefault(false)

        pendingChallenges.remove(challengeId)

        if (!finished || !entry.succeeded.get()) {
            return false
        }

        // Sync fresh cookies directly from WebView CookieManager into the interceptor's CookieJar
        val synced = cookieJar.syncFromWebView(request.url, entry.extraUrls)
        runCatching {
            Log.i("KiyomiCf", "Synced ${synced.size} cookie(s) from WebView into CookieJar for ${request.url.host}")
        }
        return true
    }

    private data class PendingChallenge(
        val latch: CountDownLatch,
        val succeeded: AtomicBoolean,
        val targetUrl: HttpUrl,
        val extraUrls: MutableList<HttpUrl> = mutableListOf()
    )

    companion object {
        private val pendingChallenges = ConcurrentHashMap<String, PendingChallenge>()
        private val activeCookieJarRef = AtomicReference<AndroidCookieJar?>(null)

        fun setActiveCookieJar(cookieJar: AndroidCookieJar?) {
            activeCookieJarRef.set(cookieJar)
        }

        fun getActiveCookieJar(): AndroidCookieJar? = activeCookieJarRef.get()

        fun createVerificationIntent(
            context: Context,
            url: String,
            sourceName: String = "",
            userAgent: String = KomaDns.USER_AGENT,
            referer: String = "",
            challengeId: String? = null,
            isManual: Boolean = true
        ): Intent {
            return Intent(context, CloudflareWebViewActivity::class.java).apply {
                putExtra(CloudflareWebViewActivity.EXTRA_URL, url)
                putExtra(CloudflareWebViewActivity.EXTRA_SOURCE_NAME, sourceName)
                putExtra(CloudflareWebViewActivity.EXTRA_USER_AGENT, userAgent)
                if (referer.isNotBlank()) {
                    putExtra(CloudflareWebViewActivity.EXTRA_REFERER, referer)
                }
                if (!challengeId.isNullOrBlank()) {
                    putExtra(CloudflareWebViewActivity.EXTRA_CHALLENGE_ID, challengeId)
                }
                putExtra(CloudflareWebViewActivity.EXTRA_IS_MANUAL, isManual)
            }
        }

        fun syncCookiesFromWebView(
            url: String,
            extraUrls: List<String> = emptyList(),
            fallbackCookieJar: AndroidCookieJar? = null
        ): List<Cookie> {
            val primaryHttpUrl = url.toHttpUrlOrNull() ?: return emptyList()
            val extraHttpUrls = extraUrls.mapNotNull { it.toHttpUrlOrNull() }
            val jar = activeCookieJarRef.get() ?: fallbackCookieJar ?: return emptyList()
            return jar.syncFromWebView(primaryHttpUrl, extraHttpUrls)
        }

        fun completeChallenge(
            challengeId: String?,
            succeeded: Boolean,
            finalUrl: String? = null
        ) {
            if (challengeId.isNullOrBlank()) return
            val pending = pendingChallenges[challengeId] ?: return
            finalUrl?.toHttpUrlOrNull()?.let { parsed ->
                synchronized(pending.extraUrls) {
                    pending.extraUrls.add(parsed)
                }
            }
            pending.succeeded.set(succeeded)
            pending.latch.countDown()
        }
    }
}
