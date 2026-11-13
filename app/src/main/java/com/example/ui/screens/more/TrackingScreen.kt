package com.example.ui.screens.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.TrackerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    viewModel: MoreViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingService by remember { mutableStateOf<TrackerService?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Tracking Services") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Connect personal OAuth Bearer tokens to sync reading progress with supported trackers. Services without configured OAuth apps operate honestly as local ledgers until connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(TrackerService.entries) { service ->
                val token = when (service) {
                    TrackerService.ANILIST -> state.prefs.anilistToken
                    TrackerService.MYANIMELIST -> state.prefs.malToken
                    TrackerService.KITSU -> state.prefs.kitsuToken
                    else -> ""
                }
                val isConnected = service.supportsOAuthTokenSync && token.isNotBlank()

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                                Icon(
                                    if (isConnected) Icons.Default.CheckCircle else Icons.Default.LinkOff,
                                    contentDescription = null,
                                    tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = if (isConnected) "Connected (${service.apiEndpointNote})" else service.apiEndpointNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (service.supportsOAuthTokenSync) {
                            OutlinedButton(onClick = { editingService = service }) {
                                Text(if (isConnected) "Manage" else "Connect")
                            }
                        } else {
                            AssistChip(onClick = {}, label = { Text("Local") })
                        }
                    }
                }
            }
        }
    }

    editingService?.let { svc ->
        var tokenInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingService = null },
            title = { Text("Connect ${svc.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste your personal ${svc.displayName} OAuth Bearer Access Token. Leave empty and tap Clear to disconnect.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Bearer Access Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setTrackerToken(svc, tokenInput)
                    editingService = null
                }) { Text("Save Token") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setTrackerToken(svc, "")
                    editingService = null
                }) { Text("Disconnect") }
            }
        )
    }
}
