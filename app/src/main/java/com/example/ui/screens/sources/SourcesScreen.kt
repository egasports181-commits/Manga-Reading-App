package com.example.ui.screens.sources

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.domain.model.*
import com.example.extension.ExtensionCompatibilityResolver
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.components.MangaCardUi
import com.example.ui.components.MangaCoverCard
import com.example.ui.theme.StatusCompleted
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusHiatus
import com.example.ui.theme.StatusOngoing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onOpenSource: (String) -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Sources", "Global Search", "Extensions")
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var showImportRepoDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var inspectPackage by remember { mutableStateOf<ExtensionPackageEntity?>(null) }

    val context = LocalContext.current
    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setLocalSafFolder(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            Column {
                LiquidGlassTopBar(
                    title = "Browse & Extensions",
                    subtitle = "${state.repos.count { it.isEnabled }} active repos • ${state.packages.size} indexed",
                    actions = {
                        IconButton(onClick = onOpenDiagnostics) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = "Network & repo diagnostics")
                        }
                        if (selectedTab == 0) {
                            IconButton(onClick = { viewModel.checkSourceAvailability() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Recheck source health")
                            }
                        } else if (selectedTab == 2) {
                            IconButton(
                                onClick = { showAddRepoDialog = true },
                                modifier = Modifier.testTag("add_repo_button")
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Add repository")
                            }
                        }
                    }
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1) },
                            modifier = Modifier.testTag("sources_tab_$index")
                        )
                    }
                }
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearBannerMessages, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            state.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearBannerMessages, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> SourcesDirectoryTab(
                    state = state,
                    onLangFilter = viewModel::setLanguageFilter,
                    onToggleSourceEnabled = viewModel::setSourceEnabled,
                    onOpenSource = onOpenSource,
                    onPickLocalSaf = { safFolderLauncher.launch(null) },
                    onOpenExtensionsTab = { selectedTab = 2 }
                )
                1 -> GlobalSearchTab(
                    state = state,
                    onQueryChange = viewModel::updateGlobalQuery,
                    onSearch = viewModel::executeGlobalSearch,
                    onOpenFilters = { showFilterSheet = true },
                    onToggleSearchSource = viewModel::toggleSearchSourceEnabled,
                    onToggleSaveSearch = viewModel::toggleSavedSearch,
                    onDeleteSearch = viewModel::deleteSearchHistory,
                    onSelectHistory = {
                        viewModel.updateGlobalQuery(it)
                        viewModel.executeGlobalSearch()
                    },
                    onOpenMangaCard = { card -> viewModel.openSourceManga(card, onOpenManga) }
                )
                2 -> ExtensionsManagerTab(
                    state = state,
                    onSelectSubTab = viewModel::selectExtSubTab,
                    onSearchChange = viewModel::updateExtSearch,
                    onLangFilterChange = viewModel::updateExtLangFilter,
                    onRepoFilterChange = viewModel::updateExtRepoFilter,
                    onCompatFilterChange = viewModel::updateExtCompatFilter,
                    onSortChange = viewModel::updateExtSort,
                    onAddRepo = { showAddRepoDialog = true },
                    onRefreshAllRepos = { viewModel.refreshAllEnabledRepos(false) },
                    onRetryFailedRepos = { viewModel.refreshAllEnabledRepos(true) },
                    onRestoreBuiltIns = viewModel::restoreBuiltInRepos,
                    onExportRepos = viewModel::exportRepositories,
                    onImportRepos = { showImportRepoDialog = true },
                    onOpenDiagnostics = onOpenDiagnostics,
                    onToggleRepoEnabled = viewModel::setRepoEnabled,
                    onRefreshRepo = viewModel::refreshRepository,
                    onTrustRepo = viewModel::trustRepository,
                    onRemoveRepo = viewModel::removeRepository,
                    onInstallOrUpdate = viewModel::installOrUpdateExtension,
                    onUpdateAll = viewModel::updateAllExtensions,
                    onToggleEnabled = viewModel::setExtensionEnabled,
                    onUninstall = viewModel::uninstallExtension,
                    onInspect = { inspectPackage = it }
                )
            }
        }
    }

    if (showAddRepoDialog) {
        AddRepositoryDialog(
            onDismiss = { showAddRepoDialog = false },
            onAdd = { url, name, fp ->
                viewModel.addRepository(url, name, fp)
                showAddRepoDialog = false
            }
        )
    }

    if (showImportRepoDialog) {
        ImportRepositoryDialog(
            onDismiss = { showImportRepoDialog = false },
            onImport = { raw ->
                viewModel.importRepositories(raw)
                showImportRepoDialog = false
            }
        )
    }

    state.exportedReposJson?.let { json ->
        ExportRepositoryDialog(
            json = json,
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Koma Repositories", json))
                viewModel.dismissExportJson()
            },
            onDismiss = viewModel::dismissExportJson
        )
    }

    if (showFilterSheet) {
        SearchFilterBottomSheet(
            initial = state.globalFilters,
            onDismiss = { showFilterSheet = false },
            onApply = { filters ->
                viewModel.updateGlobalFilters(filters)
                showFilterSheet = false
                viewModel.executeGlobalSearch()
            }
        )
    }

    inspectPackage?.let { pkg ->
        ExtensionInspectDialog(
            pkg = pkg,
            onDismiss = { inspectPackage = null },
            onInstallOrVerify = {
                viewModel.installOrUpdateExtension(pkg)
                inspectPackage = null
            },
            onOpenApkInBrowser = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        )
    }
}

@Composable
private fun SourcesDirectoryTab(
    state: SourcesUiState,
    onLangFilter: (String) -> Unit,
    onToggleSourceEnabled: (String, Boolean) -> Unit,
    onOpenSource: (String) -> Unit,
    onPickLocalSaf: () -> Unit,
    onOpenExtensionsTab: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("all" to "All Langs", "multi" to "Multi", "en" to "EN", "local" to "Local").forEach { (code, label) ->
                    FilterChip(
                        selected = state.selectedLangFilter == code,
                        onClick = { onLangFilter(code) },
                        label = { Text(label) }
                    )
                }
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local Manga / CBZ Folder (SAF)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (state.safLocalFolderUri.isBlank()) {
                                "Select a device folder containing .cbz archives or chapter image folders"
                            } else {
                                "Linked: ${state.safLocalFolderUri.takeLast(36)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onPickLocalSaf) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Select")
                    }
                }
            }
        }

        items(state.sources, key = { it.id }) { source ->
            val health = source.healthStatus
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = source.isEnabled) { onOpenSource(source.id) }
                    .testTag("source_item_${source.id}"),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when {
                                        source.id == "local_saf" -> Icons.Default.FolderZip
                                        source.isDemo -> Icons.Default.Science
                                        else -> Icons.Default.Public
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                if (source.isDemo) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "SAMPLE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${source.category} • ${source.language.uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (source.isEnabled && health != null) {
                                val statusColor = if (health.isOnline) StatusOngoing else StatusError
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Surface(modifier = Modifier.size(7.dp), shape = CircleShape, color = statusColor) {}
                                    Text(
                                        text = if (health.isOnline) {
                                            "Online • ${health.latencyMs}ms"
                                        } else {
                                            "Offline • ${health.errorReason ?: "Unreachable"}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = source.isEnabled,
                            onCheckedChange = { onToggleSourceEnabled(source.id, it) }
                        )
                    }
                }
            }
        }

        if (state.verifiedExtensionDescriptors.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Verified Mihon Extension Catalogs (${state.verifiedExtensionDescriptors.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = onOpenExtensionsTab) {
                        Text("Manage")
                    }
                }
            }
            items(state.verifiedExtensionDescriptors, key = { "desc_${it.pkg.pkgId}" }) { desc ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(desc.pkg.name, style = MaterialTheme.typography.titleSmall)
                            Surface(
                                color = StatusHiatus.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (desc.canExecuteInKoma) "Executable" else "APK Verified • Restricted",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusHiatus,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (desc.sources.isNotEmpty()) {
                            Text(
                                text = "Catalog Sources: " + desc.sources.joinToString(", ") { "${it.name} (${it.lang})" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchTab(
    state: SourcesUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onToggleSearchSource: (String) -> Unit,
    onToggleSaveSearch: (com.example.data.local.entity.SearchHistoryEntity) -> Unit,
    onDeleteSearch: (Long) -> Unit,
    onSelectHistory: (String) -> Unit,
    onOpenMangaCard: (MangaCardUi) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = state.globalQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search title or author across sources") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.globalQuery.isNotBlank()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("global_search_input")
                )
                FilledTonalIconButton(
                    onClick = onOpenFilters,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Advanced search filters")
                }
                Button(
                    onClick = onSearch,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("global_search_submit")
                ) {
                    Text("Go")
                }
            }
        }

        // Per-source inclusion toggles
        if (state.sources.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.sources.filter { it.isEnabled }.forEach { src ->
                    val included = src.id !in state.disabledSearchSourceIds
                    FilterChip(
                        selected = included,
                        onClick = { onToggleSearchSource(src.id) },
                        label = { Text(src.name) },
                        leadingIcon = if (included) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }

        // Live query suggestions
        if (state.searchSuggestions.isNotEmpty() && state.globalQuery.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.searchSuggestions.forEach { sug ->
                    SuggestionChip(
                        onClick = { onSelectHistory(sug) },
                        label = { Text(sug) }
                    )
                }
            }
        }

        if (state.isSearchingGlobal) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.globalSourceErrors.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.globalSourceErrors.forEach { (src, err) ->
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    "$src: $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusError,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (state.duplicateTitlesAcrossSources.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Duplicate series detected across multiple sources (${state.duplicateTitlesAcrossSources.size} title(s)) — check source badges on cards.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            if (state.globalResults.isEmpty() && !state.isSearchingGlobal) {
                if (state.searchHistory.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent & Saved Searches",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    items(state.searchHistory, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.query) },
                            leadingContent = {
                                Icon(
                                    if (entry.isSaved) Icons.Default.Bookmark else Icons.Default.History,
                                    contentDescription = null,
                                    tint = if (entry.isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onToggleSaveSearch(entry) }) {
                                        Icon(
                                            if (entry.isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = "Save search"
                                        )
                                    }
                                    IconButton(onClick = { onDeleteSearch(entry.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onSelectHistory(entry.query) }
                        )
                    }
                } else {
                    item {
                        KomaEmptyState(
                            title = "Global Multi-Source Search",
                            subtitle = "Enter a manga title and tap Go to query selected active sources simultaneously with genre, status, year, and rating filters."
                        )
                    }
                }
            }

            state.globalResults.forEach { (sourceName, cards) ->
                item(key = "header_$sourceName") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "$sourceName (${cards.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(cards, key = { it.id }) { card ->
                                MangaCoverCard(item = card, onClick = { onOpenMangaCard(card) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionsManagerTab(
    state: SourcesUiState,
    onSelectSubTab: (ExtensionStoreTab) -> Unit,
    onSearchChange: (String) -> Unit,
    onLangFilterChange: (String) -> Unit,
    onRepoFilterChange: (Long?) -> Unit,
    onCompatFilterChange: (ExtensionCompatFilter) -> Unit,
    onSortChange: (ExtensionSortOption) -> Unit,
    onAddRepo: () -> Unit,
    onRefreshAllRepos: () -> Unit,
    onRetryFailedRepos: () -> Unit,
    onRestoreBuiltIns: () -> Unit,
    onExportRepos: () -> Unit,
    onImportRepos: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onToggleRepoEnabled: (ExtensionRepoEntity, Boolean) -> Unit,
    onRefreshRepo: (ExtensionRepoEntity) -> Unit,
    onTrustRepo: (ExtensionRepoEntity, Boolean) -> Unit,
    onRemoveRepo: (ExtensionRepoEntity) -> Unit,
    onInstallOrUpdate: (ExtensionPackageEntity) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onInspect: (ExtensionPackageEntity) -> Unit
) {
    val updatesCount = state.packages.count { (it.isInstalled || it.localApkPath.isNotBlank()) && it.hasUpdate }
    val failedRepoCount = state.repos.count { it.isEnabled && !it.lastError.isNullOrBlank() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExtensionStoreTab.entries.forEach { subTab ->
                    val label = if (subTab == ExtensionStoreTab.UPDATES && updatesCount > 0) {
                        "${subTab.label} ($updatesCount)"
                    } else if (subTab == ExtensionStoreTab.REPOSITORIES) {
                        "${subTab.label} (${state.repos.size})"
                    } else {
                        subTab.label
                    }
                    FilterChip(
                        selected = state.extSubTab == subTab,
                        onClick = { onSelectSubTab(subTab) },
                        label = { Text(label) }
                    )
                }
            }
        }

        if (state.isRefreshingRepos) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (state.extSubTab == ExtensionStoreTab.REPOSITORIES) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mihon & Koma Extension Repositories", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Supports HTTPS Mihon `index.pb` (Protobuf), `index.min.json`, companion `repo.json`, and Koma declarative JSON adapters. Built-in defaults stay seeded and independently syncable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = onRefreshAllRepos) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Refresh All")
                            }
                            if (failedRepoCount > 0) {
                                FilledTonalButton(onClick = onRetryFailedRepos) {
                                    Text("Retry Failed ($failedRepoCount)")
                                }
                            }
                            OutlinedButton(onClick = onAddRepo) { Text("Add URL") }
                            OutlinedButton(onClick = onRestoreBuiltIns) { Text("Restore Defaults") }
                            OutlinedButton(onClick = onExportRepos) { Text("Export JSON") }
                            OutlinedButton(onClick = onImportRepos) { Text("Import JSON") }
                            OutlinedButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                        }
                    }
                }
            }

            items(state.repos, key = { "repo_${it.id}" }) { repo ->
                val hasErr = !repo.lastError.isNullOrBlank()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    border = if (hasErr) BorderStroke(1.dp, StatusError.copy(alpha = 0.35f)) else null
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        repo.displayName.ifBlank { repo.name },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            repo.repoFormat,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (repo.isBuiltIn) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "DEFAULT",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    repo.indexUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = repo.isEnabled,
                                onCheckedChange = { onToggleRepoEnabled(repo, it) }
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${repo.extensionCount} extensions • FP: ${repo.signingKeyFingerprint.ifBlank { "Pending" }.take(16)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = repo.isTrusted,
                                    onClick = { onTrustRepo(repo, !repo.isTrusted) },
                                    label = { Text(if (repo.isTrusted) "Trusted" else "Untrusted") }
                                )
                                IconButton(onClick = { onRefreshRepo(repo) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh repo")
                                }
                                IconButton(onClick = { onRemoveRepo(repo) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove repo")
                                }
                            }
                        }

                        repo.lastError?.let { err ->
                            Text(err, style = MaterialTheme.typography.bodySmall, color = StatusError)
                        }
                    }
                }
            }

            if (state.installHistory.isNotEmpty()) {
                item {
                    Text(
                        "Recent APK / Adapter Audit Log",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(state.installHistory.take(15), key = { "hist_${it.id}" }) { entry ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${entry.extensionName} (${entry.action})", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    entry.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (entry.status) {
                                        "SUCCESS", "ACTIVE" -> StatusOngoing
                                        "VERIFIED", "VERIFIED_UNSUPPORTED_RUNTIME" -> StatusCompleted
                                        else -> StatusHiatus
                                    }
                                )
                            }
                            Text(
                                entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.extSearchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search extensions, pkg ID, or source") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = onRefreshAllRepos) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync")
                    }
                    if (updatesCount > 0) {
                        Button(onClick = onUpdateAll) {
                            Text("Update ($updatesCount)")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExtensionCompatFilter.entries.forEach { cf ->
                        FilterChip(
                            selected = state.extCompatFilter == cf,
                            onClick = { onCompatFilterChange(cf) },
                            label = { Text(cf.label) }
                        )
                    }
                }
            }

            if (state.allPackageLanguages.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.extLangFilter == "all",
                            onClick = { onLangFilterChange("all") },
                            label = { Text("All Langs") }
                        )
                        state.allPackageLanguages.take(12).forEach { lang ->
                            FilterChip(
                                selected = state.extLangFilter.equals(lang, true),
                                onClick = { onLangFilterChange(lang) },
                                label = { Text(lang.uppercase()) }
                            )
                        }
                    }
                }
            }

            if (state.repos.size > 1) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.extRepoFilterId == null,
                            onClick = { onRepoFilterChange(null) },
                            label = { Text("All Repos") }
                        )
                        state.repos.filter { it.isEnabled }.forEach { repo ->
                            FilterChip(
                                selected = state.extRepoFilterId == repo.id,
                                onClick = { onRepoFilterChange(repo.id) },
                                label = { Text(repo.displayName.ifBlank { repo.name }) }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExtensionSortOption.entries.forEach { sort ->
                        FilterChip(
                            selected = state.extSort == sort,
                            onClick = { onSortChange(sort) },
                            label = { Text("Sort: ${sort.label}") }
                        )
                    }
                }
            }

            if (state.packages.isEmpty()) {
                item {
                    KomaEmptyState(
                        title = "No Extension Packages Shown",
                        subtitle = "Tap Sync above to fetch extension catalogs from your 8 configured Mihon repositories, or adjust filters.",
                        actionLabel = "Sync Repositories",
                        onAction = onRefreshAllRepos,
                        showIllustration = false
                    )
                }
            } else {
                items(state.packages, key = { it.pkgId }) { pkg ->
                    val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
                    val isApkVerified = pkg.localApkPath.isNotBlank()
                    val downloadFraction = state.extDownloadProgress[pkg.pkgId]
                    val lifecycle = ExtensionCompatibilityResolver.resolveLifecycle(pkg)

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable { onInspect(pkg) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    if (pkg.iconUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = pkg.iconUrl,
                                            contentDescription = pkg.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isKoma) Icons.Default.Extension else Icons.Default.Android,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        pkg.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${pkg.language.uppercase()} • v${pkg.latestVersionName}${if (pkg.isNsfw) " • 18+" else ""} • ${pkg.author}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                val badgeColor = when (lifecycle.primaryState) {
                                    ExtensionLifecycleState.ACTIVE -> StatusOngoing
                                    ExtensionLifecycleState.COMPATIBLE -> StatusCompleted
                                    ExtensionLifecycleState.VERIFIED,
                                    ExtensionLifecycleState.DOWNLOADED -> StatusHiatus
                                    ExtensionLifecycleState.UNSUPPORTED -> StatusError
                                }
                                Surface(
                                    color = badgeColor.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = when (lifecycle.primaryState) {
                                            ExtensionLifecycleState.ACTIVE -> "Active"
                                            ExtensionLifecycleState.COMPATIBLE -> "Compatible"
                                            ExtensionLifecycleState.VERIFIED -> "Verified • Unsupported"
                                            ExtensionLifecycleState.DOWNLOADED -> "Downloaded"
                                            ExtensionLifecycleState.UNSUPPORTED -> "Unsupported APK"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                pkg.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            pkg.installError?.let { err ->
                                Text(err, style = MaterialTheme.typography.bodySmall, color = StatusError)
                            }

                            if (downloadFraction != null) {
                                LinearProgressIndicator(
                                    progress = { downloadFraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (pkg.isInstalled && lifecycle.isCompatible) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(
                                            checked = pkg.isEnabled,
                                            onCheckedChange = { onToggleEnabled(pkg.pkgId, it) }
                                        )
                                        Text(if (pkg.isEnabled) "Active" else "Installed (Paused)", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (pkg.hasUpdate) {
                                            Button(onClick = { onInstallOrUpdate(pkg) }) { Text("Update") }
                                        }
                                        OutlinedButton(onClick = { onUninstall(pkg.pkgId) }) { Text("Uninstall") }
                                    }
                                } else {
                                    Text(
                                        text = lifecycle.summaryText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isApkVerified) {
                                            OutlinedButton(onClick = { onUninstall(pkg.pkgId) }) {
                                                Text("Remove APK")
                                            }
                                        } else if (isKoma && lifecycle.isCompatible) {
                                            Button(onClick = { onInstallOrUpdate(pkg) }) {
                                                Text("Install")
                                            }
                                        } else if (!isKoma) {
                                            FilledTonalButton(
                                                onClick = { onInstallOrUpdate(pkg) },
                                                enabled = pkg.apkUrl.startsWith("https://") && downloadFraction == null
                                            ) {
                                                Text("Verify APK")
                                            }
                                        }
                                    }
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
private fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String, fingerprint: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Extension Repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter an HTTPS repository index URL (`index.pb`, `index.min.json`, `index.json`, or `koma_json_v1`). Duplicate URLs are prevented automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository Index URL (https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("repo_url_input")
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("Expected SHA-256 Fingerprint (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(url, name, fingerprint) },
                enabled = url.isNotBlank()
            ) {
                Text("Fetch & Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportRepositoryDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Repository JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a previously exported Koma repository JSON bundle.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    minLines = 5,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(raw) }, enabled = raw.isNotBlank()) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExportRepositoryDialog(
    json: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exported Repositories JSON") },
        text = {
            OutlinedTextField(
                value = json,
                onValueChange = {},
                readOnly = true,
                minLines = 6,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onCopy) { Text("Copy & Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ExtensionInspectDialog(
    pkg: ExtensionPackageEntity,
    onDismiss: () -> Unit,
    onInstallOrVerify: () -> Unit,
    onOpenApkInBrowser: (String) -> Unit
) {
    val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
    val lifecycle = ExtensionCompatibilityResolver.resolveLifecycle(pkg)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pkg.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Package ID: ${pkg.pkgId}", style = MaterialTheme.typography.bodySmall)
                Text("Format: ${ExtensionFormat.fromId(pkg.format).label}", style = MaterialTheme.typography.bodySmall)
                Text("Version: ${pkg.latestVersionName} (code ${pkg.latestVersionCode})", style = MaterialTheme.typography.bodySmall)
                if (pkg.libVersion.isNotBlank()) {
                    Text("Target Lib Version: ${pkg.libVersion}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Lifecycle: ${lifecycle.badges.joinToString(" • ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lifecycle.isCompatible) StatusOngoing else StatusHiatus
                )
                Text(
                    "States — Downloaded: ${lifecycle.isDownloaded} • Verified: ${lifecycle.isVerified} • Compatible: ${lifecycle.isCompatible} • Active: ${lifecycle.isActive}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pkg.compatibilityReason.isNotBlank()) {
                    Text(pkg.compatibilityReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pkg.installedSignatureSha256.isNotBlank()) {
                    Text("APK Cert SHA-256: ${pkg.installedSignatureSha256.take(24)}…", style = MaterialTheme.typography.labelSmall)
                }
                if (pkg.apkUrl.startsWith("https://")) {
                    TextButton(
                        onClick = { onOpenApkInBrowser(pkg.apkUrl) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open APK Download URL in Browser")
                    }
                }
            }
        },
        confirmButton = {
            if (isKoma && lifecycle.isCompatible && !pkg.isInstalled) {
                Button(onClick = onInstallOrVerify) { Text("Install") }
            } else if (!isKoma && pkg.apkUrl.startsWith("https://")) {
                FilledTonalButton(onClick = onInstallOrVerify) { Text("Download & Verify SHA-256") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterBottomSheet(
    initial: SearchFilters,
    onDismiss: () -> Unit,
    onApply: (SearchFilters) -> Unit
) {
    var selectedGenres by remember { mutableStateOf(initial.genres) }
    var selectedStatus by remember { mutableStateOf(initial.status) }
    var author by remember { mutableStateOf(initial.authorOrArtist) }
    var yearStr by remember { mutableStateOf(initial.releaseYear?.toString().orEmpty()) }
    var selectedLang by remember { mutableStateOf(initial.language ?: "en") }
    var contentRating by remember { mutableStateOf(initial.contentRating ?: "safe") }
    var sortBy by remember { mutableStateOf(initial.sortBy) }

    val availableGenres = listOf(
        "Action", "Adventure", "Comedy", "Cyberpunk", "Drama", "Fantasy",
        "Horror", "Mystery", "Romance", "Sci-Fi", "Slice of Life", "Supernatural"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Advanced Search Filters", style = MaterialTheme.typography.headlineSmall)

            Text("Sort By", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceSortOption.entries.forEach { opt ->
                    FilterChip(
                        selected = sortBy == opt,
                        onClick = { sortBy = opt },
                        label = { Text(opt.label) }
                    )
                }
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = selectedStatus == null, onClick = { selectedStatus = null }, label = { Text("Any") })
                listOf(MangaStatus.ONGOING, MangaStatus.COMPLETED, MangaStatus.HIATUS).forEach { st ->
                    FilterChip(
                        selected = selectedStatus == st,
                        onClick = { selectedStatus = st },
                        label = { Text(st.label) }
                    )
                }
            }

            Text("Genres", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableGenres.forEach { genre ->
                    val active = genre in selectedGenres
                    FilterChip(
                        selected = active,
                        onClick = {
                            selectedGenres = if (active) selectedGenres - genre else selectedGenres + genre
                        },
                        label = { Text(genre) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author / Artist") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = yearStr,
                    onValueChange = { yearStr = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("safe" to "Safe", "suggestive" to "Suggestive").forEach { (id, label) ->
                    FilterChip(
                        selected = contentRating == id,
                        onClick = { contentRating = id },
                        label = { Text(label) }
                    )
                }
                Spacer(Modifier.weight(1f))
                listOf("en" to "EN", "ja" to "JA", "all" to "All").forEach { (id, label) ->
                    FilterChip(
                        selected = selectedLang == id,
                        onClick = { selectedLang = id },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onApply(SearchFilters()) }) { Text("Reset") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onApply(
                            SearchFilters(
                                genres = selectedGenres,
                                status = selectedStatus,
                                language = selectedLang,
                                authorOrArtist = author.trim(),
                                releaseYear = yearStr.toIntOrNull(),
                                contentRating = contentRating,
                                sortBy = sortBy
                            )
                        )
                    }
                ) {
                    Text("Apply Filters")
                }
            }
        }
    }
}
