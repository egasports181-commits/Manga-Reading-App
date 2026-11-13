package com.example.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.CategoryEntity
import com.example.domain.model.LibrarySort
import com.example.domain.model.MangaStatus
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.components.MangaCoverCard

private val CATEGORY_COLOR_PRESETS = listOf(
    0xFFE11D48L,
    0xFF2563EBL,
    0xFF059669L,
    0xFFD97706L,
    0xFF7C3AEDL,
    0xFF0891B2L
)

private val CATEGORY_ICON_PRESETS = listOf(
    "folder" to Icons.Default.Folder,
    "star" to Icons.Default.Star,
    "bookmark" to Icons.Default.Bookmark,
    "favorite" to Icons.Default.Favorite,
    "bolt" to Icons.Default.Bolt,
    "menu_book" to Icons.Default.MenuBook
)

private fun parseCategoryColor(argb: Long, fallback: Color): Color {
    return if (argb != 0L) Color(argb) else fallback
}

private fun resolveCategoryIcon(iconName: String): ImageVector {
    return CATEGORY_ICON_PRESETS.firstOrNull { it.first == iconName }?.second ?: Icons.Default.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenManga: (String) -> Unit,
    onNavigateToSources: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showFilterSortSheet by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showBulkCategoryDialog by remember { mutableStateOf(false) }

    val inSelectionMode = state.selectedMangaIds.isNotEmpty()

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedMangaIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAllVisible) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { showBulkCategoryDialog = true }) {
                            Icon(Icons.Default.Label, contentDescription = "Assign categories")
                        }
                        IconButton(onClick = { viewModel.bulkMarkRead(true) }) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Mark read")
                        }
                        IconButton(onClick = viewModel::bulkDownloadSelected) {
                            Icon(Icons.Default.Download, contentDescription = "Download all")
                        }
                        IconButton(onClick = viewModel::bulkRemove) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove from library")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else if (showSearch) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.filter.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            placeholder = { Text("Filter library...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = false
                            viewModel.updateSearchQuery("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                LiquidGlassTopBar(
                    title = "Library",
                    subtitle = "${state.mangaList.size} series",
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Filter library")
                        }
                        IconButton(
                            onClick = { showFilterSortSheet = true },
                            modifier = Modifier.testTag("library_filter_button")
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Sort and filter")
                        }
                        IconButton(
                            onClick = { showCategoryManager = true },
                            modifier = Modifier.testTag("library_categories_button")
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Manage categories")
                        }
                        IconButton(onClick = viewModel::toggleLayoutMode) {
                            Icon(
                                if (state.isGridLayout) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle grid/list"
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            state.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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

            // Category Tabs with Color & Icon badges
            ScrollableTabRow(
                selectedTabIndex = if (state.selectedCategoryId == null) 0 else {
                    (state.categories.indexOfFirst { it.id == state.selectedCategoryId } + 1).coerceAtLeast(0)
                },
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = state.selectedCategoryId == null,
                    onClick = { viewModel.selectCategory(null) },
                    text = { Text("All") }
                )
                state.categories.forEach { cat ->
                    val tint = parseCategoryColor(cat.colorHex, MaterialTheme.colorScheme.primary)
                    Tab(
                        selected = state.selectedCategoryId == cat.id,
                        onClick = { viewModel.selectCategory(cat.id) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = resolveCategoryIcon(cat.iconName),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(cat.name)
                            }
                        }
                    )
                }
            }

            // Smart Views + Group By Source Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "all" to "All",
                    "unread" to "Unread",
                    "recent_read" to "Recently Read",
                    "recent_updated" to "Recently Updated",
                    "downloaded" to "Downloaded",
                    "completed" to "Completed"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = state.smartView == key,
                        onClick = { viewModel.setSmartView(key) },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = state.groupBySource,
                    onClick = { viewModel.setGroupBySource(!state.groupBySource) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Group by Source") }
                )
            }

            if (state.mangaList.isEmpty()) {
                KomaEmptyState(
                    title = "Empty Category",
                    subtitle = "Add manga from Browse & Extensions, or adjust your active filters.",
                    actionLabel = "Browse Sources",
                    onAction = onNavigateToSources
                )
            } else if (state.isGridLayout) {
                val grouped = remember(state.mangaList, state.groupBySource) {
                    if (state.groupBySource) state.mangaList.groupBy { it.sourceName.ifBlank { "Unknown Source" } }
                    else emptyMap()
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.groupBySource) {
                        grouped.forEach { (sourceName, cards) ->
                            item(key = "hdr_$sourceName", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "$sourceName (${cards.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(cards, key = { it.id }) { card ->
                                MangaCoverCard(
                                    item = card,
                                    isSelected = card.id in state.selectedMangaIds,
                                    onClick = {
                                        if (inSelectionMode) viewModel.toggleSelection(card.id)
                                        else onOpenManga(card.id)
                                    },
                                    onLongClick = { viewModel.toggleSelection(card.id) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        items(state.mangaList, key = { it.id }) { card ->
                            MangaCoverCard(
                                item = card,
                                isSelected = card.id in state.selectedMangaIds,
                                onClick = {
                                    if (inSelectionMode) viewModel.toggleSelection(card.id)
                                    else onOpenManga(card.id)
                                },
                                onLongClick = { viewModel.toggleSelection(card.id) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                val grouped = remember(state.mangaList, state.groupBySource) {
                    if (state.groupBySource) state.mangaList.groupBy { it.sourceName.ifBlank { "Unknown Source" } }
                    else emptyMap()
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.groupBySource) {
                        grouped.forEach { (sourceName, cards) ->
                            item(key = "list_hdr_$sourceName") {
                                Text(
                                    text = "$sourceName (${cards.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            items(cards, key = { it.id }) { card ->
                                val selected = card.id in state.selectedMangaIds
                                LibraryListRow(
                                    card = card,
                                    selected = selected,
                                    onClick = {
                                        if (inSelectionMode) viewModel.toggleSelection(card.id)
                                        else onOpenManga(card.id)
                                    },
                                    onToggleSelect = { viewModel.toggleSelection(card.id) }
                                )
                            }
                        }
                    } else {
                        items(state.mangaList, key = { it.id }) { card ->
                            val selected = card.id in state.selectedMangaIds
                            LibraryListRow(
                                card = card,
                                selected = selected,
                                onClick = {
                                    if (inSelectionMode) viewModel.toggleSelection(card.id)
                                    else onOpenManga(card.id)
                                },
                                onToggleSelect = { viewModel.toggleSelection(card.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSortSheet) {
        LibraryFilterSortSheet(
            state = state,
            onDismiss = { showFilterSortSheet = false },
            onSortChange = viewModel::setSort,
            onFilterChange = viewModel::updateFilter
        )
    }

    if (showCategoryManager) {
        CategoryManagerDialog(
            categories = state.categories,
            onDismiss = { showCategoryManager = false },
            onCreate = viewModel::createCategory,
            onUpdateAppearance = viewModel::updateCategoryAppearance,
            onMove = viewModel::moveCategory,
            onDelete = viewModel::deleteCategory
        )
    }

    if (showBulkCategoryDialog) {
        val chosen = remember { mutableStateListOf<Long>() }
        AlertDialog(
            onDismissRequest = { showBulkCategoryDialog = false },
            title = { Text("Assign Categories") },
            text = {
                Column {
                    state.categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (cat.id in chosen) chosen.remove(cat.id) else chosen.add(cat.id)
                            }
                        ) {
                            Checkbox(
                                checked = cat.id in chosen,
                                onCheckedChange = {
                                    if (cat.id in chosen) chosen.remove(cat.id) else chosen.add(cat.id)
                                }
                            )
                            Text(cat.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.bulkAssignCategories(chosen.toList())
                    showBulkCategoryDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkCategoryDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LibraryListRow(
    card: com.example.ui.components.MangaCardUi,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = { Text(card.title) },
            supportingContent = { Text("${card.sourceName} • ${card.latestChapter}") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (card.unreadCount > 0) {
                        Badge { Text("${card.unreadCount}") }
                    }
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterSortSheet(
    state: LibraryUiState,
    onDismiss: () -> Unit,
    onSortChange: (LibrarySort, Boolean) -> Unit,
    onFilterChange: (LibraryFilterState) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sort & Filter Library", style = MaterialTheme.typography.headlineSmall)

            Text("Sort By", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibrarySort.entries.forEach { sortOpt ->
                    val selected = state.sort == sortOpt
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val asc = if (selected) !state.sortAscending else false
                            onSortChange(sortOpt, asc)
                        },
                        label = {
                            Text(
                                if (selected) "${sortOpt.label} ${if (state.sortAscending) "↑" else "↓"}"
                                else sortOpt.label
                            )
                        }
                    )
                }
            }

            Text("Quick Filters", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter.unreadOnly,
                    onClick = { onFilterChange(state.filter.copy(unreadOnly = !state.filter.unreadOnly)) },
                    label = { Text("Unread Only") }
                )
                FilterChip(
                    selected = state.filter.downloadedOnly,
                    onClick = { onFilterChange(state.filter.copy(downloadedOnly = !state.filter.downloadedOnly)) },
                    label = { Text("Downloaded Only") }
                )
            }

            if (state.availableGenres.isNotEmpty()) {
                Text("Genre", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filter.genre == null,
                        onClick = { onFilterChange(state.filter.copy(genre = null)) },
                        label = { Text("All") }
                    )
                    state.availableGenres.forEach { g ->
                        FilterChip(
                            selected = state.filter.genre == g,
                            onClick = { onFilterChange(state.filter.copy(genre = g)) },
                            label = { Text(g) }
                        )
                    }
                }
            }

            if (state.availableSources.isNotEmpty()) {
                Text("Source", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filter.sourceName == null,
                        onClick = { onFilterChange(state.filter.copy(sourceName = null)) },
                        label = { Text("All") }
                    )
                    state.availableSources.forEach { src ->
                        FilterChip(
                            selected = state.filter.sourceName == src,
                            onClick = { onFilterChange(state.filter.copy(sourceName = src)) },
                            label = { Text(src) }
                        )
                    }
                }
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.filter.status == null,
                    onClick = { onFilterChange(state.filter.copy(status = null)) },
                    label = { Text("Any") }
                )
                listOf(MangaStatus.ONGOING, MangaStatus.COMPLETED, MangaStatus.HIATUS).forEach { st ->
                    FilterChip(
                        selected = state.filter.status == st,
                        onClick = { onFilterChange(state.filter.copy(status = st)) },
                        label = { Text(st.label) }
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onFilterChange(LibraryFilterState()) }) { Text("Clear Filters") }
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CategoryManagerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, Long, String) -> Unit,
    onUpdateAppearance: (CategoryEntity, String, Long, String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Long) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(CATEGORY_COLOR_PRESETS.first()) }
    var newIcon by remember { mutableStateOf("folder") }
    var editingCat by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf(CATEGORY_COLOR_PRESETS.first()) }
    var editIcon by remember { mutableStateOf("folder") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New category name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onCreate(newName, newColor, newIcon)
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Add") }
                }

                // Color & Icon pickers for new category
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CATEGORY_COLOR_PRESETS.forEach { hex ->
                        val c = parseCategoryColor(hex, MaterialTheme.colorScheme.primary)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (newColor == hex) 2.5.dp else 0.dp,
                                    color = if (newColor == hex) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { newColor = hex }
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CATEGORY_ICON_PRESETS.forEach { (key, icon) ->
                        IconButton(
                            onClick = { newIcon = key },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                icon,
                                contentDescription = key,
                                tint = if (newIcon == key) parseCategoryColor(newColor, MaterialTheme.colorScheme.primary)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(categories, key = { _, c -> c.id }) { idx, cat ->
                        val catTint = parseCategoryColor(cat.colorHex, MaterialTheme.colorScheme.primary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = resolveCategoryIcon(cat.iconName),
                                contentDescription = null,
                                tint = catTint,
                                modifier = Modifier.size(18.dp).padding(end = 6.dp)
                            )
                            Text(cat.name, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { onMove(idx, idx - 1) },
                                enabled = idx > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { onMove(idx, idx + 1) },
                                enabled = idx < categories.lastIndex
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                            IconButton(onClick = {
                                editingCat = cat
                                renameText = cat.name
                                editColor = cat.colorHex
                                editIcon = cat.iconName
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            if (!cat.isDefault) {
                                IconButton(onClick = { onDelete(cat.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    editingCat?.let { cat ->
        AlertDialog(
            onDismissRequest = { editingCat = null },
            title = { Text("Edit Category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CATEGORY_COLOR_PRESETS.forEach { hex ->
                            val c = parseCategoryColor(hex, MaterialTheme.colorScheme.primary)
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (editColor == hex) 2.5.dp else 0.dp,
                                        color = if (editColor == hex) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { editColor = hex }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CATEGORY_ICON_PRESETS.forEach { (key, icon) ->
                            IconButton(
                                onClick = { editIcon = key },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = key,
                                    tint = if (editIcon == key) parseCategoryColor(editColor, MaterialTheme.colorScheme.primary)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateAppearance(cat, renameText, editColor, editIcon)
                    editingCat = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingCat = null }) { Text("Cancel") }
            }
        )
    }
}
