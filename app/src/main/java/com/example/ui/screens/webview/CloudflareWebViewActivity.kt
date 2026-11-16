package com.example.ui.screens.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.network.CloudflareChallengeCoordinator
import com.example.network.KomaDns
import com.example.ui.theme.KomaTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * In-app WebView verification screen modeled on Mihon's WebView flow.
 *
 * Allows users to complete interactive Cloudflare challenges (including manual CAPTCHA / Turnstile)
 * under strict TLS (`handler.cancel()` on SSL errors) using the exact same `User-Agent` as OkHttp,
 * then synchronizes resulting cookies from Android's [CookieManager] directly into Kiyomi's shared
 * [eu.kanade.tachiyomi.network.AndroidCookieJar].
 */
class CloudflareWebViewActivity : ComponentActivity() {

    private var webViewRef: WebView? = null
    private var completedHandled = false

    private var targetUrl: String = ""
    private var sourceName: String = ""
    private var effectiveUserAgent: String = KomaDns.USER_AGENT
    private var referer: String = ""
    private var challengeId: String? = null
    private var initialCfClearance: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        targetUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME)?.trim().orEmpty()
        effectiveUserAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            ?.takeIf { it.isNotBlank() }
            ?: KomaDns.USER_AGENT
        referer = intent.getStringExtra(EXTRA_REFERER)?.trim().orEmpty()
        challengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID)
        val isManual = intent.getBooleanExtra(EXTRA_IS_MANUAL, true)

        val parsedInitial = targetUrl.toHttpUrlOrNull()
        if (parsedInitial == null || (!parsedInitial.isHttps && parsedInitial.scheme != "http")) {
            cancelAndFinish()
            return
        }

        if (isManual) {
            runCatching {
                CloudflareChallengeCoordinator.getActiveCookieJar()
                    ?.remove(parsedInitial, listOf("cf_clearance"), maxAge = 0)
            }
        }
        initialCfClearance = extractCookieValue(
            runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull(),
            "cf_clearance"
        )

        setContent {
            KomaTheme {
                CloudflareVerificationContent()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CloudflareVerificationContent() {
        val parsedHost = remember(targetUrl) { targetUrl.toHttpUrlOrNull()?.host ?: targetUrl }
        val displayTitle = remember(sourceName, parsedHost) {
            sourceName.takeIf { it.isNotBlank() } ?: parsedHost
        }
        var currentUrl by remember { mutableStateOf(targetUrl) }
        var isLoading by remember { mutableStateOf(true) }
        var loadProgress by remember { mutableIntStateOf(0) }
        var sslErrorMessage by remember { mutableStateOf<String?>(null) }
        var statusMessage by remember {
            mutableStateOf("Complete the verification check below, then tap Done to retry.")
        }

        BackHandler {
            val wv = webViewRef
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                cancelAndFinish()
            }
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = ::cancelAndFinish,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("cf_webview_close")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close verification")
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = "Cloudflare Verification",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "$displayTitle • ${currentUrl.toHttpUrlOrNull()?.host ?: parsedHost}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    sslErrorMessage = null
                                    webViewRef?.reload()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("cf_webview_reload")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload page")
                            }
                            FilledTonalButton(
                                onClick = { syncAndComplete(currentUrl) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .minimumInteractiveComponentSize()
                                    .testTag("cf_webview_done")
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Done", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { (loadProgress.coerceIn(5, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (sslErrorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = sslErrorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            configureWebView(
                                webView = this,
                                onProgress = { progress -> loadProgress = progress },
                                onLoadingChanged = { loading, url ->
                                    isLoading = loading
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                },
                                onSslBlocked = { reason ->
                                    sslErrorMessage = reason
                                    statusMessage = reason
                                },
                                onStatusHint = { hint -> statusMessage = hint }
                            )
                            val initialHeaders = buildMap {
                                if (referer.isNotBlank()) {
                                    put("Referer", referer)
                                }
                            }
                            if (initialHeaders.isNotEmpty()) {
                                loadUrl(targetUrl, initialHeaders)
                            } else {
                                loadUrl(targetUrl)
                            }
                        }
                    }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(
        webView: WebView,
        onProgress: (Int) -> Unit,
        onLoadingChanged: (Boolean, String?) -> Unit,
        onSslBlocked: (String) -> Unit,
        onStatusHint: (String) -> Unit
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = effectiveUserAgent
        }

        runCatching {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadingChanged(true, url)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Strict TLS policy: never bypass certificate validation
                handler?.cancel()
                onSslBlocked("TLS/SSL certificate validation failed (code ${error?.primaryError ?: -1}). Connection blocked.")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoadingChanged(false, url)
                val activeUrl = url?.takeIf { it.isNotBlank() } ?: targetUrl
                val currentClearance = extractCookieValue(
                    runCatching { CookieManager.getInstance().getCookie(activeUrl) }.getOrNull(),
                    "cf_clearance"
                ) ?: extractCookieValue(
                    runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull(),
                    "cf_clearance"
                )

                val hasNewClearance = !currentClearance.isNullOrBlank() &&
                    currentClearance != initialCfClearance

                if (hasNewClearance && view != null) {
                    onStatusHint("Clearance cookie detected — verifying page state...")
                    view.evaluateJavascript("document.title") { rawTitle ->
                        val title = rawTitle.orEmpty().trim('"').lowercase()
                        val stillChallengeTitle = title.contains("just a moment") ||
                            title.contains("attention required") ||
                            title.contains("one more step")
                        if (!stillChallengeTitle) {
                            syncAndComplete(activeUrl)
                        } else {
                            onStatusHint("Complete any remaining check below, then tap Done.")
                        }
                    }
                }
            }
        }
    }

    private fun syncAndComplete(currentUrl: String) {
        if (completedHandled) return
        completedHandled = true

        val extras = buildList {
            if (currentUrl.isNotBlank() && currentUrl != targetUrl) {
                add(currentUrl)
            }
        }
        CloudflareChallengeCoordinator.syncCookiesFromWebView(
            url = targetUrl,
            extraUrls = extras
        )
        CloudflareChallengeCoordinator.completeChallenge(
            challengeId = challengeId,
            succeeded = true,
            finalUrl = currentUrl
        )
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun cancelAndFinish() {
        if (completedHandled) return
        completedHandled = true
        CloudflareChallengeCoordinator.completeChallenge(
            challengeId = challengeId,
            succeeded = false,
            finalUrl = null
        )
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (!completedHandled) {
            completedHandled = true
            CloudflareChallengeCoordinator.completeChallenge(
                challengeId = challengeId,
                succeeded = false,
                finalUrl = null
            )
        }
        webViewRef?.let { wv ->
            runCatching {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        webViewRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SOURCE_NAME = "extra_source_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_CHALLENGE_ID = "extra_challenge_id"
        const val EXTRA_IS_MANUAL = "extra_is_manual"

        internal fun extractCookieValue(rawCookieHeader: String?, name: String): String? {
            if (rawCookieHeader.isNullOrBlank()) return null
            return rawCookieHeader.split(";")
                .asSequence()
                .map { it.trim() }
                .firstOrNull { part ->
                    val eqIdx = part.indexOf('=')
                    eqIdx > 0 && part.substring(0, eqIdx).trim().equals(name, ignoreCase = true)
                }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}
