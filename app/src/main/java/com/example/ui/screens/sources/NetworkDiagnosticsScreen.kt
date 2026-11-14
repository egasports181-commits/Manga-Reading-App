package com.example.ui.screens.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AppContainer
import com.example.network.DnsMode
import com.example.network.NetworkErrorFormatter
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.theme.StatusCompleted
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOngoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.DateFormat
import java.util.Date

private data class EndpointProbeResult(
    val label: String,
    val url: String,
    val success: Boolean,
    val httpCode: Int? = null,
    val latencyMs: Long = 0L,
    val tlsInfo: String? = null,
    val error: String? = null
)

@Composable
fun NetworkDiagnosticsScreen(
    container: AppContainer,
    sourcesViewModel: SourcesViewModel,
    onBack: () -> Unit
) {
    val prefs by container.preferences.preferences.collectAsStateWithLifecycle(
        initialValue = com.example.data.preferences.UserPreferences()
    )
    val logs by container.networkDiagnostics.logs.collectAsStateWithLifecycle()
    val sourceState by sourcesViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var health by remember { mutableStateOf(container.networkDiagnostics.inspectConnectivity()) }
    val tlsCaps = remember { container.networkDiagnostics.inspectTlsCapabilities() }
    var probeResults by remember { mutableStateOf<List<EndpointProbeResult>>(emptyList()) }
    var isProbing by remember { mutableStateOf(false) }

    fun runConnectivityProbes() {
        scope.launch {
            isProbing = true
            health = container.networkDiagnostics.inspectConnectivity()
            val targets = listOf(
                "MangaDex API" to "https://api.mangadex.org/ping",
                "MangaDex Covers CDN" to "https://uploads.mangadex.org",
                "Raw GitHub (Extensions)" to "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
                "Cloudflare DoH" to "https://1.1.1.1/dns-query?name=api.mangadex.org&type=A"
            )
            probeResults = withContext(Dispatchers.IO) {
                targets.map { (label, url) ->
                    val start = System.currentTimeMillis()
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("Accept", if (url.contains("dns-query")) "application/dns-json" else "*/*")
                            .get()
                            .build()
                        container.httpClient.newCall(req).execute().use { resp ->
                            val tlsSummary = resp.handshake?.let {
                                "${it.tlsVersion.javaName} • ${it.cipherSuite.javaName}"
                            }
                            val isReachable = resp.isSuccessful || (url.contains("uploads.mangadex.org") && resp.code < 500)
                            EndpointProbeResult(
                                label = label,
                                url = url,
                                success = isReachable,
                                httpCode = resp.code,
                                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                                tlsInfo = tlsSummary,
                                error = if (isReachable) null else "HTTP ${resp.code}"
                            )
                        }
                    } catch (e: Exception) {
                        val hostHint = runCatching { java.net.URI(url).host }.getOrNull()
                        EndpointProbeResult(
                            label = label,
                            url = url,
                            success = false,
                            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                            error = NetworkErrorFormatter.describe(e, hostHint)
                        )
                    }
                }
            }
            isProbing = false
        }
    }

    Scaffold(
        topBar = {
            LiquidGlassTopBar(
                title = "Network & Repo Diagnostics",
                subtitle = "${health.transportLabel} • ${prefs.dnsMode.label}",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { container.networkDiagnostics.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        1.dp,
                        if (health.hasActiveNetwork) StatusOngoing.copy(alpha = 0.4f) else StatusError.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (health.hasActiveNetwork) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = if (health.hasActiveNetwork) StatusOngoing else StatusError
                            )
                            Text(
                                text = if (health.hasActiveNetwork) "Device Network Connected (${health.transportLabel})" else "No Active Internet Transport",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = "Validated: ${if (health.isValidated) "Yes" else "No"} • Metered: ${if (health.isMetered) "Yes" else "No"} • IPv4 Preferred: ${if (prefs.preferIpv4) "On" else "Off"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::runConnectivityProbes, enabled = !isProbing) {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isProbing) "Testing..." else "Test Live Endpoints")
                            }
                            OutlinedButton(onClick = { sourcesViewModel.refreshAllEnabledRepos(onlyFailed = true) }) {
                                Text("Retry Failed Repos")
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        1.dp,
                        if (tlsCaps.tls13Supported) StatusOngoing.copy(alpha = 0.35f) else StatusError.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (tlsCaps.tls13Supported) StatusOngoing else StatusError,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Client TLS Stack (${tlsCaps.providerName}${if (tlsCaps.usingConscrypt) " • Bundled" else ""})",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = "TLS 1.3: ${if (tlsCaps.tls13Supported) "Enabled" else "Unsupported"} • TLS 1.2: ${if (tlsCaps.tls12Supported) "Enabled" else "Unsupported"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tlsCaps.tls13Supported) StatusOngoing else StatusError
                        )
                        Text(
                            text = "Enabled protocols: ${tlsCaps.enabledProtocols.joinToString(", ").ifBlank { "None" }}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DNS Resolver & Carrier Bypass", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "If mobile carriers or Wi-Fi routers block raw GitHub or MangaDex DNS, switch to Cloudflare or Google DoH below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DnsMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = prefs.dnsMode == mode,
                                    onClick = {
                                        scope.launch { container.preferences.setDnsMode(mode) }
                                    }
                                )
                                Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Prefer IPv4 addresses (fixes broken IPv6 carrier routes)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(
                                checked = prefs.preferIpv4,
                                onCheckedChange = { scope.launch { container.preferences.setPreferIpv4(it) } }
                            )
                        }
                    }
                }
            }

            if (probeResults.isNotEmpty()) {
                item { Text("Endpoint Probe Results", style = MaterialTheme.typography.titleSmall) }
                items(probeResults, key = { it.url }) { probe ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(probe.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (probe.success) "OK • HTTP ${probe.httpCode} • ${probe.latencyMs}ms" else "FAILED • ${probe.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (probe.success) StatusOngoing else StatusError,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            probe.tlsInfo?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            probe.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = StatusError)
                            }
                        }
                    }
                }
            }

            item {
                Text("Repository Sync Health (${sourceState.repos.size})", style = MaterialTheme.typography.titleSmall)
            }
            items(sourceState.repos, key = { "diag_repo_${it.id}" }) { repo ->
                val hasErr = !repo.lastError.isNullOrBlank()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                repo.displayName.ifBlank { repo.name },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    !repo.isEnabled -> "Disabled"
                                    hasErr -> "HTTP ${repo.lastHttpStatus} • Error"
                                    repo.lastSuccessAt > 0L -> "${repo.extensionCount} exts • OK"
                                    else -> "Pending Sync"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !repo.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                    hasErr -> StatusError
                                    repo.lastSuccessAt > 0L -> StatusOngoing
                                    else -> StatusCompleted
                                }
                            )
                        }
                        Text(
                            "${repo.repoFormat} • ${repo.indexUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        repo.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = StatusError)
                        }
                    }
                }
            }

            item {
                Text("Recent HTTP Requests (${logs.size})", style = MaterialTheme.typography.titleSmall)
            }
            if (logs.isEmpty()) {
                item {
                    Text(
                        "No HTTP requests recorded yet during this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(logs, key = { "${it.timestamp}_${it.host}_${it.path}" }) { entry ->
                    val ok = entry.errorSummary == null
                    val timeStr = remember(entry.timestamp) {
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestamp))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${entry.host}${entry.path}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${entry.statusCode ?: "ERR"} • ${entry.durationMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (ok) StatusOngoing else StatusError
                                )
                            }
                            if (!entry.tlsInfo.isNullOrBlank() || !entry.resolvedIp.isNullOrBlank()) {
                                Text(
                                    text = listOfNotNull(entry.resolvedIp, entry.tlsInfo).joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = entry.errorSummary ?: "Completed at $timeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else StatusError
                            )
                        }
                    }
                }
            }
        }
    }
}
