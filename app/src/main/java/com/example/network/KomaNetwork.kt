package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

enum class DnsMode(val id: String, val label: String) {
    DOH_AUTO_FALLBACK("doh_auto", "IPv4 + DoH Auto Fallback"),
    DOH_CLOUDFLARE("doh_cloudflare", "Cloudflare DoH (1.1.1.1)"),
    DOH_GOOGLE("doh_google", "Google DoH (8.8.8.8)"),
    SYSTEM_IPV4_FIRST("system_ipv4", "System DNS (IPv4 First)"),
    SYSTEM_DEFAULT("system_default", "System Default");

    companion object {
        fun fromId(id: String): DnsMode = entries.firstOrNull { it.id == id } ?: DOH_AUTO_FALLBACK
    }
}

data class NetworkLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val host: String,
    val path: String,
    val statusCode: Int? = null,
    val durationMs: Long = 0L,
    val errorSummary: String? = null
)

data class NetworkHealthSnapshot(
    val hasActiveNetwork: Boolean,
    val transportLabel: String,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val dnsMode: DnsMode
)

class NetworkDiagnosticsTracker(private val context: Context) {
    private val _logs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val logs: StateFlow<List<NetworkLogEntry>> = _logs.asStateFlow()

    @Volatile
    var currentDnsMode: DnsMode = DnsMode.DOH_AUTO_FALLBACK

    fun record(entry: NetworkLogEntry) {
        synchronized(this) {
            val updated = (listOf(entry) + _logs.value).take(40)
            _logs.value = updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun inspectConnectivity(): NetworkHealthSnapshot {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            val transport = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val metered = cm?.isActiveNetworkMetered == true
            NetworkHealthSnapshot(
                hasActiveNetwork = caps != null && internet,
                transportLabel = transport,
                isValidated = validated,
                isMetered = metered,
                dnsMode = currentDnsMode
            )
        }.getOrElse {
            NetworkHealthSnapshot(
                hasActiveNetwork = false,
                transportLabel = "Unknown",
                isValidated = false,
                isMetered = false,
                dnsMode = currentDnsMode
            )
        }
    }
}

class KomaDns(
    private val tracker: NetworkDiagnosticsTracker
) : Dns {

    @Volatile
    var mode: DnsMode = DnsMode.DOH_AUTO_FALLBACK
        set(value) {
            field = value
            tracker.currentDnsMode = value
        }

    @Volatile
    var preferIpv4: Boolean = true

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val currentMode = mode
        if (currentMode == DnsMode.SYSTEM_DEFAULT) {
            return Dns.SYSTEM.lookup(hostname)
        }

        if (currentMode == DnsMode.DOH_CLOUDFLARE || currentMode == DnsMode.DOH_GOOGLE) {
            val doh = runCatching {
                resolveViaDoH(
                    hostname = hostname,
                    provider = currentMode
                )
            }.getOrNull().orEmpty()
            if (doh.isNotEmpty()) return orderAddresses(doh)
        }

        val systemAddresses = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull().orEmpty()
        if (systemAddresses.isNotEmpty() && currentMode == DnsMode.SYSTEM_IPV4_FIRST) {
            return orderAddresses(systemAddresses)
        }

        if (systemAddresses.isNotEmpty()) {
            val ordered = orderAddresses(systemAddresses)
            // If preferIpv4 is enabled and system DNS only returned IPv6 AAAA, try DoH A lookup as well
            if (currentMode == DnsMode.DOH_AUTO_FALLBACK && preferIpv4 && ordered.none { it is Inet4Address }) {
                val dohV4 = runCatching { resolveViaDoH(hostname, DnsMode.DOH_CLOUDFLARE) }.getOrNull().orEmpty()
                if (dohV4.isNotEmpty()) return orderAddresses(dohV4 + ordered).distinct()
            }
            return ordered
        }

        if (currentMode == DnsMode.DOH_AUTO_FALLBACK) {
            val doh = runCatching { resolveViaDoH(hostname, DnsMode.DOH_CLOUDFLARE) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { resolveViaDoH(hostname, DnsMode.DOH_GOOGLE) }.getOrNull().orEmpty()
            if (doh.isNotEmpty()) return orderAddresses(doh)
        }

        throw UnknownHostException("Unable to resolve host '$hostname' via System DNS or DoH")
    }

    private fun orderAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (!preferIpv4) return addresses
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val ipv6 = addresses.filterIsInstance<Inet6Address>()
        return ipv4 + ipv6
    }

    private fun resolveViaDoH(hostname: String, provider: DnsMode): List<InetAddress> {
        val endpoint = when (provider) {
            DnsMode.DOH_GOOGLE -> "https://8.8.8.8/resolve?name=$hostname&type=A"
            else -> "https://1.1.1.1/dns-query?name=$hostname&type=A"
        }
        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/dns-json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        bootstrapClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            return buildList {
                for (i in 0 until answers.length()) {
                    val ans = answers.optJSONObject(i) ?: continue
                    val type = ans.optInt("type", 0)
                    val data = ans.optString("data").trim()
                    if ((type == 1 || type == 28) && data.isNotBlank()) {
                        runCatching { InetAddress.getByName(data) }.getOrNull()?.let { add(it) }
                    }
                }
            }
        }
    }

    companion object {
        const val USER_AGENT = "KomaMangaReader/2.0 (Android; Mobile)"
    }
}

class KomaNetworkInterceptor(
    private val tracker: NetworkDiagnosticsTracker
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", KomaDns.USER_AGENT)
        }
        if (original.header("Accept").isNullOrBlank()) {
            builder.header("Accept", "*/*")
        }
        val request = builder.build()
        val start = System.currentTimeMillis()
        return try {
            val response = chain.proceed(request)
            val elapsed = System.currentTimeMillis() - start
            tracker.record(
                NetworkLogEntry(
                    host = request.url.host,
                    path = request.url.encodedPath,
                    statusCode = response.code,
                    durationMs = elapsed,
                    errorSummary = if (!response.isSuccessful) "HTTP ${response.code}" else null
                )
            )
            response
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            tracker.record(
                NetworkLogEntry(
                    host = request.url.host,
                    path = request.url.encodedPath,
                    statusCode = null,
                    durationMs = elapsed,
                    errorSummary = NetworkErrorFormatter.describe(t)
                )
            )
            throw t
        }
    }
}

object NetworkErrorFormatter {
    fun describe(error: Throwable?): String {
        if (error == null) return "Unknown network error"
        return when (error) {
            is UnknownHostException ->
                "DNS lookup failed for host (${error.message ?: "unknown"}). Try Cloudflare/Google DoH in Settings -> Network."
            is SSLHandshakeException ->
                "TLS/SSL certificate handshake failed (${error.message ?: "check device date/time or network proxy"})."
            is SocketTimeoutException ->
                "Connection timed out waiting for server response."
            is ConnectException ->
                "TCP connection refused or unreachable (${error.message ?: "check Wi-Fi/VPN"})."
            is SecurityException ->
                error.message ?: "Security verification blocked this request."
            is IOException ->
                error.message ?: "Network I/O error"
            else ->
                error.message ?: error.javaClass.simpleName
        }
    }
}
