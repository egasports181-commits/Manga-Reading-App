package com.example.ui.screens.sources

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.components.ChapterListSkeleton
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaElevatedCard
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.components.MangaCardUi
import com.example.ui.components.MangaCoverCard
import com.example.ui.components.rememberCloudflareWebViewLauncher
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
    val repoWebViewLauncher = rememberCloudflareWebViewLauncher {
        viewModel.refreshAllEnabledRepos(onlyFailed = true)
    }
    val searchWebViewLauncher = rememberCloudflareWebViewLauncher {
        viewModel.executeGlobalSearch()
    }
    LaunchedEffect(viewModel) {
        viewModel.osActionIntents.collect { intent ->
            runCatching { context.startActivity(intent) }
        }
    }
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
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                LiquidGlassTopBar(
                    title = "Browse & Extensions",
                    subtitle = "${state.repos.count { it.isEnabled }} active repos • ${state.packages.size} indexed",
                    actions = {
                        KomaCircularGlassButton(
                            icon = Icons.Default.NetworkCheck,
                            contentDescription = "Network & repo diagnostics",
                            onClick = onOpenDiagnostics
                        )
                        Spacer(Modifier.width(4.dp))
                        if (selectedTab == 0) {
                            KomaCircularGlassButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = "Recheck source health",
                                onClick = { viewModel.checkSourceAvailability() }
                            )
                            Spacer(Modifier.width(8.dp))
                        } else if (selectedTab == 2) {
                            KomaCircularGlassButton(
                                icon = Icons.Default.AddCircleOutline,
                                contentDescription = "Add repository",
                                onClick = { showAddRepoDialog = true },
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("add_repo_button")
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                )
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    maxLines = 1,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        ) {
            state.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearBannerMessages, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            state.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::clearBannerMessages, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                        state.errorCloudflareUrl?.let { cfUrl ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalButton(
                                    onClick = { repoWebViewLauncher(cfUrl, "Repository") },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("sources_error_webview_button")
                                ) {
                                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open WebView")
                                }
                            }
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
                    onOpenMangaCard = { card -> viewModel.openSourceManga(card, onOpenManga) },
                    onOpenCloudflareWebView = searchWebViewLauncher
                )
                2 -> ExtensionsManagerTab(
                    state = state,
                    onSelectSubTab = viewModel::selectExtSubTab,
                    onSearchChange = viewModel::updateExtSearch,
                    onClearSearchAndFilters = viewModel::clearExtSearchAndFilters,
                    onLangFilterChange = viewModel::updateExtLangFilter,
                    onRepoFilterChange = viewModel::updateExtRepoFilter,
                    onCompatFilterChange = viewModel::updateExtCompatFilter,
                    onSortChange = viewModel::updateExtSort,
                    onAddRepo = { showAddRepoDialog = true },
                    onRefreshAllRepos = { viewModel.refreshAllEnabledRepos(false) },
                    onRetryFailedRepos = { viewModel.refreshAllEnabledRepos(true) },
                    onRestoreBuiltIns = viewModel::restoreBuiltInRepos,
                    onReloadInstalled = { viewModel.syncCompanionApps(showFeedback = true) },
                    onExportRepos = viewModel::exportRepositories,
                    onImportRepos = { showImportRepoDialog = true },
                    onOpenDiagnostics = onOpenDiagnostics,
                    onToggleRepoEnabled = viewModel::setRepoEnabled,
                    onRefreshRepo = viewModel::refreshRepository,
                    onTrustRepo = viewModel::trustRepository,
                    onRemoveRepo = viewModel::removeRepository,
                    onInstallOrUpdate = viewModel::installOrUpdateExtension,
                    onTrustSigner = viewModel::trustExtensionSigner,
                    onInstallApk = viewModel::requestOsInstallMihonApk,
                    onUpdateAll = viewModel::updateAllExtensions,
                    onToggleEnabled = viewModel::setExtensionEnabled,
                    onUninstall = viewModel::uninstallExtension,
                    onInspect = { inspectPackage = it },
                    onOpenNativeSource = onOpenSource
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
                cm.setPrimaryClip(ClipData.newPlainText("Kiyomi Repositories", json))
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
            onTrustSigner = {
                viewModel.trustExtensionSigner(pkg)
                inspectPackage = null
            },
            onInstallApk = {
                viewModel.requestOsInstallMihonApk(pkg)
                inspectPackage = null
            },
            onOpenNativeSource = { nativeSourceId ->
                inspectPackage = null
                onOpenSource(nativeSourceId)
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 112.dp),
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
                        label = { Text(label) },
                        shape = CircleShape
                    )
                }
            }
        }

        item {
            KomaElevatedCard(
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = CircleShape,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.FolderZip,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text("Local Manga / CBZ Folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (state.safLocalFolderUri.isBlank()) {
                                    "Link a device folder with .cbz archives or chapter images"
                                } else {
                                    "Linked: ${state.safLocalFolderUri.takeLast(34)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalButton(
                        onClick = onPickLocalSaf,
                        shape = CircleShape,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Select")
                    }
                }
            }
        }

        items(state.sources, key = { it.id }) { source ->
            val health = source.healthStatus
            KomaElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source_item_${source.id}"),
                shape = RoundedCornerShape(20.dp),
                onClick = if (source.isEnabled) ({ onOpenSource(source.id) }) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(46.dp)
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

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (source.isDemo) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                                    shape = CircleShape
                                ) {
                                    Text(
                                        "SAMPLE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(top = 2.dp)
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

        if (state.verifiedExtensionDescriptors.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Verified Mihon Extension Catalogs (${state.verifiedExtensionDescriptors.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onOpenExtensionsTab) {
                        Text("Manage")
                    }
                }
            }
            items(state.verifiedExtensionDescriptors, key = { "desc_${it.pkg.pkgId}" }) { desc ->
                KomaElevatedCard(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                desc.pkg.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = StatusHiatus.copy(alpha = 0.16f),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (desc.canExecuteInKoma) "Compatible" else "Action Needed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusHiatus,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
    onOpenMangaCard: (MangaCardUi) -> Unit,
    onOpenCloudflareWebView: (url: String, sourceName: String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(22.dp),
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
                    shape = RoundedCornerShape(16.dp),
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
                    shape = CircleShape,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("global_search_submit")
                ) {
                    Text("Go")
                }
            }
        }

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
                        shape = CircleShape,
                        leadingIcon = if (included) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }

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
                        label = { Text(sug) },
                        shape = CircleShape
                    )
                }
            }
        }

        if (state.isSearchingGlobal) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(CircleShape)
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.globalSourceErrors.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.globalSourceErrors.forEach { (src, err) ->
                            val cfUrl = state.globalSourceCloudflareUrls[src]
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "$src: $err",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusError,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (cfUrl != null) {
                                        FilledTonalButton(
                                            onClick = { onOpenCloudflareWebView(cfUrl, src) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("global_search_webview_$src")
                                        ) {
                                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Open WebView")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.duplicateTitlesAcrossSources.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Duplicate series detected across multiple sources (${state.duplicateTitlesAcrossSources.size} title(s)) — check source badges on cards.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
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
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    items(state.searchHistory, key = { it.id }) { entry ->
                        KomaElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            onClick = { onSelectHistory(entry.query) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (entry.isSaved) Icons.Default.Bookmark else Icons.Default.History,
                                    contentDescription = null,
                                    tint = if (entry.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    entry.query,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onToggleSaveSearch(entry) }) {
                                    Icon(
                                        if (entry.isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "Save search",
                                        tint = if (entry.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteSearch(entry.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove")
                                }
                            }
                        }
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
                            fontWeight = FontWeight.Bold,
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
    onClearSearchAndFilters: () -> Unit,
    onLangFilterChange: (String) -> Unit,
    onRepoFilterChange: (Long?) -> Unit,
    onCompatFilterChange: (ExtensionCompatFilter) -> Unit,
    onSortChange: (ExtensionSortOption) -> Unit,
    onAddRepo: () -> Unit,
    onRefreshAllRepos: () -> Unit,
    onRetryFailedRepos: () -> Unit,
    onRestoreBuiltIns: () -> Unit,
    onReloadInstalled: () -> Unit,
    onExportRepos: () -> Unit,
    onImportRepos: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onToggleRepoEnabled: (ExtensionRepoEntity, Boolean) -> Unit,
    onRefreshRepo: (ExtensionRepoEntity) -> Unit,
    onTrustRepo: (ExtensionRepoEntity, Boolean) -> Unit,
    onRemoveRepo: (ExtensionRepoEntity) -> Unit,
    onInstallOrUpdate: (ExtensionPackageEntity) -> Unit,
    onTrustSigner: (ExtensionPackageEntity) -> Unit,
    onInstallApk: (ExtensionPackageEntity) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onInspect: (ExtensionPackageEntity) -> Unit,
    onOpenNativeSource: (String) -> Unit
) {
    val updatesCount = state.updatesPackagesCount
    val failedRepoCount = state.repos.count { it.isEnabled && !it.lastError.isNullOrBlank() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExtensionStoreTab.entries.forEach { subTab ->
                    val label = when (subTab) {
                        ExtensionStoreTab.INSTALLED -> if (state.installedPackagesCount > 0) "${subTab.label} (${state.installedPackagesCount})" else subTab.label
                        ExtensionStoreTab.UPDATES -> if (updatesCount > 0) "${subTab.label} ($updatesCount)" else subTab.label
                        ExtensionStoreTab.REPOSITORIES -> "${subTab.label} (${state.repos.size})"
                        else -> subTab.label
                    }
                    FilterChip(
                        selected = state.extSubTab == subTab,
                        onClick = { onSelectSubTab(subTab) },
                        label = { Text(label) },
                        shape = CircleShape,
                        modifier = Modifier.testTag("ext_subtab_${subTab.name.lowercase()}")
                    )
                }
            }

            if (state.extSubTab != ExtensionStoreTab.REPOSITORIES) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.extSearchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search name, package, or lang (e.g. en)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search extensions") },
                        trailingIcon = {
                            if (state.extSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchChange("") },
                                    modifier = Modifier.testTag("ext_search_clear")
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ext_search_input")
                    )
                    FilledTonalButton(
                        onClick = onRefreshAllRepos,
                        shape = CircleShape,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ext_sync_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync")
                    }
                    if (updatesCount > 0) {
                        Button(
                            onClick = onUpdateAll,
                            shape = CircleShape,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Update ($updatesCount)")
                        }
                    }
                }
            }

            if (state.isRefreshingRepos) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ext_store_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.extSubTab == ExtensionStoreTab.REPOSITORIES) {
            item {
                KomaElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Mihon & Kiyomi Extension Repositories",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Supports HTTPS Mihon `index.pb` (Protobuf v1/v2), `index.min.json`, companion `repo.json`, and Kiyomi declarative JSON adapters with `apksig` verification and OS-consented installation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = onRefreshAllRepos, shape = CircleShape) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Refresh All")
                            }
                            if (failedRepoCount > 0) {
                                FilledTonalButton(onClick = onRetryFailedRepos, shape = CircleShape) {
                                    Text("Retry Failed ($failedRepoCount)")
                                }
                            }
                            FilledTonalButton(onClick = onReloadInstalled, shape = CircleShape) {
                                Text("Reload Installed")
                            }
                            OutlinedButton(onClick = onAddRepo, shape = CircleShape) { Text("Add URL") }
                            OutlinedButton(onClick = onRestoreBuiltIns, shape = CircleShape) { Text("Restore Defaults") }
                            OutlinedButton(onClick = onExportRepos, shape = CircleShape) { Text("Export JSON") }
                            OutlinedButton(onClick = onImportRepos, shape = CircleShape) { Text("Import JSON") }
                            OutlinedButton(onClick = onOpenDiagnostics, shape = CircleShape) { Text("Diagnostics") }
                        }
                    }
                }
            }

            items(state.repos, key = { "repo_${it.id}" }) { repo ->
                val hasErr = !repo.lastError.isNullOrBlank()
                KomaElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    borderColor = if (hasErr) StatusError.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            repo.repoFormat,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (repo.isBuiltIn) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                                            shape = CircleShape
                                        ) {
                                            Text(
                                                "DEFAULT",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                FilterChip(
                                    selected = repo.isTrusted,
                                    onClick = { onTrustRepo(repo, !repo.isTrusted) },
                                    label = { Text(if (repo.isTrusted) "Trusted" else "Untrusted") },
                                    shape = CircleShape
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
                            Surface(
                                color = StatusError.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusError,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (state.installHistory.isNotEmpty()) {
                item {
                    Text(
                        "Recent APK / Adapter Audit Log",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(state.installHistory.take(15), key = { "hist_${it.id}" }) { entry ->
                    KomaElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${entry.extensionName} (${entry.action})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                val logColor = when (entry.status) {
                                    "SUCCESS", "ACTIVE" -> StatusOngoing
                                    "VERIFIED", "VERIFIED_UNSUPPORTED_RUNTIME" -> StatusCompleted
                                    else -> StatusHiatus
                                }
                                Surface(color = logColor.copy(alpha = 0.14f), shape = CircleShape) {
                                    Text(
                                        entry.status,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = logColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
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
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExtensionCompatFilter.entries.forEach { cf ->
                        FilterChip(
                            selected = state.extCompatFilter == cf,
                            onClick = { onCompatFilterChange(cf) },
                            label = { Text(cf.label) },
                            shape = CircleShape
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
                            label = { Text("All Langs") },
                            shape = CircleShape
                        )
                        state.allPackageLanguages.take(12).forEach { lang ->
                            FilterChip(
                                selected = state.extLangFilter.equals(lang, true),
                                onClick = { onLangFilterChange(lang) },
                                label = { Text(lang.uppercase()) },
                                shape = CircleShape
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
                            label = { Text("All Repos") },
                            shape = CircleShape
                        )
                        state.repos.filter { it.isEnabled }.forEach { repo ->
                            FilterChip(
                                selected = state.extRepoFilterId == repo.id,
                                onClick = { onRepoFilterChange(repo.id) },
                                label = { Text(repo.displayName.ifBlank { repo.name }) },
                                shape = CircleShape
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
                            label = { Text("Sort: ${sort.label}") },
                            shape = CircleShape
                        )
                    }
                }
            }

            if (state.isRefreshingRepos && state.packages.isEmpty()) {
                item { ChapterListSkeleton(count = 5) }
            } else if (state.packages.isEmpty()) {
                item {
                    if (state.totalIndexedPackagesCount > 0) {
                        val trimmedQ = state.extSearchQuery.trim()
                        KomaEmptyState(
                            title = if (trimmedQ.isNotEmpty()) {
                                "No Extensions Match \"$trimmedQ\""
                            } else {
                                "No Matching Extensions"
                            },
                            subtitle = "No extensions matched your current search or filter selection (${state.totalIndexedPackagesCount} indexed across active repositories).",
                            actionLabel = "Clear Search & Filters",
                            onAction = onClearSearchAndFilters,
                            showIllustration = false
                        )
                    } else {
                        KomaEmptyState(
                            title = "No Extension Packages Shown",
                            subtitle = "Tap Sync above to fetch extension catalogs from your configured Mihon repositories.",
                            actionLabel = "Sync Repositories",
                            onAction = onRefreshAllRepos,
                            showIllustration = false
                        )
                    }
                }
            } else {
                items(state.packages, key = { it.pkgId }) { pkg ->
                    val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
                    val isIpc = pkg.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id
                    val isApkVerified = pkg.localApkPath.isNotBlank()
                    val downloadFraction = state.extDownloadProgress[pkg.pkgId]
                    val lifecycle = ExtensionCompatibilityResolver.resolveLifecycle(pkg)

                    KomaElevatedCard(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onInspect(pkg) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    if (pkg.iconUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = pkg.iconUrl,
                                            contentDescription = pkg.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp))
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
                                        fontWeight = FontWeight.SemiBold,
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
                                    ExtensionLifecycleState.VERIFIED -> StatusCompleted
                                    ExtensionLifecycleState.DOWNLOADED -> StatusHiatus
                                    ExtensionLifecycleState.UNSUPPORTED -> when (pkg.compatibilityStatus) {
                                        ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name,
                                        ExtensionCompatibilityStatus.UNTRUSTED_REPO.name -> StatusHiatus
                                        else -> StatusError
                                    }
                                }
                                val badgeLabel = when {
                                    lifecycle.primaryState == ExtensionLifecycleState.ACTIVE -> if (isIpc) "Active IPC" else "Active"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name -> "Verified APK"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name -> "Trust Required"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNSIGNED_APK.name -> "Unsigned APK"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.MALFORMED_APK.name -> "Invalid APK"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH.name -> "Sig Mismatch"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE.name -> "API Mismatch"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE.name -> "Non-Mihon APK"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.MIHON_LOAD_FAILED.name -> "Load Error"
                                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_REPO.name -> "Untrusted Repo"
                                    lifecycle.primaryState == ExtensionLifecycleState.COMPATIBLE -> if (isIpc) "IPC Ready" else "Compatible"
                                    else -> "Mihon Extension"
                                }
                                Surface(
                                    color = badgeColor.copy(alpha = 0.14f),
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(CircleShape)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (pkg.isInstalled && pkg.compatibilityStatus != ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(
                                            checked = pkg.isEnabled,
                                            onCheckedChange = { onToggleEnabled(pkg.pkgId, it) }
                                        )
                                        Text(
                                            if (pkg.isEnabled) "Active" else "Installed (Paused)",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (pkg.hasUpdate) {
                                            Button(onClick = { onInstallOrUpdate(pkg) }, shape = CircleShape) { Text("Update") }
                                        }
                                        OutlinedButton(onClick = { onUninstall(pkg.pkgId) }, shape = CircleShape) { Text("Uninstall") }
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
                                        val nativeEqId = lifecycle.nativeEquivalentSourceId
                                        if (isIpc && lifecycle.isCompatible) {
                                            Button(onClick = { onInstallOrUpdate(pkg) }, shape = CircleShape) {
                                                Text("Activate IPC")
                                            }
                                        } else if (pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name) {
                                            Button(onClick = { onTrustSigner(pkg) }, shape = CircleShape) {
                                                Text("Trust Signer")
                                            }
                                            OutlinedButton(onClick = { onUninstall(pkg.pkgId) }, shape = CircleShape) {
                                                Text("Remove")
                                            }
                                        } else if (pkg.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name) {
                                            Button(onClick = { onInstallApk(pkg) }, shape = CircleShape) {
                                                Text("Install APK")
                                            }
                                            OutlinedButton(onClick = { onUninstall(pkg.pkgId) }, shape = CircleShape) {
                                                Text("Remove")
                                            }
                                        } else if (isApkVerified) {
                                            if (nativeEqId != null) {
                                                Button(onClick = { onOpenNativeSource(nativeEqId) }, shape = CircleShape) {
                                                    Text("Open Native")
                                                }
                                            }
                                            OutlinedButton(onClick = { onUninstall(pkg.pkgId) }, shape = CircleShape) {
                                                Text("Remove APK")
                                            }
                                        } else if (isKoma && lifecycle.isCompatible) {
                                            Button(onClick = { onInstallOrUpdate(pkg) }, shape = CircleShape) {
                                                Text("Install")
                                            }
                                        } else if (!isKoma) {
                                            if (nativeEqId != null) {
                                                OutlinedButton(onClick = { onOpenNativeSource(nativeEqId) }, shape = CircleShape) {
                                                    Text("Native")
                                                }
                                            }
                                            FilledTonalButton(
                                                onClick = { onInstallOrUpdate(pkg) },
                                                enabled = pkg.apkUrl.startsWith("https://") && downloadFraction == null,
                                                shape = CircleShape
                                            ) {
                                                Text("Download & Verify")
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
        shape = RoundedCornerShape(24.dp),
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
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repo_url_input")
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("Expected SHA-256 Fingerprint (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(url, name, fingerprint) },
                enabled = url.isNotBlank(),
                shape = CircleShape
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
        shape = RoundedCornerShape(24.dp),
        title = { Text("Import Repository JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a previously exported Kiyomi repository JSON bundle.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    minLines = 5,
                    maxLines = 9,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(raw) }, enabled = raw.isNotBlank(), shape = CircleShape) { Text("Import") }
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
        shape = RoundedCornerShape(24.dp),
        title = { Text("Exported Repositories JSON") },
        text = {
            OutlinedTextField(
                value = json,
                onValueChange = {},
                readOnly = true,
                minLines = 6,
                maxLines = 10,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onCopy, shape = CircleShape) { Text("Copy & Close") }
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
    onTrustSigner: () -> Unit,
    onInstallApk: () -> Unit,
    onOpenNativeSource: (String) -> Unit,
    onOpenApkInBrowser: (String) -> Unit
) {
    val isKoma = pkg.format == ExtensionFormat.KOMA_JSON_ADAPTER.id
    val isIpc = pkg.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id
    val lifecycle = ExtensionCompatibilityResolver.resolveLifecycle(pkg)
    val matrix = lifecycle.matrix
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(pkg.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Package ID: ${pkg.pkgId}", style = MaterialTheme.typography.bodySmall)
                Text("Format: ${ExtensionFormat.fromId(pkg.format).label}", style = MaterialTheme.typography.bodySmall)
                Text("Version: ${pkg.latestVersionName} (code ${pkg.latestVersionCode})", style = MaterialTheme.typography.bodySmall)
                if (pkg.libVersion.isNotBlank()) {
                    Text("Target Lib Version: ${pkg.libVersion}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Classification: ${lifecycle.incompatibilityCategory.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lifecycle.isCompatible) StatusOngoing else StatusHiatus
                )
                if (matrix != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("Compatibility Matrix", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "1. Repository Imported: ${if (matrix.repositoryImported) "YES" else "NO"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "2. Metadata Recognized: ${if (matrix.metadataRecognized) "YES" else "NO"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "3. APK Inspected: ${if (matrix.apkInspected) "YES" else "PENDING"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "4. Signature Verified: ${if (matrix.apkVerified) "YES" else "NO"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "5. Extension Compatible: ${if (matrix.extensionCompatible) "YES" else "NO (${matrix.capabilityLevel})"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "6. Extension Activated: ${if (matrix.extensionActivated) "YES" else "NO"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "Status: ${matrix.sourceFunctionalStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (pkg.compatibilityReason.isNotBlank()) {
                    Text(pkg.compatibilityReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pkg.installedSignatureSha256.isNotBlank()) {
                    Text("Signer Cert SHA-256: ${pkg.installedSignatureSha256}", style = MaterialTheme.typography.labelSmall)
                }
                lifecycle.nativeEquivalentSourceId?.let { nativeId ->
                    FilledTonalButton(
                        onClick = { onOpenNativeSource(nativeId) },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Built-in Native ${nativeId.replaceFirstChar { it.uppercase() }}")
                    }
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
            if (isIpc && lifecycle.isCompatible && !pkg.isInstalled) {
                Button(onClick = onInstallOrVerify, shape = CircleShape) { Text("Activate IPC") }
            } else if (isKoma && lifecycle.isCompatible && !pkg.isInstalled) {
                Button(onClick = onInstallOrVerify, shape = CircleShape) { Text("Install") }
            } else if (pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name) {
                Button(onClick = onTrustSigner, shape = CircleShape) { Text("Trust Signer") }
            } else if (pkg.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name) {
                Button(onClick = onInstallApk, shape = CircleShape) { Text("Install APK") }
            } else if (!isKoma && !isIpc && pkg.apkUrl.startsWith("https://")) {
                FilledTonalButton(onClick = onInstallOrVerify, shape = CircleShape) { Text("Download & Verify APK") }
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
                        label = { Text(opt.label) },
                        shape = CircleShape
                    )
                }
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = selectedStatus == null, onClick = { selectedStatus = null }, label = { Text("Any") }, shape = CircleShape)
                listOf(MangaStatus.ONGOING, MangaStatus.COMPLETED, MangaStatus.HIATUS).forEach { st ->
                    FilterChip(
                        selected = selectedStatus == st,
                        onClick = { selectedStatus = st },
                        label = { Text(st.label) },
                        shape = CircleShape
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
                        label = { Text(genre) },
                        shape = CircleShape
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author / Artist") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = yearStr,
                    onValueChange = { yearStr = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.width(100.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("safe" to "Safe", "suggestive" to "Suggestive").forEach { (id, label) ->
                    FilterChip(
                        selected = contentRating == id,
                        onClick = { contentRating = id },
                        label = { Text(label) },
                        shape = CircleShape
                    )
                }
                Spacer(Modifier.weight(1f))
                listOf("en" to "EN", "ja" to "JA", "all" to "All").forEach { (id, label) ->
                    FilterChip(
                        selected = selectedLang == id,
                        onClick = { selectedLang = id },
                        label = { Text(label) },
                        shape = CircleShape
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
                    },
                    shape = CircleShape
                ) {
                    Text("Apply Filters")
                }
            }
        }
    }
}
