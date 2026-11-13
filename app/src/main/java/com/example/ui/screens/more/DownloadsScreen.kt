package com.example.ui.screens.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.KomaEmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: MoreViewModel,
    onBack: () -> Unit,
    onOpenReader: (mangaId: String, chapterId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Download Manager") },
                actions = {
                    IconButton(onClick = viewModel::cleanupReadDownloads) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Clean up read downloads")
                    }
                    IconButton(onClick = viewModel::clearCompletedDownloads) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear completed queue")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Offline Storage", style = MaterialTheme.typography.titleMedium)
                        val usedMb = state.stats.downloadedBytes / (1024f * 1024f)
                        val limitMb = state.prefs.downloadStorageLimitMb.coerceAtLeast(1)
                        Text(
                            "${state.stats.downloadedChaptersCount} chapters • ${"%.1f".format(usedMb)} MB / $limitMb MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { (usedMb / limitMb.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wi-Fi Only Downloads", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.prefs.wifiOnlyDownloads,
                                onCheckedChange = viewModel::setWifiOnly
                            )
                        }
                    }
                }
            }

            if (state.downloadQueue.isNotEmpty()) {
                item { Text("Download Queue (${state.downloadQueue.size})", style = MaterialTheme.typography.titleSmall) }
                items(state.downloadQueue, key = { it.chapterId }) { task ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.mangaTitle, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${task.chapterName} • ${task.status} (${task.downloadedPages}/${task.totalPages})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    when (task.status) {
                                        "DOWNLOADING", "QUEUED" -> {
                                            IconButton(onClick = { viewModel.pauseDownload(task.chapterId) }) {
                                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                                            }
                                        }
                                        "PAUSED", "FAILED" -> {
                                            IconButton(onClick = { viewModel.resumeDownload(task.chapterId) }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume/Retry")
                                            }
                                        }
                                    }
                                    IconButton(onClick = { viewModel.cancelDownload(task.chapterId) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                                    }
                                }
                            }
                            LinearProgressIndicator(
                                progress = { task.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            task.errorMessage?.let { err ->
                                Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item { Text("Downloaded Offline Chapters (${state.downloadedChapters.size})", style = MaterialTheme.typography.titleSmall) }
            if (state.downloadedChapters.isEmpty()) {
                item {
                    KomaEmptyState(
                        title = "No Offline Chapters Yet",
                        subtitle = "Download chapters from any manga detail screen to read offline anytime.",
                        showIllustration = false
                    )
                }
            } else {
                items(state.downloadedChapters, key = { "offline_${it.id}" }) { ch ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenReader(ch.mangaId, ch.id) }
                    ) {
                        ListItem(
                            headlineContent = { Text(ch.name) },
                            supportingContent = { Text("${ch.sizeBytes / 1024} KB • Offline Ready") },
                            trailingContent = {
                                IconButton(onClick = { viewModel.deleteDownloadedChapter(ch) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete download")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
