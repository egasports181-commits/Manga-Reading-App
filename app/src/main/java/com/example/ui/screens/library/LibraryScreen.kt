package com.example.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.CategoryEntity
import com.example.domain.model.LibrarySort
import com.example.domain.model.MangaStatus
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.MangaCoverCard

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
            } else {
                TopAppBar(
                    title = {
                        if (showSearch) {
                            OutlinedTextField(
                                value = state.filter.searchQuery,
                                onValueChange = viewModel::updateSearchQuery,
                                placeholder = { Text("Filter library...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("Library", style = MaterialTheme.typography.headlineMedium)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.updateSearchQuery("")
                        }) {
                            Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, contentDescription = "Filter library")
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

            // Category Tabs
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
                    Tab(
                        selected = state.selectedCategoryId == cat.id,
                        onClick = { viewModel.selectCategory(cat.id) },
                        text = { Text(cat.name) }
                    )
                }
            }

            if (state.mangaList.isEmpty()) {
                KomaEmptyState(
                    title = "Empty Category",
                    subtitle = "Add manga from Browse & Extensions, or adjust your active filters.",
                    actionLabel = "Browse Sources",
                    onAction = onNavigateToSources
                )
            } else if (state.isGridLayout) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
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
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.mangaList, key = { it.id }) { card ->
                        val selected = card.id in state.selectedMangaIds
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (inSelectionMode) viewModel.toggleSelection(card.id)
                                    else onOpenManga(card.id)
                                }
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
                                            onCheckedChange = { viewModel.toggleSelection(card.id) }
                                        )
                                    }
                                }
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
            onRename = viewModel::renameCategory,
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
    onCreate: (String) -> Unit,
    onRename: (CategoryEntity, String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Long) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var editingCat by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

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
                            onCreate(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Add") }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(categories, key = { _, c -> c.id }) { idx, cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
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
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename")
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
            title = { Text("Rename Category") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(cat, renameText)
                    editingCat = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingCat = null }) { Text("Cancel") }
            }
        )
    }
}
