package com.example.ui.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.TrackerService
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaElevatedCard
import com.example.ui.components.LiquidGlassTopBar

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
            LiquidGlassTopBar(
                navigationIcon = {
                    KomaCircularGlassButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                title = "Tracking Services",
                subtitle = "Sync chapters with external trackers"
            )
        }
    ) { padding ->
        LazyColumn(
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
                ),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                KomaElevatedCard(
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        "Connect personal OAuth Bearer tokens to sync reading progress with supported trackers. Services without configured OAuth apps operate honestly as local ledgers until connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            items(TrackerService.entries) { service ->
                val token = when (service) {
                    TrackerService.ANILIST -> state.prefs.anilistToken
                    TrackerService.MYANIMELIST -> state.prefs.malToken
                    TrackerService.KITSU -> state.prefs.kitsuToken
                    else -> ""
                }
                val isConnected = service.supportsOAuthTokenSync && token.isNotBlank()

                KomaElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(service.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        Spacer(Modifier.width(10.dp))
                        if (service.supportsOAuthTokenSync) {
                            if (isConnected) {
                                FilledTonalButton(onClick = { editingService = service }, shape = CircleShape) {
                                    Text("Manage")
                                }
                            } else {
                                Button(onClick = { editingService = service }, shape = CircleShape) {
                                    Text("Connect")
                                }
                            }
                        } else {
                            AssistChip(onClick = {}, label = { Text("Local") }, shape = CircleShape)
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Connect ${svc.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste your personal ${svc.displayName} OAuth Bearer Access Token. Leave empty and tap Disconnect to unlink.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Bearer Access Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setTrackerToken(svc, tokenInput)
                        editingService = null
                    },
                    shape = CircleShape
                ) { Text("Save Token") }
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
