package com.example.ui.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaElevatedCard
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassTopBar

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
            LiquidGlassTopBar(
                navigationIcon = {
                    KomaCircularGlassButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                title = "Download Manager",
                subtitle = "${state.downloadQueue.size} queued • ${state.downloadedChapters.size} offline",
                actions = {
                    KomaCircularGlassButton(
                        icon = Icons.Default.CleaningServices,
                        contentDescription = "Clean up read downloads",
                        onClick = viewModel::cleanupReadDownloads
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.ClearAll,
                        contentDescription = "Clear completed queue",
                        onClick = viewModel::clearCompletedDownloads
                    )
                    Spacer(Modifier.width(8.dp))
                }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                KomaElevatedCard(
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Offline Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val usedMb = state.stats.downloadedBytes / (1024f * 1024f)
                        val limitMb = state.prefs.downloadStorageLimitMb.coerceAtLeast(1)
                        Text(
                            "${state.stats.downloadedChaptersCount} chapters • ${"%.1f".format(usedMb)} MB / $limitMb MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { (usedMb / limitMb.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (state.storageBySource.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                            Text("Storage by Source", style = MaterialTheme.typography.labelLarge)
                            state.storageBySource.forEach { (sourceId, bytes) ->
                                val mb = bytes / (1024f * 1024f)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(sourceId, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        if (mb >= 1f) "${"%.1f".format(mb)} MB" else "${bytes / 1024} KB",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
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
                item {
                    Text(
                        "Download Queue (${state.downloadQueue.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(state.downloadQueue, key = { it.chapterId }) { task ->
                    KomaElevatedCard(shape = RoundedCornerShape(18.dp)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.mangaTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    val retryLabel = if (task.retryCount > 0) " • Retry ${task.retryCount}" else ""
                                    Text(
                                        "${task.chapterName} • ${task.status} (${task.downloadedPages}/${task.totalPages})$retryLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    if (task.status == "QUEUED" || task.status == "PAUSED") {
                                        IconButton(onClick = { viewModel.moveDownloadToTop(task.chapterId) }) {
                                            Icon(Icons.Default.VerticalAlignTop, contentDescription = "Move to top")
                                        }
                                    }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary
                            )
                            task.errorMessage?.let { err ->
                                Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Downloaded Offline Chapters (${state.downloadedChapters.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
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
                    KomaElevatedCard(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenReader(ch.mangaId, ch.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ch.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                Text(
                                    "${ch.sizeBytes / 1024} KB • Offline Ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { viewModel.deleteDownloadedChapter(ch) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete download")
                            }
                        }
                    }
                }
            }
        }
    }
}
