package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.HomeSectionType
import com.example.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenManga: (String) -> Unit,
    onNavigateToSources: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToDownloads: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCustomizeSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Koma", style = MaterialTheme.typography.headlineMedium)
                        if (state.demoModeEnabled) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "DEMO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshLiveFeeds() },
                        modifier = Modifier.testTag("home_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh dashboard")
                    }
                    IconButton(
                        onClick = { showCustomizeSheet = true },
                        modifier = Modifier.testTag("home_customize_button")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Customize home sections")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                DemoModeBanner(
                    enabled = state.demoModeEnabled,
                    onToggleDemoMode = viewModel::toggleDemoMode
                )
            }

            if (state.feedError != null) {
                item {
                    ErrorRetryBanner(
                        message = state.feedError!!,
                        onRetry = viewModel::refreshLiveFeeds
                    )
                }
            }

            if (state.isLoadingLiveFeeds && state.popularManga.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Fetching Catalogs...",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        MangaCardSkeletonRow()
                    }
                }
            }

            val visibleSections = state.sectionOrder.filterNot { it.id in state.hiddenSections }
            val hasAnyContent = state.continueReading.isNotEmpty() ||
                state.recentlyUpdated.isNotEmpty() ||
                state.recentlyAdded.isNotEmpty() ||
                state.popularManga.isNotEmpty() ||
                state.recentlyDownloaded.isNotEmpty() ||
                state.readingHistory.isNotEmpty()

            if (!hasAnyContent && !state.isLoadingLiveFeeds) {
                item {
                    KomaEmptyState(
                        title = "Your Desk is Ready",
                        subtitle = "Browse live manga sources, import local CBZ archives, or toggle Demo Mode above to explore sample chapters.",
                        actionLabel = "Browse Sources",
                        onAction = onNavigateToSources
                    )
                }
            }

            items(visibleSections, key = { it.id }) { section ->
                val onCardClick: (MangaCardUi) -> Unit = { card ->
                    viewModel.openSourceManga(card, onOpenManga)
                }
                when (section) {
                    HomeSectionType.CONTINUE_READING -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.PlayCircleOutline,
                        items = state.continueReading,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToLibrary
                    )
                    HomeSectionType.RECENTLY_UPDATED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.NewReleases,
                        items = state.recentlyUpdated,
                        onItemClick = onCardClick
                    )
                    HomeSectionType.RECENTLY_ADDED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.BookmarkAdded,
                        items = state.recentlyAdded,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToLibrary
                    )
                    HomeSectionType.POPULAR_MANGA -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Whatshot,
                        items = state.popularManga,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToSources
                    )
                    HomeSectionType.TRENDING_MANGA -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.TrendingUp,
                        items = state.trendingManga,
                        onItemClick = onCardClick
                    )
                    HomeSectionType.LATEST_CHAPTERS -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Update,
                        items = state.latestChapters,
                        onItemClick = onCardClick
                    )
                    HomeSectionType.RECOMMENDED_SERIES -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.AutoAwesome,
                        items = state.recommendedSeries,
                        onItemClick = onCardClick
                    )
                    HomeSectionType.RECENTLY_DOWNLOADED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.DownloadDone,
                        items = state.recentlyDownloaded,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToDownloads
                    )
                    HomeSectionType.READING_HISTORY -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.History,
                        items = state.readingHistory,
                        onItemClick = onCardClick
                    )
                    HomeSectionType.PERSONALIZED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Recommend,
                        items = state.personalized,
                        onItemClick = onCardClick
                    )
                }
            }
        }
    }

    if (showCustomizeSheet) {
        ModalBottomSheet(onDismissRequest = { showCustomizeSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Customize Home Sections",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Reorder carousels or toggle visibility.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(state.sectionOrder, key = { _, s -> s.id }) { index, section ->
                        val visible = section.id !in state.hiddenSections
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Switch(
                                    checked = visible,
                                    onCheckedChange = { viewModel.toggleSectionVisibility(section.id) }
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.moveSection(index, index - 1) },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = { viewModel.moveSection(index, index + 1) },
                                    enabled = index < state.sectionOrder.lastIndex
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
