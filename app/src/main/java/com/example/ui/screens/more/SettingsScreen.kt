package com.example.ui.screens.more

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.*
import com.example.network.DnsMode
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaElevatedCard
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.DaylightBg
import com.example.ui.theme.DaylightSurfaceLowest
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurfaceHigh
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
                    KomaCircularGlassButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                title = "Settings",
                subtitle = "Theme, reader & system preferences"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                ),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.statusMessage?.let { msg ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        )
                    }
                }
            }

            // General & Appearance
            item { SectionHeader("General & Appearance") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                ThemePreviewCard(
                                    mode = mode,
                                    selected = prefs.themeMode == mode,
                                    accent = prefs.accentPreset.primary,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text("Accent Color", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AccentPreset.entries.forEach { preset ->
                                AccentSwatchPill(
                                    preset = preset,
                                    selected = prefs.accentPreset == preset,
                                    onClick = { viewModel.setAccentPreset(preset) }
                                )
                            }
                        }

                        Text("App Language", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("en" to "English", "ja" to "日本語", "es" to "Español").forEach { (code, label) ->
                                FilterChip(
                                    selected = prefs.appLanguage == code,
                                    onClick = { viewModel.setAppLanguage(code) },
                                    label = { Text(label) },
                                    shape = CircleShape
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsSwitch("AMOLED Pure Black Dark Theme", prefs.amoledBlack, viewModel::setAmoledBlack)
                        SettingsSwitch("Material You Dynamic Color (Android 12+)", prefs.dynamicColor, viewModel::setDynamicColor)
                        SettingsSwitch("Demo Mode (Bundled Sample Catalog)", prefs.demoModeEnabled, viewModel::setDemoMode)
                    }
                }
            }

            // Reader Defaults
            item { SectionHeader("Reader Defaults") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Default Reading Mode", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReadingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = prefs.defaultReadingMode == mode,
                                    onClick = { viewModel.setDefaultReadingMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape
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
                                    label = { Text(sc.label) },
                                    shape = CircleShape
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
                                    label = { Text(bg.label) },
                                    shape = CircleShape
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsSwitch("Page Transitions", prefs.pageTransitions, viewModel::setPageTransitions)
                        SettingsSwitch("Tap Zones Navigation", prefs.tapZonesEnabled, viewModel::setTapZones)
                        SettingsSwitch("Volume Button Navigation", prefs.volumeKeyNavigation, viewModel::setVolumeNav)
                        SettingsSwitch("Keep Screen Awake", prefs.keepScreenAwake, viewModel::setKeepAwake)
                    }
                }
            }

            // Library
            item { SectionHeader("Library & Updates") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Automatic Update Check Interval", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Manual", 6 to "6h", 12 to "12h", 24 to "24h").forEach { (hrs, lbl) ->
                                FilterChip(
                                    selected = prefs.autoCheckUpdatesHours == hrs,
                                    onClick = { viewModel.setAutoUpdateHours(hrs) },
                                    label = { Text(lbl) },
                                    shape = CircleShape
                                )
                            }
                        }
                        SettingsSwitch("Update Notifications", prefs.updateNotificationsEnabled, viewModel::setUpdateNotifs)
                    }
                }
            }

            // Downloads
            item { SectionHeader("Downloads") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Custom Download Location (SAF)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    prefs.safDownloadTreeUri.ifBlank { "App-private offline directory (default)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { downloadDirLauncher.launch(null) },
                                shape = CircleShape
                            ) { Text("Choose") }
                        }
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsSwitch("Wi-Fi Only Downloads", prefs.wifiOnlyDownloads, viewModel::setWifiOnly)
                        SettingsSwitch("Auto-Download New Chapters", prefs.autoDownloadNewChapters, viewModel::setAutoDownloadNew)
                        SettingsSwitch("Auto-Cleanup Read Chapters", prefs.autoCleanupReadChapters, viewModel::setAutoCleanupRead)
                    }
                }
            }

            // Network & DNS
            item { SectionHeader("Network & DNS") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DNS Resolution Mode", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DnsMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = prefs.dnsMode == mode,
                                    onClick = { viewModel.setDnsMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape
                                )
                            }
                        }
                        SettingsSwitch("Prefer IPv4 First (Fixes Broken Mobile IPv6 Routes)", prefs.preferIpv4, viewModel::setPreferIpv4)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsActionRow(
                            title = "Network & Repository Diagnostics",
                            subtitle = "Test connectivity, inspect DoH/DNS status, and view HTTP logs",
                            onClick = onOpenDiagnostics
                        )
                    }
                }
            }

            // Extensions & Security
            item { SectionHeader("Extensions & Privacy") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSwitch("Auto-Update Trusted Koma Extensions", prefs.autoUpdateExtensions, viewModel::setAutoUpdateExtensions)
                        SettingsSwitch("Incognito Reading Mode", prefs.incognitoMode, viewModel::setIncognitoMode)
                    }
                }
            }

            // Advanced
            item { SectionHeader("Advanced & Diagnostics") }
            item {
                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clear Image Cache", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Current cache size: ${state.imageCacheBytes / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = viewModel::clearImageCache, shape = CircleShape) { Text("Clear") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsActionRow(
                            title = "Clear Reading History",
                            subtitle = "Remove logged chapter timestamps",
                            onClick = { viewModel.clearReadingHistory() }
                        )
                        SettingsActionRow(
                            title = "Clear Search History",
                            subtitle = "Clear recent global search queries",
                            onClick = { viewModel.clearSearchHistory() }
                        )
                        SettingsActionRow(
                            title = "Backup & Restore",
                            subtitle = "Export or import portable JSON backup",
                            onClick = onOpenBackup
                        )
                        SettingsActionRow(
                            title = "Export Diagnostics",
                            subtitle = "Inspect local database, cache, and queue telemetry",
                            onClick = { viewModel.exportDiagnostics() }
                        )
                    }
                }
            }
        }
    }

    state.diagnosticsText?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDiagnostics,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Diagnostics Summary") },
            text = { Text(report, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDiagnostics) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ThemePreviewCard(
    mode: ThemeMode,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgTop = when (mode) {
        ThemeMode.DARK -> ObsidianBg
        ThemeMode.LIGHT -> DaylightBg
        ThemeMode.SYSTEM -> ObsidianBg
    }
    val bgBottom = when (mode) {
        ThemeMode.DARK -> ObsidianSurfaceHigh
        ThemeMode.LIGHT -> DaylightSurfaceLowest
        ThemeMode.SYSTEM -> DaylightBg
    }
    val borderColor = if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (selected) 2.dp else 0.75.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(bgTop, bgBottom)))
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .align(Alignment.BottomStart)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = accent,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.TopEnd)
                    )
                }
            }
            Text(
                text = when (mode) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.DARK -> "Dark"
                    ThemeMode.LIGHT -> "Light"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AccentSwatchPill(
    preset: AccentPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) preset.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 0.75.dp,
            color = if (selected) preset.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(preset.primary)
                    .then(
                        if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier
                    )
            )
            Text(
                text = preset.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
