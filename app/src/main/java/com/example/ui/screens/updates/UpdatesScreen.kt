package com.example.ui.screens.updates

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassTopBar
import java.text.DateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    onOpenReader: (mangaId: String, chapterId: String) -> Unit,
    onOpenManga: (mangaId: String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    Scaffold(
        topBar = {
            LiquidGlassTopBar(
                title = "Updates",
                subtitle = if (state.updates.isNotEmpty()) "${state.updates.size} entries" else null,
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Default.Download, contentDescription = "Download Queue")
                    }
                    IconButton(onClick = viewModel::markAllSeen) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Mark all seen")
                    }
                    IconButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.checkForUpdatesNow()
                        },
                        modifier = Modifier.testTag("check_updates_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isChecking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = viewModel::clearStatusMessage, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (state.sources.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedSource == null,
                        onClick = { viewModel.selectSourceFilter(null) },
                        label = { Text("All Sources") }
                    )
                    state.sources.forEach { src ->
                        FilterChip(
                            selected = state.selectedSource == src,
                            onClick = { viewModel.selectSourceFilter(src) },
                            label = { Text(src) }
                        )
                    }
                }
            }

            if (state.mangaTitles.size > 1) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedMangaTitle == null,
                        onClick = { viewModel.selectMangaFilter(null) },
                        label = { Text("All Manga") }
                    )
                    state.mangaTitles.forEach { title ->
                        FilterChip(
                            selected = state.selectedMangaTitle == title,
                            onClick = { viewModel.selectMangaFilter(title) },
                            label = { Text(title) }
                        )
                    }
                }
            }

            if (state.updates.isEmpty()) {
                KomaEmptyState(
                    title = "No New Chapter Updates",
                    subtitle = "Tap Refresh above to scan your library sources for newly released chapters.",
                    actionLabel = "Check Now",
                    onAction = viewModel::checkForUpdatesNow
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.updates, key = { it.id }) { item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenReader(item.mangaId, item.chapterId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = item.mangaTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 66.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onOpenManga(item.mangaId) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.mangaTitle, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        item.chapterName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "${item.sourceName} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.detectedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.markUpdateRead(item) }) {
                                    Icon(
                                        if (item.isSeen) Icons.Default.Done else Icons.Default.CheckCircleOutline,
                                        contentDescription = "Mark read"
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onOpenReader(item.mangaId, item.chapterId) }
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Read chapter")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
