package com.example.ui.screens.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.LiquidGlassTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    viewModel: MoreViewModel,
    onOpenDownloads: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenTracking: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPrivacyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LiquidGlassTopBar(title = "More")
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Incognito Reading Mode", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Pause reading history and chapter progress logging",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.prefs.incognitoMode,
                            onCheckedChange = viewModel::setIncognitoMode
                        )
                    }
                }
            }

            item {
                MoreHubItem(
                    icon = Icons.Default.Download,
                    title = "Download Manager",
                    subtitle = "${state.downloadQueue.size} in queue • ${state.stats.downloadedChaptersCount} offline chapters",
                    onClick = onOpenDownloads,
                    tag = "more_downloads_item"
                )
            }
            item {
                MoreHubItem(
                    icon = Icons.Default.BarChart,
                    title = "Reading Statistics",
                    subtitle = "${state.stats.totalChaptersRead} chapters read • ${state.stats.dailyStreakDays} day streak",
                    onClick = onOpenStats,
                    tag = "more_stats_item"
                )
            }
            item {
                MoreHubItem(
                    icon = Icons.Default.Sync,
                    title = "Tracking Services",
                    subtitle = "AniList, MyAnimeList, Kitsu, MangaUpdates, MangaBaka",
                    onClick = onOpenTracking,
                    tag = "more_tracking_item"
                )
            }
            item {
                MoreHubItem(
                    icon = Icons.Default.Backup,
                    title = "Backup & Restore",
                    subtitle = "Export/import versioned JSON backups via Storage Access Framework",
                    onClick = onOpenBackup,
                    tag = "more_backup_item"
                )
            }
            item {
                MoreHubItem(
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    subtitle = "Theme, AMOLED, Reader defaults, Library, Downloads, Extensions & Security",
                    onClick = onOpenSettings,
                    tag = "more_settings_item"
                )
            }
            item {
                MoreHubItem(
                    icon = Icons.Default.PrivacyTip,
                    title = "Security & Privacy Policy",
                    subtitle = "Sandboxed source model, local-first storage, zero analytics",
                    onClick = { showPrivacyDialog = true }
                )
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Koma Privacy & Security") },
            text = {
                Text(
                    "• Local-First Storage: Your library, reading history, categories, and preferences stay in on-device Room & DataStore storage.\n\n" +
                        "• Untrusted Extensions Protection: Koma validates HTTPS repository URLs and SHA-256 digests, never silently installs APKs, and never executes arbitrary remote JavaScript.\n\n" +
                        "• Zero Telemetry: Koma collects no analytics or usage tracking. Network calls occur only to sources or trackers you explicitly use.\n\n" +
                        "• Authorized Content Only: Koma never circumvents paywalls, DRM, or authentication protections.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MoreHubItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String = ""
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier)
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        )
    }
}
