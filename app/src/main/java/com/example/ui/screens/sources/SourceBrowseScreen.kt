package com.example.ui.screens.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.LiquidGlassTopBar
import com.example.ui.components.MangaCardSkeletonRow
import com.example.ui.components.MangaCoverCard
import com.example.ui.components.rememberCloudflareWebViewLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: String,
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit
) {
    LaunchedEffect(sourceId) {
        viewModel.initBrowseSource(sourceId)
    }

    val state by viewModel.browseUiState.collectAsStateWithLifecycle()
    val launchWebView = rememberCloudflareWebViewLauncher {
        viewModel.loadSourcePage(state.page, append = false)
    }
    var showSearchBar by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showPrefsDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = showSearchBar) {
        showSearchBar = false
    }

    Scaffold(
        topBar = {
            if (showSearchBar) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setBrowseQuery,
                            placeholder = { Text("Search ${state.sourceName}...") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.setBrowseMode("search") }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    actions = {
                        IconButton(onClick = { showSearchBar = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                LiquidGlassTopBar(
                    title = state.sourceName.ifBlank { "Browse Source" },
                    subtitle = when (state.mode) {
                        "latest" -> "Latest updates"
                        "search" -> "Search results"
                        else -> "Popular catalog"
                    },
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
                    actions = {
                        KomaCircularGlassButton(
                            icon = Icons.Default.Search,
                            contentDescription = "Search source",
                            onClick = { showSearchBar = true }
                        )
                        Spacer(Modifier.width(4.dp))
                        KomaCircularGlassButton(
                            icon = Icons.Default.FilterList,
                            contentDescription = "Source filters",
                            onClick = { showFilters = true }
                        )
                        if (state.sourceBaseUrl.startsWith("http", ignoreCase = true)) {
                            Spacer(Modifier.width(4.dp))
                            KomaCircularGlassButton(
                                icon = Icons.Default.Public,
                                contentDescription = "Open in WebView",
                                onClick = {
                                    launchWebView(
                                        state.cloudflareUrl ?: state.sourceBaseUrl,
                                        state.sourceName
                                    )
                                },
                                modifier = Modifier.testTag("browse_webview_button")
                            )
                        }
                        if (state.supportsLogin) {
                            Spacer(Modifier.width(4.dp))
                            KomaCircularGlassButton(
                                icon = Icons.Default.AccountCircle,
                                contentDescription = "Source login",
                                onClick = { showLoginDialog = true }
                            )
                        }
                        if (state.preferences.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            KomaCircularGlassButton(
                                icon = Icons.Default.Settings,
                                contentDescription = "Source settings",
                                onClick = { showPrefsDialog = true }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.mode == "popular",
                    onClick = { viewModel.setBrowseMode("popular") },
                    shape = CircleShape,
                    label = { Text("Popular") },
                    leadingIcon = { Icon(Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = state.mode == "latest",
                    onClick = { viewModel.setBrowseMode("latest") },
                    shape = CircleShape,
                    label = { Text("Latest") },
                    leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (state.query.isNotBlank() || state.mode == "search") {
                    FilterChip(
                        selected = state.mode == "search",
                        onClick = { viewModel.setBrowseMode("search") },
                        shape = CircleShape,
                        label = { Text("Search") }
                    )
                }
            }

            if (state.isLoading && state.items.isEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                MangaCardSkeletonRow()
                Spacer(Modifier.height(14.dp))
                MangaCardSkeletonRow()
            }

            state.error?.let { err ->
                ErrorRetryBanner(
                    message = err,
                    onRetry = { viewModel.loadSourcePage(state.page, append = false) },
                    onOpenWebView = state.cloudflareUrl?.let { cfUrl ->
                        { launchWebView(cfUrl, state.sourceName) }
                    }
                )
            }

            if (!state.isLoading && state.items.isEmpty() && state.error == null) {
                KomaEmptyState(
                    title = "No Titles Found",
                    subtitle = "Try adjusting your search query or filters, or verify network access."
                )
            } else if (state.items.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items, key = { it.id }) { card ->
                        MangaCoverCard(
                            item = card,
                            onClick = { viewModel.openSourceManga(card, onOpenManga) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.hasNextPage) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { viewModel.loadSourcePage(state.page + 1, append = true) },
                                    enabled = !state.isLoading,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        if (state.isLoading) "Loading..." else "Load More",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        SearchFilterBottomSheet(
            initial = state.filters,
            onDismiss = { showFilters = false },
            onApply = {
                viewModel.setBrowseFilters(it)
                showFilters = false
            }
        )
    }

    if (showPrefsDialog) {
        AlertDialog(
            onDismissRequest = { showPrefsDialog = false },
            title = { Text("${state.sourceName} Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.preferences.filterNot { it.isSecret }.forEach { pref ->
                        Text(pref.title, style = MaterialTheme.typography.titleSmall)
                        Text(pref.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        pref.options.forEach { (valKey, valLabel) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateSourcePreference(pref.key, valKey) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = pref.currentValue == valKey,
                                    onClick = { viewModel.updateSourcePreference(pref.key, valKey) }
                                )
                                Text(valLabel, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrefsDialog = false }) { Text("Done") }
            }
        )
    }

    if (showLoginDialog) {
        var username by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            title = { Text("${state.sourceName} Session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Provide a personal OAuth Bearer token for ${state.sourceName}. Credentials are stored locally on your device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username (optional)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Bearer Access Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    state.loginFeedback?.let { fb ->
                        Text(fb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.loginToSource(username, token) }) { Text("Verify & Save") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginDialog = false }) { Text("Close") }
            }
        )
    }
}
