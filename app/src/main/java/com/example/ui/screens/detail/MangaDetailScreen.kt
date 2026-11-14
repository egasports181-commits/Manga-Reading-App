package com.example.ui.screens.detail

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.TrackingEntryEntity
import com.example.domain.model.*
import com.example.ui.components.ChapterListSkeleton
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.MangaCoverImage
import com.example.ui.components.rememberIsDarkSurface
import com.example.ui.theme.StatusCompleted
import com.example.ui.theme.StatusHiatus
import com.example.ui.theme.StatusOngoing

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
    val isDark = rememberIsDarkSurface()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row {
                        Spacer(Modifier.width(6.dp))
                        KomaCircularGlassButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack
                        )
                    }
                },
                title = {
                    Text(
                        manga?.title ?: "Series Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    KomaCircularGlassButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Refresh chapters",
                        onClick = viewModel::refresh
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.DownloadForOffline,
                        contentDescription = "Download unread",
                        onClick = viewModel::downloadUnreadChapters
                    )
                    Spacer(Modifier.width(4.dp))
                    KomaCircularGlassButton(
                        icon = Icons.Default.PlaylistAdd,
                        contentDescription = "Download chapter range",
                        onClick = { showRangeDialog = true }
                    )
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            val nextChapter = viewModel.getNextChapterToRead()
            if (manga != null && nextChapter != null) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenReader(manga.id, nextChapter.id) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = {
                        Text(
                            if (nextChapter.lastPageRead > 0) "Resume" else "Read",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("start_reading_fab")
                )
            }
        }
    ) { padding ->
        if (manga == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(210.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
                ChapterListSkeleton(count = 6)
            }
            return@Scaffold
        }

        val totalCount = state.chapters.size
        val unreadCount = state.chapters.count { !it.isRead }
        val readCount = (totalCount - unreadCount).coerceAtLeast(0)
        val progressPct = if (totalCount > 0) ((readCount * 100f) / totalCount).toInt() else 0

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.isRefreshing) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            state.statusBanner?.let { msg ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = viewModel::clearBanner, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            state.error?.let { err ->
                item {
                    ErrorRetryBanner(message = err, onRetry = viewModel::refresh)
                }
            }

            // Atmospheric Hero Backdrop + Floating Frosted Detail Sheet
            item {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (manga.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = manga.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .alpha(if (isDark) 0.28f else 0.20f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )

                    LiquidGlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(26.dp),
                        shadowElevation = 10.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MangaCoverImage(
                                    coverUrl = manga.coverUrl,
                                    contentDescription = "${manga.title} cover",
                                    modifier = Modifier
                                        .width(112.dp)
                                        .aspectRatio(3f / 4.2f)
                                        .clip(RoundedCornerShape(18.dp))
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (manga.isDemoData) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            shape = CircleShape
                                        ) {
                                            Text(
                                                "DEMO SAMPLE",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        manga.title,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    if (manga.altTitle.isNotBlank()) {
                                        Text(
                                            manga.altTitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (manga.author.isNotBlank()) {
                                        Text(
                                            "By ${manga.author}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val parsedStatus = MangaStatus.fromString(manga.status)
                                    val statusColor = when (parsedStatus) {
                                        MangaStatus.ONGOING -> StatusOngoing
                                        MangaStatus.COMPLETED -> StatusCompleted
                                        MangaStatus.HIATUS -> StatusHiatus
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Text(
                                            "${parsedStatus.label} • ${manga.sourceName}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            // 3-Column Bento Stats Strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                DetailMetricColumn(value = "$totalCount", label = "Chapters")
                                DetailMetricColumn(value = "$unreadCount", label = "Unread")
                                DetailMetricColumn(value = "$progressPct%", label = "Progress")
                            }
                        }
                    }
                }
            }

            // Primary Action Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionPill(
                        icon = if (manga.inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (manga.inLibrary) "In Library" else "Add",
                        highlighted = manga.inLibrary,
                        onClick = viewModel::toggleLibrary,
                        tag = "toggle_library_button",
                        modifier = Modifier.weight(1f)
                    )
                    ActionPill(
                        icon = Icons.Default.FolderSpecial,
                        label = "Categories",
                        highlighted = state.assignedCategoryIds.isNotEmpty(),
                        onClick = { showCategoryDialog = true },
                        tag = "assign_categories_button",
                        modifier = Modifier.weight(1f)
                    )
                    ActionPill(
                        icon = if (manga.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        label = if (manga.isBookmarked) "Saved" else "Bookmark",
                        highlighted = manga.isBookmarked,
                        onClick = viewModel::toggleBookmark,
                        modifier = Modifier.weight(1f)
                    )
                    ActionPill(
                        icon = Icons.Default.Sync,
                        label = if (state.trackingEntries.isEmpty()) "Track" else "Track (${state.trackingEntries.size})",
                        highlighted = state.trackingEntries.isNotEmpty(),
                        onClick = { showTrackingSheet = true },
                        tag = "tracking_button",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Synopsis & Genres Card
            val genres = manga.genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (manga.description.isNotBlank() || genres.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (manga.description.isNotBlank()) {
                                Text(
                                    text = manga.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expandSynopsis) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { expandSynopsis = !expandSynopsis }
                                )
                            }
                            if (genres.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    genres.forEach { g ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = CircleShape,
                                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                g,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Chapter Header + Controls
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${state.chapters.size} Chapters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = state.showUnreadOnly,
                            onClick = viewModel::toggleUnreadFilter,
                            shape = CircleShape,
                            label = { Text("Unread") }
                        )
                        KomaCircularGlassButton(
                            icon = Icons.Default.SwapVert,
                            contentDescription = "Reverse chapter order",
                            onClick = viewModel::toggleChapterSort
                        )
                    }
                }
            }

            if (state.chapters.isEmpty() && state.isRefreshing) {
                item { ChapterListSkeleton(count = 5) }
            } else if (state.chapters.isEmpty()) {
                item {
                    KomaEmptyState(
                        title = "No Chapters Found",
                        subtitle = "Tap Refresh above to fetch chapters from ${manga.sourceName}.",
                        actionLabel = "Refresh",
                        onAction = viewModel::refresh,
                        showIllustration = false
                    )
                }
            } else {
                items(state.chapters, key = { it.id }) { chapter ->
                    ChapterListRow(
                        chapter = chapter,
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
            title = { Text("Set Categories") },
            text = {
                Column {
                    state.categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
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
                Button(onClick = {
                    viewModel.setCategories(selected.toList())
                    showCategoryDialog = false
                }) { Text("Save") }
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
            title = { Text("Download Chapter Range") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        label = { Text("From Ch.") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("To Ch.") },
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val f = fromText.toDoubleOrNull() ?: 1.0
                    val t = toText.toDoubleOrNull() ?: f
                    viewModel.downloadChapterRange(f, t)
                    showRangeDialog = false
                }) { Text("Queue") }
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

@Composable
private fun DetailMetricColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = ""
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            0.75.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChapterListRow(
    chapter: ChapterEntity,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            0.75.dp,
            if (!chapter.isRead) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("chapter_row_${chapter.id}")
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!chapter.isRead) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (chapter.isBookmarked) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (chapter.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val meta = buildString {
                    if (chapter.scanlator.isNotBlank()) append(chapter.scanlator)
                    if (chapter.lastPageRead > 0 && !chapter.isRead) {
                        if (isNotEmpty()) append(" • ")
                        append("Page ${chapter.lastPageRead + 1}")
                    }
                    if (chapter.isDownloaded) {
                        if (isNotEmpty()) append(" • ")
                        append("Offline Ready")
                    }
                }
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (chapter.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "Bookmark chapter",
                    tint = if (chapter.isBookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleRead) {
                Icon(
                    if (chapter.isRead) Icons.Default.VisibilityOff else Icons.Default.CheckCircleOutline,
                    contentDescription = if (chapter.isRead) "Mark unread" else "Mark read"
                )
            }
            if (chapter.isDownloaded) {
                IconButton(onClick = onDeleteDownload) {
                    Icon(Icons.Default.DownloadDone, contentDescription = "Delete download", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download chapter")
                }
            }
        }
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
