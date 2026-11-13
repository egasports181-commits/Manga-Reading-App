package com.example.ui.screens.more

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.*
import com.example.network.DnsMode
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MoreViewModel,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current

    val downloadDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setSafDownloadUri(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            LiquidGlassTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = "Settings"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.statusMessage?.let { msg ->
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }

            // General & Appearance
            item { SectionHeader("General & Appearance") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    Text("Accent Color", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AccentPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = prefs.accentPreset == preset,
                                onClick = { viewModel.setAccentPreset(preset) },
                                label = { Text(preset.label) }
                            )
                        }
                    }

                    Text("App Language", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("en" to "English", "ja" to "日本語", "es" to "Español").forEach { (code, label) ->
                            FilterChip(
                                selected = prefs.appLanguage == code,
                                onClick = { viewModel.setAppLanguage(code) },
                                label = { Text(label) }
                            )
                        }
                    }

                    SettingsSwitch("AMOLED Pure Black Dark Theme", prefs.amoledBlack, viewModel::setAmoledBlack)
                    SettingsSwitch("Material You Dynamic Color (Android 12+)", prefs.dynamicColor, viewModel::setDynamicColor)
                    SettingsSwitch("Demo Mode (Bundled Sample Catalog)", prefs.demoModeEnabled, viewModel::setDemoMode)
                }
            }

            // Reader Defaults
            item { SectionHeader("Reader Defaults") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Default Reading Mode", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReadingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.defaultReadingMode == mode,
                                onClick = { viewModel.setDefaultReadingMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text("Default Zoom / Scale", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ImageScaleMode.entries.forEach { sc ->
                            FilterChip(
                                selected = prefs.defaultScaleMode == sc,
                                onClick = { viewModel.setDefaultScaleMode(sc) },
                                label = { Text(sc.label) }
                            )
                        }
                    }
                    Text("Reader Background", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderBackground.entries.forEach { bg ->
                            FilterChip(
                                selected = prefs.readerBackground == bg,
                                onClick = { viewModel.setReaderBg(bg) },
                                label = { Text(bg.label) }
                            )
                        }
                    }
                    SettingsSwitch("Page Transitions", prefs.pageTransitions, viewModel::setPageTransitions)
                    SettingsSwitch("Tap Zones Navigation", prefs.tapZonesEnabled, viewModel::setTapZones)
                    SettingsSwitch("Volume Button Navigation", prefs.volumeKeyNavigation, viewModel::setVolumeNav)
                    SettingsSwitch("Keep Screen Awake", prefs.keepScreenAwake, viewModel::setKeepAwake)
                }
            }

            // Library
            item { SectionHeader("Library & Updates") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Automatic Update Check Interval", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Manual", 6 to "6h", 12 to "12h", 24 to "24h").forEach { (hrs, lbl) ->
                            FilterChip(
                                selected = prefs.autoCheckUpdatesHours == hrs,
                                onClick = { viewModel.setAutoUpdateHours(hrs) },
                                label = { Text(lbl) }
                            )
                        }
                    }
                    SettingsSwitch("Update Notifications", prefs.updateNotificationsEnabled, viewModel::setUpdateNotifs)
                }
            }

            // Downloads
            item { SectionHeader("Downloads") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListItem(
                        headlineContent = { Text("Custom Download Location (SAF)") },
                        supportingContent = {
                            Text(prefs.safDownloadTreeUri.ifBlank { "App-private offline directory (default)" })
                        },
                        trailingContent = {
                            OutlinedButton(onClick = { downloadDirLauncher.launch(null) }) { Text("Choose") }
                        }
                    )
                    Text("Concurrent Downloads: ${prefs.concurrentDownloads}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = prefs.concurrentDownloads.toFloat(),
                        onValueChange = { viewModel.setConcurrentDownloads(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    Text("Automatic Download Retries: ${prefs.downloadMaxRetries}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = prefs.downloadMaxRetries.toFloat(),
                        onValueChange = { viewModel.setDownloadMaxRetries(it.toInt()) },
                        valueRange = 0f..5f,
                        steps = 4
                    )
                    SettingsSwitch("Wi-Fi Only Downloads", prefs.wifiOnlyDownloads, viewModel::setWifiOnly)
                    SettingsSwitch("Auto-Download New Chapters", prefs.autoDownloadNewChapters, viewModel::setAutoDownloadNew)
                    SettingsSwitch("Auto-Cleanup Read Chapters", prefs.autoCleanupReadChapters, viewModel::setAutoCleanupRead)
                }
            }

            // Network & DNS
            item { SectionHeader("Network & DNS") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DNS Resolution Mode", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DnsMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.dnsMode == mode,
                                onClick = { viewModel.setDnsMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    SettingsSwitch("Prefer IPv4 First (Fixes Broken Mobile IPv6 Routes)", prefs.preferIpv4, viewModel::setPreferIpv4)
                    ListItem(
                        headlineContent = { Text("Network & Repository Diagnostics") },
                        supportingContent = { Text("Test connectivity, inspect DoH/DNS status, and view HTTP logs") },
                        modifier = Modifier.clickable(onClick = onOpenDiagnostics)
                    )
                }
            }

            // Extensions & Security
            item { SectionHeader("Extensions & Privacy") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSwitch("Auto-Update Trusted Koma Extensions", prefs.autoUpdateExtensions, viewModel::setAutoUpdateExtensions)
                    SettingsSwitch("Incognito Reading Mode", prefs.incognitoMode, viewModel::setIncognitoMode)
                }
            }

            // Advanced
            item { SectionHeader("Advanced & Diagnostics") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListItem(
                        headlineContent = { Text("Clear Image Cache") },
                        supportingContent = { Text("Current cache size: ${state.imageCacheBytes / 1024} KB") },
                        trailingContent = {
                            OutlinedButton(onClick = viewModel::clearImageCache) { Text("Clear") }
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Clear Reading History") },
                        modifier = Modifier.clickable { viewModel.clearReadingHistory() }
                    )
                    ListItem(
                        headlineContent = { Text("Clear Search History") },
                        modifier = Modifier.clickable { viewModel.clearSearchHistory() }
                    )
                    ListItem(
                        headlineContent = { Text("Backup & Restore") },
                        modifier = Modifier.clickable(onClick = onOpenBackup)
                    )
                    ListItem(
                        headlineContent = { Text("Export Diagnostics") },
                        supportingContent = { Text("Inspect local database, cache, and queue telemetry") },
                        modifier = Modifier.clickable { viewModel.exportDiagnostics() }
                    )
                }
            }
        }
    }

    state.diagnosticsText?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDiagnostics,
            title = { Text("Diagnostics Summary") },
            text = { Text(report, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDiagnostics) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
