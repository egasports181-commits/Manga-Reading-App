package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

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
    val errorSummary: String? = null,
    val tlsInfo: String? = null,
    val resolvedIp: String? = null
)

data class NetworkHealthSnapshot(
    val hasActiveNetwork: Boolean,
    val transportLabel: String,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val dnsMode: DnsMode
)

data class CallTlsMeta(
    @Volatile var resolvedIp: String? = null,
    @Volatile var tlsInfo: String? = null
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
    private val tracker: NetworkDiagnosticsTracker? = null,
    private val systemResolver: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
    private val dohResolver: ((String, DnsMode) -> List<InetAddress>)? = null
) : Dns {

    @Volatile
    var mode: DnsMode = DnsMode.DOH_AUTO_FALLBACK
        set(value) {
            if (field != value) {
                dohPreferredHosts.clear()
            }
            field = value
            tracker?.currentDnsMode = value
        }

    @Volatile
    var preferIpv4: Boolean = true

    private val dohPreferredHosts = ConcurrentHashMap.newKeySet<String>()

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun markHostForDohPreferred(hostname: String) {
        val norm = hostname.trim().lowercase()
        if (norm.isNotEmpty() && !isIpLiteral(norm)) {
            dohPreferredHosts.add(norm)
        }
    }

    fun isHostDohPreferred(hostname: String): Boolean =
        hostname.trim().lowercase() in dohPreferredHosts

    fun clearHostOverrides() {
        dohPreferredHosts.clear()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val trimmed = hostname.trim()
        if (isIpLiteral(trimmed)) {
            val cleaned = trimmed.removeSurrounding("[", "]")
            return listOf(InetAddress.getByName(cleaned))
        }

        val currentMode = mode
        if (currentMode == DnsMode.SYSTEM_DEFAULT) {
            return systemResolver(trimmed)
        }

        // Explicit DoH modes OR hosts where System DNS previously failed TLS verification
        val shouldTryDohFirst = currentMode == DnsMode.DOH_CLOUDFLARE ||
            currentMode == DnsMode.DOH_GOOGLE ||
            (currentMode == DnsMode.DOH_AUTO_FALLBACK && isHostDohPreferred(trimmed))

        if (shouldTryDohFirst) {
            val primaryProvider = if (currentMode == DnsMode.DOH_GOOGLE) DnsMode.DOH_GOOGLE else DnsMode.DOH_CLOUDFLARE
            val doh = resolveDohWithFallback(trimmed, primaryProvider)
            if (doh.isNotEmpty()) return orderAddresses(doh)
        }

        val rawSystem = runCatching { systemResolver(trimmed) }.getOrNull().orEmpty()
        val systemAddresses = filterValidAddresses(trimmed, rawSystem)

        if (systemAddresses.isNotEmpty() && currentMode == DnsMode.SYSTEM_IPV4_FIRST) {
            return orderAddresses(systemAddresses)
        }

        if (systemAddresses.isNotEmpty()) {
            val ordered = orderAddresses(systemAddresses)
            // If preferIpv4 is enabled and system DNS only returned IPv6 AAAA, try DoH A lookup as well
            if (currentMode == DnsMode.DOH_AUTO_FALLBACK && preferIpv4 && ordered.none { it is Inet4Address }) {
                val dohV4 = resolveDohWithFallback(trimmed, DnsMode.DOH_CLOUDFLARE)
                if (dohV4.isNotEmpty()) return orderAddresses((dohV4 + ordered).distinct())
            }
            return ordered
        }

        if (currentMode == DnsMode.DOH_AUTO_FALLBACK) {
            val doh = resolveDohWithFallback(trimmed, DnsMode.DOH_CLOUDFLARE)
            if (doh.isNotEmpty()) return orderAddresses(doh)
        }

        // Last resort: if all system results were filtered only for non-DoH mode, fall back
        if (rawSystem.isNotEmpty() && currentMode == DnsMode.SYSTEM_IPV4_FIRST) {
            return orderAddresses(rawSystem)
        }

        throw UnknownHostException("Unable to resolve host '$trimmed' via System DNS or DoH")
    }

    internal fun filterValidAddresses(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (!isPublicHostname(hostname)) return addresses
        return addresses.filterNot { isBogonForPublicHost(it) }
    }

    private fun resolveDohWithFallback(hostname: String, primary: DnsMode): List<InetAddress> {
        val providers = if (primary == DnsMode.DOH_GOOGLE) {
            listOf(DnsMode.DOH_GOOGLE, DnsMode.DOH_CLOUDFLARE)
        } else {
            listOf(DnsMode.DOH_CLOUDFLARE, DnsMode.DOH_GOOGLE)
        }
        for (provider in providers) {
            val resolved = runCatching {
                dohResolver?.invoke(hostname, provider) ?: resolveViaDoH(hostname, provider)
            }.onFailure { err ->
                safeLogWarn("DoH lookup via ${provider.name} failed for $hostname: ${NetworkErrorFormatter.describe(err)}")
            }.getOrNull().orEmpty()
            val clean = filterValidAddresses(hostname, resolved)
            if (clean.isNotEmpty()) return clean
        }
        return emptyList()
    }

    private fun orderAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (!preferIpv4) return addresses
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val ipv6 = addresses.filterIsInstance<Inet6Address>()
        return ipv4 + ipv6
    }

    private fun resolveViaDoH(hostname: String, provider: DnsMode): List<InetAddress> {
        val encoded = URLEncoder.encode(hostname, "UTF-8")
        val rrTypes = if (preferIpv4) listOf("A", "AAAA") else listOf("AAAA", "A")
        val endpoints = when (provider) {
            DnsMode.DOH_GOOGLE -> listOf(
                "https://8.8.8.8/resolve",
                "https://8.8.4.4/resolve",
                "https://dns.google/resolve"
            )
            else -> listOf(
                "https://1.1.1.1/dns-query",
                "https://1.0.0.1/dns-query",
                "https://cloudflare-dns.com/dns-query"
            )
        }

        val collected = mutableListOf<InetAddress>()
        var lastError: Throwable? = null
        for (base in endpoints) {
            for (rr in rrTypes) {
                val url = "$base?name=$encoded&type=$rr"
                val result = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-json")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                    bootstrapClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IOException("DoH HTTP ${resp.code}")
                        }
                        val body = resp.body?.string() ?: return@use emptyList()
                        parseDnsJsonAnswers(body)
                    }
                }
                result.onSuccess { addrs ->
                    collected.addAll(addrs)
                }.onFailure { err ->
                    lastError = err
                }
                if (rr == "A" && preferIpv4 && collected.any { it is Inet4Address }) {
                    return collected.distinct()
                }
            }
            if (collected.isNotEmpty()) return collected.distinct()
        }
        if (lastError != null) {
            val host = endpoints.first().substringAfter("://").substringBefore("/")
            tracker?.record(
                NetworkLogEntry(
                    host = host,
                    path = "/doh-query",
                    statusCode = null,
                    durationMs = 0L,
                    errorSummary = NetworkErrorFormatter.describe(lastError, host)
                )
            )
            throw lastError!!
        }
        return emptyList()
    }

    private fun parseDnsJsonAnswers(body: String): List<InetAddress> {
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

    companion object {
        const val USER_AGENT = "KomaMangaReader/2.0 (Android; Mobile)"
        private val IPV4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

        fun isIpLiteral(host: String): Boolean {
            val stripped = host.trim().removeSurrounding("[", "]")
            if (IPV4_REGEX.matches(stripped)) return true
            return stripped.contains(':')
        }

        fun isPublicHostname(host: String): Boolean {
            val lower = host.trim().lowercase()
            if (lower.isEmpty() || lower == "localhost" || isIpLiteral(lower)) return false
            if (lower.endsWith(".local") || lower.endsWith(".lan") || lower.endsWith(".internal")) return false
            return lower.contains('.')
        }

        fun isBogonForPublicHost(addr: InetAddress): Boolean {
            return addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
        }

        private fun safeLogWarn(msg: String) {
            runCatching { Log.w("KomaDns", msg) }
        }
    }
}

class KomaNetworkEventListener(
    private val tracker: NetworkDiagnosticsTracker,
    private val komaDns: KomaDns
) : EventListener() {

    private val callMeta = Collections.synchronizedMap(WeakHashMap<Call, CallTlsMeta>())

    fun consumeCallMeta(call: Call): CallTlsMeta? = callMeta.remove(call)

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val firstIp = inetAddressList.firstOrNull()?.hostAddress
        if (firstIp != null) {
            val meta = callMeta.getOrPut(call) { CallTlsMeta() }
            meta.resolvedIp = if (inetAddressList.size > 1) {
                "$firstIp (+${inetAddressList.size - 1})"
            } else {
                firstIp
            }
        }
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (handshake != null) {
            val tlsSummary = "${handshake.tlsVersion.javaName} • ${handshake.cipherSuite.javaName}"
            val meta = callMeta.getOrPut(call) { CallTlsMeta() }
            meta.tlsInfo = tlsSummary
            runCatching {
                Log.d(
                    "KomaTLS",
                    "TLS OK host=${call.request().url.host} ip=${meta.resolvedIp ?: "?"} $tlsSummary"
                )
            }
        }
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        val host = call.request().url.host
        val ip = inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostString
        val meta = callMeta.getOrPut(call) { CallTlsMeta() }
        meta.resolvedIp = "$ip:${inetSocketAddress.port}"

        if (NetworkErrorFormatter.isTlsVerificationFailure(ioe)) {
            if (komaDns.mode == DnsMode.DOH_AUTO_FALLBACK && !komaDns.isHostDohPreferred(host)) {
                komaDns.markHostForDohPreferred(host)
            }
            val causeSummary = NetworkErrorFormatter.describe(ioe, host)
            runCatching {
                Log.w(
                    "KomaTLS",
                    "TLS handshake failed host=$host ip=${meta.resolvedIp} cause=$causeSummary"
                )
            }
        } else {
            runCatching {
                Log.w(
                    "KomaNet",
                    "Connect failed host=$host ip=${meta.resolvedIp} error=${ioe.javaClass.simpleName}: ${ioe.message}"
                )
            }
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        if (NetworkErrorFormatter.isTlsVerificationFailure(ioe)) {
            val url = call.request().url
            val meta = callMeta[call]
            runCatching {
                Log.w(
                    "KomaTLS",
                    "Call failed TLS host=${url.host} path=${url.encodedPath} ip=${meta?.resolvedIp ?: "?"} detail=${NetworkErrorFormatter.describe(ioe, url.host)}"
                )
            }
        }
    }
}

class KomaNetworkInterceptor(
    private val tracker: NetworkDiagnosticsTracker,
    private val komaDns: KomaDns? = null,
    private val eventListener: KomaNetworkEventListener? = null
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
        val host = request.url.host
        val path = request.url.encodedPath
        val canAutoDohRetry = komaDns != null &&
            komaDns.mode == DnsMode.DOH_AUTO_FALLBACK &&
            (request.method.equals("GET", ignoreCase = true) || request.method.equals("HEAD", ignoreCase = true)) &&
            !KomaDns.isIpLiteral(host) &&
            !komaDns.isHostDohPreferred(host)

        val start = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            val meta = eventListener?.consumeCallMeta(chain.call())
            val elapsed = System.currentTimeMillis() - start
            tracker.record(
                NetworkLogEntry(
                    host = host,
                    path = path,
                    statusCode = response.code,
                    durationMs = elapsed,
                    errorSummary = if (!response.isSuccessful) "HTTP ${response.code}" else null,
                    tlsInfo = meta?.tlsInfo ?: response.handshake?.let {
                        "${it.tlsVersion.javaName} • ${it.cipherSuite.javaName}"
                    },
                    resolvedIp = meta?.resolvedIp
                )
            )
            return response
        } catch (t: Throwable) {
            val firstMeta = eventListener?.consumeCallMeta(chain.call())
            val elapsed = System.currentTimeMillis() - start
            val described = NetworkErrorFormatter.describe(t, host)
            tracker.record(
                NetworkLogEntry(
                    host = host,
                    path = path,
                    statusCode = null,
                    durationMs = elapsed,
                    errorSummary = described,
                    tlsInfo = firstMeta?.tlsInfo,
                    resolvedIp = firstMeta?.resolvedIp
                )
            )

            // If System DNS resolved an ISP/router block-page IP that broke TLS verification,
            // escalate this host to DoH and retry once using full strict TLS verification.
            if (canAutoDohRetry && NetworkErrorFormatter.isTlsVerificationFailure(t)) {
                komaDns!!.markHostForDohPreferred(host)
                val retryStart = System.currentTimeMillis()
                try {
                    val retryResponse = chain.proceed(request)
                    val retryMeta = eventListener?.consumeCallMeta(chain.call())
                    val retryElapsed = System.currentTimeMillis() - retryStart
                    tracker.record(
                        NetworkLogEntry(
                            host = host,
                            path = path,
                            statusCode = retryResponse.code,
                            durationMs = retryElapsed,
                            errorSummary = if (!retryResponse.isSuccessful) "HTTP ${retryResponse.code} (DoH retry)" else null,
                            tlsInfo = retryMeta?.tlsInfo ?: retryResponse.handshake?.let {
                                "${it.tlsVersion.javaName} • ${it.cipherSuite.javaName}"
                            },
                            resolvedIp = retryMeta?.resolvedIp
                        )
                    )
                    return retryResponse
                } catch (retryErr: Throwable) {
                    val retryMeta = eventListener?.consumeCallMeta(chain.call())
                    val retryElapsed = System.currentTimeMillis() - retryStart
                    tracker.record(
                        NetworkLogEntry(
                            host = host,
                            path = path,
                            statusCode = null,
                            durationMs = retryElapsed,
                            errorSummary = "${NetworkErrorFormatter.describe(retryErr, host)} (after DoH retry)",
                            tlsInfo = retryMeta?.tlsInfo,
                            resolvedIp = retryMeta?.resolvedIp
                        )
                    )
                    throw retryErr
                }
            }

            throw t
        }
    }
}

object NetworkErrorFormatter {

    fun isTlsVerificationFailure(error: Throwable?): Boolean {
        return unwrapChain(error).any {
            it is SSLHandshakeException ||
                it is SSLPeerUnverifiedException ||
                it is CertPathValidatorException ||
                it is CertificateExpiredException ||
                it is CertificateNotYetValidException
        }
    }

    fun describe(error: Throwable?, hostHint: String? = null): String {
        if (error == null) return "Unknown network error"
        val chain = unwrapChain(error)
        val hostPrefix = if (!hostHint.isNullOrBlank()) " for $hostHint" else ""

        val expired = chain.filterIsInstance<CertificateExpiredException>().firstOrNull()
        if (expired != null) {
            return "TLS/SSL certificate handshake failed$hostPrefix: server certificate expired or device clock is ahead (${expired.message ?: "check date/time"})."
        }

        val notYetValid = chain.filterIsInstance<CertificateNotYetValidException>().firstOrNull()
        if (notYetValid != null) {
            return "TLS/SSL certificate handshake failed$hostPrefix: certificate not yet valid — check device date/time (${notYetValid.message ?: "clock mismatch"})."
        }

        val peerUnverified = chain.filterIsInstance<SSLPeerUnverifiedException>().firstOrNull()
        if (peerUnverified != null) {
            return "TLS/SSL certificate handshake failed$hostPrefix: hostname verification failed (${peerUnverified.message ?: "possible ISP DNS hijack or captive portal"})."
        }

        val certPath = chain.filterIsInstance<CertPathValidatorException>().firstOrNull()
        if (certPath != null) {
            val raw = certPath.message.orEmpty()
            val detail = if (raw.contains("trust anchor", ignoreCase = true)) {
                "untrusted certificate authority (check VPN/proxy, ISP block page, or older Android root CA store)"
            } else {
                raw.ifBlank { "certificate chain validation failed" }
            }
            return "TLS/SSL certificate handshake failed$hostPrefix: $detail."
        }

        val protocolErr = chain.filterIsInstance<SSLProtocolException>().firstOrNull()
        if (protocolErr != null) {
            return "TLS/SSL certificate handshake failed$hostPrefix: protocol negotiation error (${protocolErr.message ?: "TLS version or cipher mismatch"})."
        }

        val handshake = chain.filterIsInstance<SSLHandshakeException>().firstOrNull()
        if (handshake != null) {
            val deepest = chain.lastOrNull { !it.message.isNullOrBlank() }?.message ?: handshake.message
            return "TLS/SSL certificate handshake failed$hostPrefix (${deepest ?: "check device date/time or network proxy"})."
        }

        val sslOther = chain.filterIsInstance<SSLException>().firstOrNull()
        if (sslOther != null) {
            return "TLS/SSL connection failed$hostPrefix (${sslOther.message ?: "secure socket error"})."
        }

        val unknownHost = chain.filterIsInstance<UnknownHostException>().firstOrNull()
        if (unknownHost != null) {
            return "DNS lookup failed for host (${unknownHost.message ?: hostHint ?: "unknown"}). Try Cloudflare/Google DoH in Settings -> Network."
        }

        val timeout = chain.filterIsInstance<SocketTimeoutException>().firstOrNull()
        if (timeout != null) {
            return "Connection timed out waiting for server response$hostPrefix."
        }

        val connect = chain.filterIsInstance<ConnectException>().firstOrNull()
        if (connect != null) {
            return "TCP connection refused or unreachable$hostPrefix (${connect.message ?: "check Wi-Fi/VPN"})."
        }

        val security = chain.filterIsInstance<SecurityException>().firstOrNull()
        if (security != null) {
            return security.message ?: "Security verification blocked this request."
        }

        val io = chain.filterIsInstance<IOException>().firstOrNull()
        if (io != null) {
            return io.message ?: "Network I/O error"
        }

        return error.message ?: error.javaClass.simpleName
    }

    private fun unwrapChain(error: Throwable?): List<Throwable> {
        if (error == null) return emptyList()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val list = mutableListOf<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16 && seen.add(current)) {
            list.add(current)
            current = current.cause
            depth++
        }
        return list
    }
}

