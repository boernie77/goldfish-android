package com.goldfish.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.data.model.WatchLink
import com.goldfish.android.ui.theme.GoldfishOrange

/** Status-Texte 1:1 wie in der Apple-App und im Browser. */
internal fun watchLinkStatusLabel(status: String): String = when (status) {
    "accepted" -> "Aktiv"
    "pending_incoming" -> "Wartet auf deine Bestätigung"
    "pending_outgoing" -> "Warte auf Bestätigung"
    else -> status
}

/**
 * Gesehen-Sync mit einem anderen Konto. Die Synchronisierung selbst läuft
 * komplett auf dem Server (wirkt damit für alle Clients) — diese Seite ist
 * nur Anfrage/Bestätigung/Trennen. Vorbild: `WatchLinkSettingsView.swift`
 * (Apple-App), Aufbau und Texte bewusst gleich.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchLinkScreen(
    onBack: () -> Unit,
    viewModel: WatchLinkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedUsername by remember { mutableStateOf("") }
    var pickerExpanded by remember { mutableStateOf(false) }
    var pendingUnlink by remember { mutableStateOf<WatchLink?>(null) }

    // Auswahl verwerfen, wenn das Konto nach dem Neuladen nicht mehr
    // angeboten wird (z. B. nach erfolgreicher Anfrage).
    LaunchedEffect(state.availableUsers) {
        if (state.availableUsers.none { it.username == selectedUsername }) selectedUsername = ""
    }

    pendingUnlink?.let { link ->
        val reject = link.status == "pending_incoming"
        val verb = if (reject) "ablehnen" else "trennen"
        AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            title = { Text(if (reject) "Anfrage ablehnen?" else "Verknüpfung trennen?") },
            text = {
                Text("Verknüpfung mit „${link.partnerName}“ wirklich $verb? " +
                     "Bereits übernommene Gesehen-Markierungen bleiben erhalten.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnlink = null
                    viewModel.unlink(link)
                }) {
                    Text(if (reject) "Ablehnen" else "Trennen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlink = null }) { Text("Abbrechen") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gesehen-Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.loaded) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = GoldfishOrange
                )
            }

            // --- Verknüpfungen (nur wenn vorhanden, wie in der Apple-App) ---
            if (state.links.isNotEmpty()) {
                Text(
                    text = "Verknüpfungen",
                    style = MaterialTheme.typography.titleSmall,
                    color = GoldfishOrange
                )
                state.links.forEach { link ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(link.partnerName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = watchLinkStatusLabel(link.status),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (link.status == "pending_incoming") {
                                Button(
                                    onClick = { viewModel.confirm(link) },
                                    enabled = !state.isBusy,
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
                                ) { Text("Bestätigen") }
                                Spacer(Modifier.width(8.dp))
                            }
                            TextButton(
                                onClick = { pendingUnlink = link },
                                enabled = !state.isBusy
                            ) {
                                Text(
                                    if (link.status == "pending_incoming") "Ablehnen" else "Trennen",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Divider()
            }

            // --- Neue Verknüpfung anfragen ---
            Text(
                text = "Neue Verknüpfung anfragen",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange
            )

            if (state.loaded && state.availableUsers.isEmpty()) {
                Text(
                    text = "Keine weiteren Benutzer vorhanden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.availableUsers.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = pickerExpanded,
                    onExpandedChange = { pickerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedUsername.ifEmpty { "Bitte wählen" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Benutzer") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pickerExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldfishOrange,
                            focusedLabelColor = GoldfishOrange
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = pickerExpanded,
                        onDismissRequest = { pickerExpanded = false }
                    ) {
                        state.availableUsers.forEach { user ->
                            DropdownMenuItem(
                                text = { Text(user.username) },
                                onClick = {
                                    pickerExpanded = false
                                    selectedUsername = user.username
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { viewModel.sendRequest(selectedUsername) },
                    enabled = selectedUsername.isNotEmpty() && !state.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
                ) { Text("Anfrage senden") }
            }

            state.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Der andere Benutzer muss die Anfrage in seinen eigenen Einstellungen " +
                       "bestätigen, bevor die Synchronisierung startet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
