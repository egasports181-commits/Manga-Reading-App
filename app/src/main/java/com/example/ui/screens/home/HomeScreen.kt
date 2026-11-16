package com.example.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.HomeSectionType
import com.example.ui.components.*
import kotlin.math.absoluteValue

private enum class HomeCategoryTab(val label: String) {
    MANGAS("Mangas"),
    WEBTOONS("Webtoons"),
    NOVELS("Novels")
}

private enum class HomeSubFilter(val label: String) {
    MANGA_CHART("Manga Chart"),
    NOW_READING("Now Reading"),
    LATEST_RELEASE("Latest Release")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenManga: (String) -> Unit,
    onNavigateToSources: () -> Unit,
    onOpenSource: (String) -> Unit = { onNavigateToSources() },
    onNavigateToLibrary: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToUpdates: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launchWebView = rememberCloudflareWebViewLauncher { viewModel.refreshLiveFeeds() }
    var showCustomizeSheet by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(HomeCategoryTab.MANGAS) }
    var selectedSubFilter by remember { mutableStateOf(HomeSubFilter.MANGA_CHART) }
    val cardWidth = if (state.compactCards) 122.dp else 148.dp
    val isDark = rememberIsDarkSurface()

    val onCardClick: (MangaCardUi) -> Unit = { card ->
        viewModel.openSourceManga(card, onOpenManga)
    }

    val allCombinedCards = remember(state) {
        (state.popularManga +
            state.trendingManga +
            state.continueReading +
            state.latestChapters +
            state.recentlyUpdated +
            state.recentlyAdded +
            state.recommendedSeries +
            state.personalized +
            state.readingHistory)
            .distinctBy { it.id }
    }

    val categoryFilteredCards = remember(selectedCategory, allCombinedCards) {
        when (selectedCategory) {
            HomeCategoryTab.MANGAS -> allCombinedCards
            HomeCategoryTab.WEBTOONS -> allCombinedCards.filter { card ->
                val text = "${card.title} ${card.altTitle} ${card.sourceName}".lowercase()
                "webtoon" in text || "manhwa" in text || "manhua" in text || "long strip" in text
            }
            HomeCategoryTab.NOVELS -> allCombinedCards.filter { card ->
                val text = "${card.title} ${card.altTitle} ${card.sourceName}".lowercase()
                "novel" in text || "light novel" in text
            }
        }
    }

    val chartItems = remember(selectedCategory, selectedSubFilter, state, categoryFilteredCards) {
        when (selectedCategory) {
            HomeCategoryTab.MANGAS -> {
                val base = when (selectedSubFilter) {
                    HomeSubFilter.MANGA_CHART -> (state.popularManga + state.trendingManga + state.recommendedSeries)
                        .ifEmpty { state.recentlyUpdated + state.continueReading + state.recentlyAdded }
                    HomeSubFilter.NOW_READING -> (state.continueReading + state.readingHistory + state.recentlyAdded)
                        .ifEmpty { state.popularManga }
                    HomeSubFilter.LATEST_RELEASE -> (state.latestChapters + state.recentlyUpdated)
                        .ifEmpty { state.popularManga }
                }
                base.distinctBy { it.id }.take(12)
            }
            HomeCategoryTab.WEBTOONS, HomeCategoryTab.NOVELS -> categoryFilteredCards.take(12)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HomeEditorialTopBar(
                isDemoMode = state.demoModeEnabled,
                onNotificationsClick = onNavigateToUpdates,
                onRefreshClick = { viewModel.refreshLiveFeeds() },
                onCustomizeClick = { showCustomizeSheet = true },
                onSearchClick = onNavigateToSources
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Top Category Pill Tabs: Mangas | Webtoons | Novels
            item(key = "home_category_pills") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HomeCategoryTab.entries.forEach { tab ->
                        val active = tab == selectedCategory
                        val activeBg = if (isDark) Color(0xFFF7F7FA) else Color(0xFF111114)
                        val activeFg = if (isDark) Color(0xFF111114) else Color.White
                        Surface(
                            color = if (active) activeBg else Color.Transparent,
                            shape = CircleShape,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { selectedCategory = tab }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (active) {
                                    Icon(
                                        imageVector = when (tab) {
                                            HomeCategoryTab.MANGAS -> Icons.Default.AutoStories
                                            HomeCategoryTab.WEBTOONS -> Icons.Default.ViewAgenda
                                            HomeCategoryTab.NOVELS -> Icons.Default.Book
                                        },
                                        contentDescription = null,
                                        tint = activeFg,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) activeFg else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Sub-filters row: Manga Chart | Now Reading | Latest Release | See all >
            item(key = "home_subfilters") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeSubFilter.entries.forEach { filter ->
                                val active = filter == selectedSubFilter
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedSubFilter = filter }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = filter.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(if (active) 28.dp else 0.dp)
                                            .height(2.5.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    if (selectedSubFilter == HomeSubFilter.NOW_READING) onNavigateToLibrary()
                                    else onNavigateToSources()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "See all",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "See all",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.75.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                    )
                }
            }

            if (state.feedError != null) {
                item(key = "home_error_banner") {
                    ErrorRetryBanner(
                        message = state.feedError!!,
                        onRetry = viewModel::refreshLiveFeeds,
                        onOpenWebView = state.feedCloudflareUrl?.let { cfUrl ->
                            { launchWebView(cfUrl, "Home Feed") }
                        }
                    )
                }
            }

            // Ranked Poster Carousel Hero Stage
            if (state.isLoadingLiveFeeds && chartItems.isEmpty()) {
                item(key = "home_hero_skeleton") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        FeaturedHeroSkeletonRow()
                        MangaCardSkeletonRow()
                    }
                }
            } else if (chartItems.isNotEmpty()) {
                item(key = "home_ranked_carousel") {
                    RankedPosterCarousel(
                        items = chartItems,
                        isDark = isDark,
                        onOpenItem = onCardClick
                    )
                }
            } else {
                item(key = "home_category_empty") {
                    val emptySubtitle = when (selectedCategory) {
                        HomeCategoryTab.WEBTOONS -> "No cached Webtoon/Manhwa titles match locally yet. Browse active sources or Global Search to explore Webtoons."
                        HomeCategoryTab.NOVELS -> "No Light Novel titles are cached locally. Use Sources or Global Search to explore available catalogs."
                        HomeCategoryTab.MANGAS -> "Browse live manga sources, import local CBZ archives, or enable Demo Mode below to explore sample chapters."
                    }
                    KomaEmptyState(
                        title = "Explore ${selectedCategory.label}",
                        subtitle = emptySubtitle,
                        actionLabel = "Browse Sources",
                        onAction = onNavigateToSources
                    )
                }
            }

            // Quick Resume Circular Shelf Strip
            val quickStripItems = (state.continueReading + state.recentlyUpdated)
                .distinctBy { it.id }
                .take(10)
            if (quickStripItems.isNotEmpty()) {
                item(key = "quick_resume_strip") {
                    QuickResumeAvatarStrip(
                        title = "Your Shelf",
                        items = quickStripItems,
                        onItemClick = onCardClick
                    )
                }
            }

            // All Configurable Koma Sections
            val visibleSections = state.sectionOrder.filterNot { it.id in state.hiddenSections }
            items(visibleSections, key = { it.id }) { section ->
                when (section) {
                    HomeSectionType.CONTINUE_READING -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.PlayCircleOutline,
                        items = state.continueReading,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToLibrary,
                        cardWidth = cardWidth
                    )
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
                    HomeSectionType.POPULAR_MANGA -> HorizontalMangaSection(
                        title = section.title,
                        icon = Icons.Default.Whatshot,
                        items = state.popularManga,
                        onItemClick = onCardClick,
                        onActionClick = onNavigateToSources,
                        cardWidth = cardWidth
                    )
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
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
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
private fun HomeEditorialTopBar(
    isDemoMode: Boolean,
    onNotificationsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onCustomizeClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(38.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_koma_logo),
                        contentDescription = "Kiyomi logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = "Kiyomi",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (isDemoMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "DEMO",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "Updates feed",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh dashboard",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = onCustomizeClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_customize_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Customize home sections",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search sources",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun RankedPosterCarousel(
    items: List<MangaCardUi>,
    isDark: Boolean,
    onOpenItem: (MangaCardUi) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size })
    val activeCard = items.getOrNull(pagerState.currentPage) ?: items.first()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            pageSpacing = 14.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val card = items[page]
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val scale = 1f - (pageOffset.coerceIn(0f, 1f) * 0.10f)
            val cardAlpha = 1f - (pageOffset.coerceIn(0f, 1f) * 0.25f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = cardAlpha
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = if (pageOffset < 0.25f) 14.dp else 4.dp,
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onOpenItem(card) }
                        .testTag("featured_card_${card.id}")
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MangaCoverImage(
                            coverUrl = card.coverUrl,
                            contentDescription = "${card.title} ranked #${page + 1} cover",
                            modifier = Modifier.fillMaxSize()
                        )

                        // Subtle top vignette for crisp rank numeral & heart badge contrast
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.50f), Color.Transparent)
                                    )
                                )
                        )

                        // Top-left Ranking Number (#1, #2, #3...)
                        Text(
                            text = "${page + 1}",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontStyle = FontStyle.Italic,
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.75f),
                                    offset = Offset(0f, 3f),
                                    blurRadius = 10f
                                )
                            ),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 10.dp)
                        )

                        // Top-right Circular Favorite/Bookmark Badge
                        Surface(
                            color = if (card.isInLibrary || card.isBookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.92f)
                            },
                            shape = CircleShape,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (card.isInLibrary || card.isBookmarked) {
                                        Icons.Filled.Favorite
                                    } else {
                                        Icons.Outlined.FavoriteBorder
                                    },
                                    contentDescription = if (card.isInLibrary) "In Library" else "Open series",
                                    tint = if (card.isInLibrary || card.isBookmarked) Color.White else Color(0xFF1A1B20),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (card.progressFraction != null && card.progressFraction > 0f) {
                            LinearProgressIndicator(
                                progress = { card.progressFraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Black.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }
        }

        // Centered Info & Big "Read Now" CTA beneath active ranked poster
        AnimatedContent(
            targetState = activeCard,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "rankedHeroInfo"
        ) { card ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (card.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${card.unreadCount}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Real Metadata Stats Strip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = buildString {
                            append(card.sourceName.ifBlank { "Kiyomi" })
                            append("  •  ")
                            append(card.status.label)
                            if (card.latestChapter.isNotBlank()) {
                                append("  •  ")
                                append(card.latestChapter)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(2.dp))

                val ctaColor = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF111114)
                Button(
                    onClick = { onOpenItem(card) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ctaColor,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 13.dp),
                    modifier = Modifier
                        .widthIn(min = 196.dp)
                        .heightIn(min = 48.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            ambientColor = ctaColor.copy(alpha = 0.32f),
                            spotColor = ctaColor.copy(alpha = 0.32f)
                        )
                ) {
                    Text(
                        text = "Read Now",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
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
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
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
