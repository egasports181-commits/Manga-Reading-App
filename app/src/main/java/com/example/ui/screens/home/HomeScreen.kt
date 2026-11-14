package com.example.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    onOpenSource: (String) -> Unit = { onNavigateToSources() },
    onNavigateToLibrary: () -> Unit,
    onNavigateToDownloads: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCustomizeSheet by remember { mutableStateOf(false) }
    val cardWidth = if (state.compactCards) 122.dp else 148.dp

    Scaffold(
        topBar = {
            LiquidGlassTopBar(
                title = if (state.demoModeEnabled) "Koma • Demo" else "Koma",
                subtitle = if (state.continueReading.isNotEmpty()) {
                    "${state.continueReading.size} active series • ${state.favoriteSources.size} sources"
                } else {
                    "${state.favoriteSources.size} active sources"
                },
                showBrandMark = true,
                actions = {
                    KomaCircularGlassButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Refresh dashboard",
                        onClick = { viewModel.refreshLiveFeeds() },
                        modifier = Modifier.testTag("home_refresh_button")
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.Tune,
                        contentDescription = "Customize home sections",
                        onClick = { showCustomizeSheet = true },
                        modifier = Modifier.testTag("home_customize_button")
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
            contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val quickStripItems = (state.continueReading + state.recentlyUpdated)
                .distinctBy { it.id }
                .take(10)
            if (quickStripItems.isNotEmpty()) {
                item(key = "quick_resume_strip") {
                    QuickResumeAvatarStrip(
                        title = "Your Shelf",
                        items = quickStripItems,
                        onItemClick = { card -> viewModel.openSourceManga(card, onOpenManga) }
                    )
                }
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
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Discovering Series",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        FeaturedHeroSkeletonRow()
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
                        subtitle = "Browse live manga sources, import local CBZ archives, or enable Demo Mode below to explore sample chapters.",
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
                    HomeSectionType.CONTINUE_READING -> {
                        if (!state.compactCards && state.continueReading.isNotEmpty()) {
                            FeaturedHeroMangaSection(
                                title = section.title,
                                icon = Icons.Default.PlayArrow,
                                items = state.continueReading,
                                onItemClick = onCardClick,
                                badgeProvider = { card ->
                                    val pct = ((card.progressFraction ?: 0f) * 100).toInt()
                                    if (pct > 0) "RESUME $pct%" else "READING"
                                },
                                onActionClick = onNavigateToLibrary
                            )
                        } else {
                            HorizontalMangaSection(
                                title = section.title,
                                icon = Icons.Default.PlayCircleOutline,
                                items = state.continueReading,
                                onItemClick = onCardClick,
                                onActionClick = onNavigateToLibrary,
                                cardWidth = cardWidth
                            )
                        }
                    }
                    HomeSectionType.FAVORITE_SOURCES -> if (state.favoriteSources.isNotEmpty()) {
                        FavoriteSourcesStrip(
                            sources = state.favoriteSources,
                            onOpenSource = onOpenSource,
                            onSeeAll = onNavigateToSources
                        )
                    }
                    HomeSectionType.RECENTLY_UPDATED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.NewReleases,
                        items = state.recentlyUpdated,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.RECENTLY_ADDED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.BookmarkAdded,
                        items = state.recentlyAdded,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToLibrary,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.POPULAR_MANGA -> {
                        if (!state.compactCards && state.continueReading.isEmpty() && state.popularManga.isNotEmpty()) {
                            FeaturedHeroMangaSection(
                                title = section.title,
                                icon = Icons.Default.Whatshot,
                                items = state.popularManga,
                                onItemClick = onCardClick,
                                badgeProvider = { "POPULAR" },
                                onActionClick = onNavigateToSources
                            )
                        } else {
                            HorizontalMangaSection(
                                title = section.title,
                                icon = Icons.Default.Whatshot,
                                items = state.popularManga,
                                onItemClick = onCardClick,
                                onActionClick = onNavigateToSources,
                                cardWidth = cardWidth
                            )
                        }
                    }
                    HomeSectionType.TRENDING_MANGA -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.TrendingUp,
                        items = state.trendingManga,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.LATEST_CHAPTERS -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Update,
                        items = state.latestChapters,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.RECOMMENDED_SERIES -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.AutoAwesome,
                        items = state.recommendedSeries,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.RECENTLY_DOWNLOADED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.DownloadDone,
                        items = state.recentlyDownloaded,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToDownloads,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.READING_HISTORY -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.History,
                        items = state.readingHistory,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                    HomeSectionType.PERSONALIZED -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Recommend,
                        items = state.personalized,
                        onItemClick = onCardClick,
                        cardWidth = cardWidth
                    )
                }
            }

            item(key = "demo_banner") {
                DemoModeBanner(
                    enabled = state.demoModeEnabled,
                    onToggleDemoMode = viewModel::toggleDemoMode
                )
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
                    text = "Customize Home Layout",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Compact Cover Density", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Fit more covers across horizontal carousels",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.compactCards,
                            onCheckedChange = viewModel::toggleCompactCards
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.sectionOrder, key = { _, s -> s.id }) { index, section ->
                        val visible = section.id !in state.hiddenSections
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
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

@Composable
private fun FavoriteSourcesStrip(
    sources: List<FavoriteSourceQuickItem>,
    onOpenSource: (String) -> Unit,
    onSeeAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KomaSectionHeader(
            title = "Active Sources",
            icon = Icons.Default.Explore,
            onActionClick = onSeeAll,
            actionLabel = "All sources"
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sources, key = { it.id }) { src ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable { onOpenSource(src.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = CircleShape,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when {
                                        src.id == "local_saf" -> Icons.Default.FolderZip
                                        src.isDemo -> Icons.Default.Science
                                        else -> Icons.Default.Public
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column {
                            Text(src.name, style = MaterialTheme.typography.labelLarge)
                            Text(
                                src.language.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
