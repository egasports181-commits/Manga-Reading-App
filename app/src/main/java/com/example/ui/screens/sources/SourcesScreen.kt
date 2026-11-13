package com.example.ui.screens.sources

import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.domain.model.*
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.MangaCardUi
import com.example.ui.components.MangaCoverCard
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOngoing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onOpenSource: (String) -> Unit,
    onOpenManga: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Sources", "Global Search", "Extensions")
    var showAddRepoDialog by remember { mutableStateOf(false) }
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
                TopAppBar(
                    title = { Text("Browse & Extensions", style = MaterialTheme.typography.headlineMedium) },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { viewModel.checkSourceAvailability() }) {
                                Icon(Icons.Default.NetworkCheck, contentDescription = "Check source status")
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
                TabRow(selectedTabIndex = selectedTab) {
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
                    onCategoryFilter = viewModel::setCategoryFilter,
                    onOpenSource = onOpenSource,
                    onPickLocalSaf = { safFolderLauncher.launch(null) }
                )
                1 -> GlobalSearchTab(
                    state = state,
                    onQueryChange = viewModel::updateGlobalQuery,
                    onSearch = viewModel::executeGlobalSearch,
                    onOpenFilters = { showFilterSheet = true },
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
                    onSearchChange = viewModel::updateExtSearch,
                    onSortChange = viewModel::updateExtSort,
                    onInstalledOnlyChange = viewModel::toggleExtInstalledOnly,
                    onAddRepo = { showAddRepoDialog = true },
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
            onInstall = {
                viewModel.installOrUpdateExtension(pkg)
                inspectPackage = null
            }
        )
    }
}

@Composable
private fun SourcesDirectoryTab(
    state: SourcesUiState,
    onLangFilter: (String) -> Unit,
    onCategoryFilter: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onPickLocalSaf: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
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
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSource(source.id) }
                    .testTag("source_item_${source.id}"),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
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
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val statusColor = when (source.isAvailable) {
                            true -> StatusOngoing
                            false -> StatusError
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = statusColor
                        ) {}
                        Text(
                            text = when (source.isAvailable) {
                                true -> "Online"
                                false -> "Offline"
                                null -> "Checking"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
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
    onToggleSaveSearch: (com.example.data.local.entity.SearchHistoryEntity) -> Unit,
    onDeleteSearch: (Long) -> Unit,
    onSelectHistory: (String) -> Unit,
    onOpenMangaCard: (MangaCardUi) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.globalQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search title, alt title, or author across sources") },
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

        if (state.isSearchingGlobal) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                            subtitle = "Enter a manga title and tap Go to query all active sources simultaneously with genre, status, year, and rating filters."
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
    onSearchChange: (String) -> Unit,
    onSortChange: (ExtensionSortOption) -> Unit,
    onInstalledOnlyChange: (Boolean) -> Unit,
    onAddRepo: () -> Unit,
    onRefreshRepo: (ExtensionRepoEntity) -> Unit,
    onTrustRepo: (ExtensionRepoEntity, Boolean) -> Unit,
    onRemoveRepo: (ExtensionRepoEntity) -> Unit,
    onInstallOrUpdate: (ExtensionPackageEntity) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onInspect: (ExtensionPackageEntity) -> Unit
) {
    val updatesCount = state.packages.count { it.isInstalled && it.hasUpdate }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Sandboxed Extension Runtime", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        text = "Koma executes declarative JSON source descriptors (`koma_json_v1`) only. External Mihon/Tachiyomi APK indexes can be inspected for compatibility, but APKs and arbitrary remote JS are never silently installed or executed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddRepo) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Repo URL")
                        }
                        if (updatesCount > 0) {
                            FilledTonalButton(onClick = onUpdateAll) {
                                Text("Update All ($updatesCount)")
                            }
                        }
                    }
                }
            }
        }

        if (state.isRefreshingRepos) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (state.repos.isNotEmpty()) {
            item {
                Text("Configured Repositories (${state.repos.size})", style = MaterialTheme.typography.titleSmall)
            }
            items(state.repos, key = { "repo_${it.id}" }) { repo ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    repo.indexUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                IconButton(onClick = { onRefreshRepo(repo) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh repo")
                                }
                                IconButton(onClick = { onRemoveRepo(repo) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove repo")
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "SHA-256: ${repo.signingKeyFingerprint.ifBlank { "Pending" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = repo.isTrusted,
                                onClick = { onTrustRepo(repo, !repo.isTrusted) },
                                label = { Text(if (repo.isTrusted) "Trusted" else "Untrusted (Tap to Trust)") },
                                leadingIcon = {
                                    Icon(
                                        if (repo.isTrusted) Icons.Default.VerifiedUser else Icons.Default.GppMaybe,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        repo.lastError?.let { err ->
                            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.extSearchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Filter extensions by name, package ID, or author") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.extShowInstalledOnly,
                    onClick = { onInstalledOnlyChange(!state.extShowInstalledOnly) },
                    label = { Text("Installed Only") }
                )
                ExtensionSortOption.entries.forEach { sort ->
                    FilterChip(
                        selected = state.extSort == sort,
                        onClick = { onSortChange(sort) },
                        label = { Text(sort.label) }
                    )
                }
            }
        }

        if (state.packages.isEmpty()) {
            item {
                KomaEmptyState(
                    title = "No Extension Packages Indexed",
                    subtitle = "Add an HTTPS repository index URL above to inspect compatible Koma JSON source descriptors or external extension metadata.",
                    showIllustration = false
                )
            }
        } else {
            items(state.packages, key = { it.pkgId }) { pkg ->
                val isCompatible = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clickable { onInspect(pkg) }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pkg.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${pkg.language.uppercase()} • v${pkg.latestVersionName} • ${pkg.author}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = if (isCompatible) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isCompatible) "Koma JSON" else "APK Incompatible",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCompatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pkg.isInstalled) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Switch(
                                        checked = pkg.isEnabled,
                                        onCheckedChange = { onToggleEnabled(pkg.pkgId, it) }
                                    )
                                    Text(if (pkg.isEnabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (pkg.hasUpdate) {
                                        Button(onClick = { onInstallOrUpdate(pkg) }) { Text("Update") }
                                    }
                                    OutlinedButton(onClick = { onUninstall(pkg.pkgId) }) { Text("Uninstall") }
                                }
                            } else {
                                Text(
                                    pkg.permissionsSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { onInstallOrUpdate(pkg) },
                                    enabled = isCompatible
                                ) {
                                    Text(if (isCompatible) "Install" else "Blocked")
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
                    "Enter an HTTPS repository JSON index URL (Koma JSON index or Mihon index.min.json for metadata inspection).",
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
private fun ExtensionInspectDialog(
    pkg: ExtensionPackageEntity,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    val isCompatible = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pkg.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Package ID: ${pkg.pkgId}", style = MaterialTheme.typography.bodySmall)
                Text("Format: ${ExtensionFormat.fromId(pkg.format).label}", style = MaterialTheme.typography.bodySmall)
                Text("Version: ${pkg.latestVersionName} (code ${pkg.latestVersionCode})", style = MaterialTheme.typography.bodySmall)
                Text("Signature Hash: ${pkg.signatureHash.ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                Text("Permissions: ${pkg.permissionsSummary}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (!isCompatible) {
                    Text(
                        "Security Notice: Native APK plugins cannot be executed or installed silently by Koma. Only sandboxed Koma JSON adapters are runnable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isCompatible && !pkg.isInstalled) {
                Button(onClick = onInstall) { Text("Install") }
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
