package com.example.ui.screens.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.KomaEmptyState
import com.example.ui.components.MangaCoverCard

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
    var showSearchBar by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showPrefsDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    if (showSearchBar) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setBrowseQuery,
                            placeholder = { Text("Search ${state.sourceName}...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.setBrowseMode("search") }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(state.sourceName)
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search source")
                    }
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Source filters")
                    }
                    if (state.supportsLogin) {
                        IconButton(onClick = { showLoginDialog = true }) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Source login")
                        }
                    }
                    if (state.preferences.isNotEmpty()) {
                        IconButton(onClick = { showPrefsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Source settings")
                        }
                    }
                }
            )
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.mode == "popular",
                    onClick = { viewModel.setBrowseMode("popular") },
                    label = { Text("Popular") },
                    leadingIcon = { Icon(Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = state.mode == "latest",
                    onClick = { viewModel.setBrowseMode("latest") },
                    label = { Text("Latest") },
                    leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (state.query.isNotBlank() || state.mode == "search") {
                    FilterChip(
                        selected = state.mode == "search",
                        onClick = { viewModel.setBrowseMode("search") },
                        label = { Text("Search") }
                    )
                }
            }

            if (state.isLoading && state.items.isEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { err ->
                ErrorRetryBanner(
                    message = err,
                    onRetry = { viewModel.loadSourcePage(state.page, append = false) }
                )
            }

            if (!state.isLoading && state.items.isEmpty() && state.error == null) {
                KomaEmptyState(
                    title = "No Titles Found",
                    subtitle = "Try adjusting your search query or filters, or verify network access."
                )
            } else {
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
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { viewModel.loadSourcePage(state.page + 1, append = true) },
                                    enabled = !state.isLoading
                                ) {
                                    Text(if (state.isLoading) "Loading..." else "Load More")
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
