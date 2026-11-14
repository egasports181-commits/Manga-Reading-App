package com.example.ui.screens.detail

import android.app.Activity
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.TrackingEntryEntity
import com.example.domain.model.*
import com.example.ui.components.ChapterListSkeleton
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.KomaEmptyState
import com.example.ui.theme.DetailCrimsonMid
import com.example.ui.theme.DetailCrimsonTop
import com.example.ui.theme.DetailObsidian
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.StatusError
import com.example.ui.theme.WarmOrangeContainerDark
import com.example.ui.theme.WarmOrangeDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DetailChapterFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    OFFLINE("Offline"),
    SAVED("Bookmarked")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    viewModel: MangaDetailViewModel,
    onBack: () -> Unit,
    onOpenReader: (mangaId: String, chapterId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val manga = state.manga
    var expandSynopsis by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var showTrackingSheet by remember { mutableStateOf(false) }
    var selectedChapterFilter by remember { mutableStateOf(DetailChapterFilter.ALL) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(Unit) {
            val window = (view.context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val previousLightStatus = controller?.isAppearanceLightStatusBars ?: false
            val previousLightNav = controller?.isAppearanceLightNavigationBars ?: false
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                controller?.isAppearanceLightStatusBars = previousLightStatus
                controller?.isAppearanceLightNavigationBars = previousLightNav
            }
        }
    }

    val filteredChapters = remember(state.chapters, selectedChapterFilter) {
        when (selectedChapterFilter) {
            DetailChapterFilter.ALL -> state.chapters
            DetailChapterFilter.UNREAD -> state.chapters.filter { !it.isRead }
            DetailChapterFilter.OFFLINE -> state.chapters.filter { it.isDownloaded }
            DetailChapterFilter.SAVED -> state.chapters.filter { it.isBookmarked }
        }
    }

    val detailAccent = WarmOrangeDark
    val crimsonHeroGradient = remember {
        Brush.verticalGradient(
            colors = listOf(
                DetailCrimsonTop.copy(alpha = 0.78f),
                DetailCrimsonMid.copy(alpha = 0.88f),
                DetailObsidian
            )
        )
    }

    val detailColorScheme = remember {
        darkColorScheme(
            primary = detailAccent,
            onPrimary = Color.White,
            primaryContainer = WarmOrangeContainerDark,
            onPrimaryContainer = InkWhite,
            secondary = Color(0xFFFF3B30),
            onSecondary = Color.White,
            background = DetailObsidian,
            onBackground = InkWhite,
            surface = ObsidianSurface,
            onSurface = InkWhite,
            surfaceVariant = ObsidianSurfaceHigh,
            onSurfaceVariant = InkMuted,
            surfaceContainerLowest = DetailObsidian,
            surfaceContainerLow = ObsidianSurface,
            surfaceContainer = ObsidianSurfaceHigh,
            surfaceContainerHigh = Color(0xFF21222B),
            outline = ObsidianOutline,
            outlineVariant = ObsidianOutline.copy(alpha = 0.55f),
            error = StatusError,
            onError = Color.White
        )
    }

    MaterialTheme(
        colorScheme = detailColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DetailObsidian)
        ) {
        if (manga == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DarkHeroCircleButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(260.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                )
                ChapterListSkeleton(count = 6)
            }
            return@Box
        }

        val nextChapter = viewModel.getNextChapterToRead()
        val genres = remember(manga.genres) {
            manga.genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        val parsedStatus = MangaStatus.fromString(manga.status)
        val dateText = remember(manga.latestUpdateAt, state.chapters) {
            val ts = manga.latestUpdateAt.takeIf { it > 0L }
                ?: state.chapters.maxOfOrNull { it.uploadedAt }?.takeIf { it > 0L }
            if (ts != null) {
                SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).format(Date(ts))
            } else {
                null
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DetailObsidian),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // Full-Bleed Immersive Crimson & Cover Hero Stage
            item(key = "detail_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(crimsonHeroGradient)
                ) {
                    // Full-bleed cover artwork fading smoothly into deep black
                    if (manga.coverUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = manga.coverUrl,
                            contentDescription = "${manga.title} cover backdrop",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .background(crimsonHeroGradient)
                        )
                    }

                    // Top subtle vignette + rich crimson-to-black bottom fade
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Black.copy(alpha = 0.45f),
                                        0.28f to DetailCrimsonTop.copy(alpha = 0.20f),
                                        0.62f to DetailCrimsonMid.copy(alpha = 0.82f),
                                        0.86f to DetailObsidian.copy(alpha = 0.96f),
                                        1.0f to DetailObsidian
                                    )
                                )
                            )
                    )

                    // Top floating action controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DarkHeroCircleButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkHeroCircleButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh chapters",
                                onClick = viewModel::refresh
                            )
                            DarkHeroCircleButton(
                                icon = Icons.Default.DownloadForOffline,
                                contentDescription = "Download unread chapters",
                                onClick = viewModel::downloadUnreadChapters
                            )
                            DarkHeroCircleButton(
                                icon = Icons.Default.PlaylistAdd,
                                contentDescription = "Download chapter range",
                                onClick = { showRangeDialog = true }
                            )
                        }
                    }

                    // Centered Editorial Title, Metadata, & Large Orange "Read Now" Button
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 246.dp, start = 20.dp, end = 20.dp, bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (manga.isDemoData) {
                            Surface(
                                color = detailAccent.copy(alpha = 0.22f),
                                shape = CircleShape,
                                border = BorderStroke(0.75.dp, detailAccent.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "DEMO SAMPLE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Text(
                            text = manga.title,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (manga.altTitle.isNotBlank()) {
                            Text(
                                text = manga.altTitle.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // "Updated • Action, Adventure, Dark Fantasy"
                        val statusTag = if (parsedStatus == MangaStatus.ONGOING) "Updated" else parsedStatus.label
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = detailAccent, fontWeight = FontWeight.SemiBold)) {
                                    append(statusTag)
                                }
                                if (genres.isNotEmpty()) {
                                    withStyle(SpanStyle(color = Color.White.copy(alpha = 0.72f))) {
                                        append("  •  ")
                                        append(genres.take(4).joinToString(", "))
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        // "• 26 Sep, 2026 • MangaDex"
                        Text(
                            text = buildString {
                                append("• ")
                                if (dateText != null) {
                                    append(dateText)
                                    append("  •  ")
                                }
                                append(manga.sourceName)
                                if (manga.author.isNotBlank()) {
                                    append("  •  ")
                                    append(manga.author)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(6.dp))

                        // Large Vibrant Orange "Read Now" Button
                        if (nextChapter != null) {
                            Button(
                                onClick = { onOpenReader(manga.id, nextChapter.id) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = detailAccent,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(vertical = 15.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .shadow(
                                        elevation = 16.dp,
                                        shape = CircleShape,
                                        ambientColor = detailAccent.copy(alpha = 0.65f),
                                        spotColor = detailAccent.copy(alpha = 0.65f)
                                    )
                                    .testTag("start_reading_fab")
                            ) {
                                Text(
                                    text = if (nextChapter.lastPageRead > 0) "Resume Reading" else "Read Now",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Compact Secondary Action Pills (Library, Categories, Bookmark, Track)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailActionPill(
                                icon = if (manga.inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                label = if (manga.inLibrary) "Saved" else "Add",
                                highlighted = manga.inLibrary,
                                accentColor = detailAccent,
                                onClick = viewModel::toggleLibrary,
                                tag = "toggle_library_button",
                                modifier = Modifier.weight(1f)
                            )
                            DetailActionPill(
                                icon = Icons.Default.FolderSpecial,
                                label = "Shelf",
                                highlighted = state.assignedCategoryIds.isNotEmpty(),
                                accentColor = detailAccent,
                                onClick = { showCategoryDialog = true },
                                tag = "assign_categories_button",
                                modifier = Modifier.weight(1f)
                            )
                            DetailActionPill(
                                icon = if (manga.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                label = if (manga.isBookmarked) "Pinned" else "Pin",
                                highlighted = manga.isBookmarked,
                                accentColor = detailAccent,
                                onClick = viewModel::toggleBookmark,
                                modifier = Modifier.weight(1f)
                            )
                            DetailActionPill(
                                icon = Icons.Default.Sync,
                                label = if (state.trackingEntries.isEmpty()) "Track" else "Track (${state.trackingEntries.size})",
                                highlighted = state.trackingEntries.isNotEmpty(),
                                accentColor = detailAccent,
                                onClick = { showTrackingSheet = true },
                                tag = "tracking_button",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (state.isRefreshing) {
                item(key = "detail_loading_bar") {
                    LinearProgressIndicator(
                        color = detailAccent,
                        trackColor = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            state.statusBanner?.let { msg ->
                item(key = "detail_status_banner") {
                    Surface(
                        color = detailAccent.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.75.dp, detailAccent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f))
                            IconButton(onClick = viewModel::clearBanner, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                item(key = "detail_error_banner") {
                    Box(modifier = Modifier.padding(vertical = 6.dp)) {
                        ErrorRetryBanner(message = err, onRetry = viewModel::refresh)
                    }
                }
            }

            // Collapsible Synopsis Card
            if (manga.description.isNotBlank()) {
                item(key = "detail_synopsis") {
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { expandSynopsis = !expandSynopsis }
                    ) {
                        Text(
                            text = manga.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.74f),
                            maxLines = if (expandSynopsis) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            // Chapters Header + Filter Pills (All / Unread / Offline / Bookmarked)
            item(key = "detail_chapters_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Chapters",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${filteredChapters.size} of ${state.chapters.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.56f)
                            )
                            DarkHeroCircleButton(
                                icon = Icons.Default.SwapVert,
                                contentDescription = "Reverse chapter order",
                                onClick = viewModel::toggleChapterSort,
                                containerSize = 36.dp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DetailChapterFilter.entries.forEach { filter ->
                            val selected = selectedChapterFilter == filter
                            Surface(
                                color = if (selected) detailAccent else Color.Transparent,
                                shape = CircleShape,
                                border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .clickable {
                                        selectedChapterFilter = filter
                                        if (filter == DetailChapterFilter.ALL && state.showUnreadOnly) {
                                            viewModel.toggleUnreadFilter()
                                        }
                                    }
                            ) {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (filteredChapters.isEmpty() && state.isRefreshing) {
                item(key = "detail_chapter_skeleton") { ChapterListSkeleton(count = 5) }
            } else if (filteredChapters.isEmpty()) {
                item(key = "detail_chapters_empty") {
                    KomaEmptyState(
                        title = "No Chapters Found",
                        subtitle = if (selectedChapterFilter == DetailChapterFilter.ALL) {
                            "Tap Refresh above to fetch chapters from ${manga.sourceName}."
                        } else {
                            "No chapters match the ${selectedChapterFilter.label} filter."
                        },
                        actionLabel = if (selectedChapterFilter == DetailChapterFilter.ALL) "Refresh" else "Show All",
                        onAction = {
                            if (selectedChapterFilter == DetailChapterFilter.ALL) viewModel.refresh()
                            else selectedChapterFilter = DetailChapterFilter.ALL
                        },
                        showIllustration = false
                    )
                }
            } else {
                items(filteredChapters, key = { it.id }) { chapter ->
                    ChapterListRow(
                        chapter = chapter,
                        accentColor = detailAccent,
                        onClick = { onOpenReader(manga.id, chapter.id) },
                        onToggleRead = { viewModel.toggleChapterRead(chapter) },
                        onToggleBookmark = { viewModel.toggleChapterBookmark(chapter) },
                        onDownload = { viewModel.downloadChapter(chapter) },
                        onDeleteDownload = { viewModel.deleteDownloadedChapter(chapter) }
                    )
                }
            }
        }
    }

    if (showCategoryDialog) {
        val selected = remember(state.assignedCategoryIds) {
            mutableStateListOf<Long>().apply { addAll(state.assignedCategoryIds) }
        }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Set Categories") },
            text = {
                Column {
                    state.categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (cat.id in selected) selected.remove(cat.id) else selected.add(cat.id)
                                }
                        ) {
                            Checkbox(
                                checked = cat.id in selected,
                                onCheckedChange = {
                                    if (cat.id in selected) selected.remove(cat.id) else selected.add(cat.id)
                                }
                            )
                            Text(cat.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setCategories(selected.toList())
                        showCategoryDialog = false
                    },
                    shape = CircleShape
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRangeDialog) {
        var fromText by remember { mutableStateOf("1") }
        var toText by remember { mutableStateOf(state.chapters.maxOfOrNull { it.chapterNumber }?.toInt()?.toString() ?: "10") }
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Download Chapter Range") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text("From Ch.") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("To Ch.") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val f = fromText.toDoubleOrNull() ?: 1.0
                        val t = toText.toDoubleOrNull() ?: f
                        viewModel.downloadChapterRange(f, t)
                        showRangeDialog = false
                    },
                    shape = CircleShape
                ) { Text("Queue") }
            },
            dismissButton = {
                TextButton(onClick = { showRangeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTrackingSheet && manga != null) {
        MangaTrackingSheet(
            manga = manga,
            entries = state.trackingEntries,
            searchResults = state.trackerSearchResults,
            searchError = state.trackerSearchError,
            onSearch = viewModel::searchTracker,
            onSave = viewModel::saveTracking,
            onResolveConflict = viewModel::resolveConflict,
            onDismiss = { showTrackingSheet = false }
        )
    }
    }
}

@Composable
private fun DarkHeroCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerSize: androidx.compose.ui.unit.Dp = 40.dp
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(containerSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DetailActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = ""
) {
    Surface(
        color = if (highlighted) accentColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            0.75.dp,
            if (highlighted) accentColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) accentColor else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (highlighted) accentColor else Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChapterListRow(
    chapter: ChapterEntity,
    accentColor: Color,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    val dateStr = remember(chapter.uploadedAt) {
        if (chapter.uploadedAt > 0L) {
            SimpleDateFormat("dd MMM, yyyy", Locale.getDefault()).format(Date(chapter.uploadedAt))
        } else {
            ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
            .testTag("chapter_row_${chapter.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (!chapter.isRead) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                    if (chapter.isBookmarked) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (chapter.isRead) FontWeight.Medium else FontWeight.Bold,
                        color = if (chapter.isRead) Color.White.copy(alpha = 0.56f) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val subText = buildString {
                    append(if (chapter.isRead) "Read" else if (chapter.lastPageRead > 0) "Page ${chapter.lastPageRead + 1}" else "Unread")
                    if (chapter.isDownloaded) append(" • Offline")
                    if (chapter.scanlator.isNotBlank()) append(" • ${chapter.scanlator}")
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.48f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (dateStr.isNotBlank()) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                IconButton(onClick = onToggleBookmark, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (chapter.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark chapter",
                        tint = if (chapter.isBookmarked) accentColor else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onToggleRead, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (chapter.isRead) Icons.Default.VisibilityOff else Icons.Default.CheckCircleOutline,
                        contentDescription = if (chapter.isRead) "Mark unread" else "Mark read",
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (chapter.isDownloaded) {
                    IconButton(onClick = onDeleteDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = "Delete download",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download chapter",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 0.75.dp,
            color = Color.White.copy(alpha = 0.08f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaTrackingSheet(
    manga: com.example.data.local.entity.MangaEntity,
    entries: List<TrackingEntryEntity>,
    searchResults: List<RemoteTrackerSearchResult>,
    searchError: String?,
    onSearch: (TrackerService, String) -> Unit,
    onSave: (TrackingEntryEntity) -> Unit,
    onResolveConflict: (TrackingEntryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedService by remember { mutableStateOf(TrackerService.ANILIST) }
    val existing = entries.firstOrNull { it.serviceId == selectedService.id }
    var status by remember(selectedService, existing) {
        mutableStateOf(TrackingStatus.fromId(existing?.status ?: "reading"))
    }
    var chaptersRead by remember(selectedService, existing) {
        mutableStateOf((existing?.chaptersRead ?: 0).toString())
    }
    var score by remember(selectedService, existing) {
        mutableStateOf((existing?.score ?: 0.0).toString())
    }
    var remoteId by remember(selectedService, existing) {
        mutableStateOf(existing?.remoteMediaId.orEmpty())
    }
    var remoteTitle by remember(selectedService, existing) {
        mutableStateOf(existing?.remoteTitle ?: manga.title)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Manga Tracking", style = MaterialTheme.typography.headlineSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerService.entries.forEach { svc ->
                    FilterChip(
                        selected = selectedService == svc,
                        onClick = { selectedService = svc },
                        label = { Text(svc.displayName) }
                    )
                }
            }
            Text(
                selectedService.apiEndpointNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (existing?.syncState == SyncState.CONFLICT.name) {
                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f), shape = MaterialTheme.shapes.small) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sync conflict detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onResolveConflict(existing) }) { Text("Push Local") }
                    }
                }
            }

            if (selectedService.supportsOAuthTokenSync) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = remoteTitle,
                        onValueChange = { remoteTitle = it },
                        label = { Text("Match Remote Title") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onSearch(selectedService, remoteTitle) }) { Text("Search") }
                }
                searchError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                searchResults.take(3).forEach { r ->
                    ListItem(
                        headlineContent = { Text(r.title) },
                        supportingContent = { Text("ID: ${r.remoteMediaId} • ${r.totalChapters} ch") },
                        modifier = Modifier.clickable {
                            remoteId = r.remoteMediaId
                            remoteTitle = r.title
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TrackingStatus.entries.forEach { st ->
                    FilterChip(
                        selected = status == st,
                        onClick = { status = st },
                        label = { Text(st.label) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = chaptersRead,
                    onValueChange = { chaptersRead = it.filter { c -> c.isDigit() } },
                    label = { Text("Chapters Read") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = score,
                    onValueChange = { score = it },
                    label = { Text("Score (0-10)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = {
                    onSave(
                        TrackingEntryEntity(
                            id = existing?.id ?: 0L,
                            mangaId = manga.id,
                            serviceId = selectedService.id,
                            remoteMediaId = remoteId,
                            remoteTitle = remoteTitle,
                            status = status.id,
                            score = score.toDoubleOrNull() ?: 0.0,
                            chaptersRead = chaptersRead.toIntOrNull() ?: 0,
                            totalChapters = manga.totalChapters
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Sync Progress")
            }
        }
    }
}
