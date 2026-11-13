package com.example.ui.screens.more

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.KomaEmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: MoreViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats = state.stats

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Reading Statistics") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        icon = Icons.Default.CollectionsBookmark,
                        label = "Library",
                        value = "${stats.totalLibraryManga}",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.MenuBook,
                        label = "Chapters Read",
                        value = "${stats.totalChaptersRead}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                val mins = (stats.totalReadingTimeMs / 60_000L).coerceAtLeast(0L)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        icon = Icons.Default.Timer,
                        label = "Reading Time",
                        value = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.LocalFireDepartment,
                        label = "Daily Streak",
                        value = "${stats.dailyStreakDays}d",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                StatCard(
                    icon = Icons.Default.DownloadDone,
                    label = "Downloaded Chapters",
                    value = "${stats.downloadedChaptersCount} (${stats.downloadedBytes / (1024 * 1024)} MB)",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (stats.monthlyActivity.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Monthly Reading Activity", style = MaterialTheme.typography.titleMedium)
                            val primary = MaterialTheme.colorScheme.primary
                            val maxVal = (stats.monthlyActivity.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            ) {
                                val count = stats.monthlyActivity.size.coerceAtLeast(1)
                                val barWidth = (size.width / (count * 1.8f)).coerceAtMost(44.dp.toPx())
                                val gap = (size.width - barWidth * count) / (count + 1)
                                stats.monthlyActivity.forEachIndexed { idx, (_, value) ->
                                    val barHeight = (value.toFloat() / maxVal) * (size.height - 16.dp.toPx())
                                    val x = gap + idx * (barWidth + gap)
                                    val y = size.height - barHeight
                                    drawRoundRect(
                                        color = primary,
                                        topLeft = Offset(x, y),
                                        size = Size(barWidth, barHeight.coerceAtLeast(6f)),
                                        cornerRadius = CornerRadius(8f, 8f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                stats.monthlyActivity.forEach { (month, count) ->
                                    Text("$month ($count)", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (stats.favoriteGenres.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Favorite Genres", style = MaterialTheme.typography.titleMedium)
                            val top = (stats.favoriteGenres.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                            stats.favoriteGenres.forEach { (genre, count) ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(genre, style = MaterialTheme.typography.bodyMedium)
                                        Text("$count", style = MaterialTheme.typography.labelMedium)
                                    }
                                    LinearProgressIndicator(
                                        progress = { count.toFloat() / top },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (stats.mostReadManga.isNotEmpty()) {
                item { Text("Most-Read Series", style = MaterialTheme.typography.titleMedium) }
                items(stats.mostReadManga) { (title, timeMs) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        trailingContent = { Text("${(timeMs / 60_000L).coerceAtLeast(1)} min") }
                    )
                }
            }

            if (stats.totalLibraryManga == 0 && stats.totalChaptersRead == 0) {
                item {
                    KomaEmptyState(
                        title = "No Reading Activity Yet",
                        subtitle = "Your reading streak, favorite genres, and monthly activity charts will populate automatically as you read.",
                        showIllustration = false
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
