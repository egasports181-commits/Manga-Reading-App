package com.example.ui.screens.updates

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ChapterListSkeleton
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.components.MangaCoverImage
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
                subtitle = if (state.updates.isNotEmpty()) "${state.updates.size} entries" else "Library chapter feed",
                actions = {
                    KomaCircularGlassButton(
                        icon = Icons.Default.Download,
                        contentDescription = "Download Queue",
                        onClick = onOpenDownloads
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.DoneAll,
                        contentDescription = "Mark all seen",
                        onClick = viewModel::markAllSeen
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Check for updates",
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.checkForUpdatesNow()
                        },
                        modifier = Modifier.testTag("check_updates_button")
                    )
                    Spacer(Modifier.width(8.dp))
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
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearStatusMessage, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (state.sources.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedSource == null,
                        onClick = { viewModel.selectSourceFilter(null) },
                        shape = CircleShape,
                        label = { Text("All Sources") }
                    )
                    state.sources.forEach { src ->
                        FilterChip(
                            selected = state.selectedSource == src,
                            onClick = { viewModel.selectSourceFilter(src) },
                            shape = CircleShape,
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
                        shape = CircleShape,
                        label = { Text("All Manga") }
                    )
                    state.mangaTitles.forEach { title ->
                        FilterChip(
                            selected = state.selectedMangaTitle == title,
                            onClick = { viewModel.selectMangaFilter(title) },
                            shape = CircleShape,
                            label = { Text(title) }
                        )
                    }
                }
            }

            if (state.isChecking && state.updates.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                ChapterListSkeleton(count = 6)
            } else if (state.updates.isEmpty()) {
                KomaEmptyState(
                    title = "No New Chapter Updates",
                    subtitle = "Tap Refresh above to scan your library sources for newly released chapters.",
                    actionLabel = "Check Now",
                    onAction = viewModel::checkForUpdatesNow
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.updates, key = { it.id }) { item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(
                                0.75.dp,
                                if (!item.isSeen) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onOpenReader(item.mangaId, item.chapterId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                MangaCoverImage(
                                    coverUrl = item.coverUrl,
                                    contentDescription = item.mangaTitle,
                                    modifier = Modifier
                                        .width(50.dp)
                                        .aspectRatio(3f / 4.2f)
                                        .clip(RoundedCornerShape(11.dp))
                                        .clickable { onOpenManga(item.mangaId) }
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (!item.isSeen) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                        Text(
                                            item.mangaTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        item.chapterName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.sourceName} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.detectedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { viewModel.markUpdateRead(item) }) {
                                    Icon(
                                        if (item.isSeen) Icons.Default.Done else Icons.Default.CheckCircleOutline,
                                        contentDescription = "Mark read",
                                        tint = if (item.isSeen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onOpenReader(item.mangaId, item.chapterId) },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
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
