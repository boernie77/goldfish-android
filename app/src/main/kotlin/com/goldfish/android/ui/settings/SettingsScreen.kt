package com.goldfish.android.ui.settings

import android.content.Intent
import android.net.Uri
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.model.Library
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.data.CacheSize
import com.goldfish.android.ui.theme.GoldfishOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var serverUrlInput by remember(state.settings.serverUrl) {
        mutableStateOf(state.settings.serverUrl)
    }
    var cacheSizeExpanded by remember { mutableStateOf(false) }

    // SAF: Ordner-Picker für Download-Ziel
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persistente Permission greifen
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) { /* manche Provider erlauben kein takePersistable */ }
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
            viewModel.saveDownloadTree(uri.toString(), displayName)
        }
    }

    val currentCacheSize = CacheSize.entries.minByOrNull {
        Math.abs(it.bytes - state.settings.cacheSizeBytes)
    } ?: CacheSize.MEDIUM

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
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
            // Server URL section
            Text(
                text = "Server",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange
            )

            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = { serverUrlInput = it },
                label = { Text("Server-URL") },
                placeholder = { Text("https://your-goldfish.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldfishOrange,
                    focusedLabelColor = GoldfishOrange
                )
            )

            Button(
                onClick = { viewModel.saveServerUrl(serverUrlInput.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
            ) {
                Text("Speichern")
            }

            Divider()

            // Download section
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${state.downloadCount} Downloads",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = formatSize(state.totalDownloadSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Alle Downloads loeschen — mit Bestaetigung
            var confirmDeleteAll by remember { mutableStateOf(false) }
            if (confirmDeleteAll) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteAll = false },
                    title = { Text("Alle Downloads entfernen?") },
                    text = {
                        Text("Es werden ${state.downloadCount} heruntergeladene Dateien geloescht " +
                             "(${formatSize(state.totalDownloadSize)}). Dies kann nicht rueckgaengig " +
                             "gemacht werden.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDeleteAll = false
                            viewModel.deleteAllDownloads()
                        }) {
                            Text("Loeschen", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") }
                    }
                )
            }
            OutlinedButton(
                onClick = { confirmDeleteAll = true },
                enabled = state.downloadCount > 0,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Alle Downloads entfernen")
            }

            // Download-Ordner-Auswahl per SAF
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Download-Ordner",
                    style = MaterialTheme.typography.bodyMedium
                )
                val displayName = state.settings.downloadTreeDisplayName
                Text(
                    text = if (displayName.isNotBlank())
                        displayName
                    else
                        "(App-interner Standard-Ordner)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ordner auswählen…")
                    }
                    if (state.settings.downloadTreeUri.isNotBlank()) {
                        TextButton(onClick = { viewModel.clearDownloadTree() }) {
                            Text("Zurücksetzen")
                        }
                    }
                }
            }

            Divider()

            // Cache section
            Text(
                text = "Cache",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange
            )

            Text(
                text = "Maximale Größe des HTTP-Caches (Poster, Thumbs, API-Antworten)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ExposedDropdownMenuBox(
                expanded = cacheSizeExpanded,
                onExpandedChange = { cacheSizeExpanded = it }
            ) {
                OutlinedTextField(
                    value = currentCacheSize.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Cache-Größe") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cacheSizeExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldfishOrange,
                        focusedLabelColor = GoldfishOrange
                    )
                )
                ExposedDropdownMenu(
                    expanded = cacheSizeExpanded,
                    onDismissRequest = { cacheSizeExpanded = false }
                ) {
                    CacheSize.entries.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(size.label) },
                            onClick = {
                                cacheSizeExpanded = false
                                viewModel.saveCacheSize(size)
                            }
                        )
                    }
                }
            }

            Divider()

            // Bibliotheken zusammenlegen — 2 Server-Libs als eine anzeigen
            MergedLibrarySection(
                serverLibraries = state.serverLibraries,
                localLibraries = state.localLibraries,
                selectedServerIds = state.settings.mergedServerLibraryIds,
                selectedLocalIds = state.settings.mergedLocalLibraryIds,
                onServerSelectionChanged = { viewModel.saveMergedServerLibraryIds(it) },
                onLocalSelectionChanged = { viewModel.saveMergedLocalLibraryIds(it) }
            )

            Divider()

            // Lokale Bibliotheken (on-device Files via SAF)
            LocalLibrariesSection()

            Divider()

            // About
            Text(
                text = "Über",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange
            )
            Text(
                text = "Goldfish Android App v${com.goldfish.android.BuildConfig.VERSION_NAME}" +
                       " (Build ${com.goldfish.android.BuildConfig.VERSION_CODE})\n" +
                       "Kompatibel mit Goldfish Home Video Server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L -> "${"%.0f".format(bytes / 1_048_576.0)} MB"
        bytes == 0L -> "0 B"
        else -> "${bytes / 1024} KB"
    }
}

@Composable
fun MergedLibrarySection(
    serverLibraries: List<Library>,
    localLibraries: List<LocalLibraryEntity>,
    selectedServerIds: Set<Int>,
    selectedLocalIds: Set<Int>,
    onServerSelectionChanged: (Set<Int>) -> Unit,
    onLocalSelectionChanged: (Set<Int>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Bibliotheken zusammenlegen",
            style = MaterialTheme.typography.titleSmall,
            color = GoldfishOrange
        )
        Text(
            text = "Je 2 Bibliotheken gleichen Typs zusammenlegen. Im Menü erscheinen sie als eine — Zufallsplay, Sortierung etc. funktionieren über beide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Server-Bibliotheken
        if (serverLibraries.isNotEmpty()) {
            Text(
                "📡 Server-Bibliotheken",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            MergedLibGroup(
                items = serverLibraries.map { it.id to it.name },
                selectedIds = selectedServerIds,
                onSelectionChanged = onServerSelectionChanged
            )
        }

        // Lokale Bibliotheken
        if (localLibraries.isNotEmpty()) {
            Text(
                "📁 Lokale Bibliotheken",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            MergedLibGroup(
                items = localLibraries.map { it.id to it.name },
                selectedIds = selectedLocalIds,
                onSelectionChanged = onLocalSelectionChanged
            )
        }

        if (serverLibraries.isEmpty() && localLibraries.isEmpty()) {
            Text(
                "Keine Bibliotheken verfügbar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MergedLibGroup(
    items: List<Pair<Int, String>>,
    selectedIds: Set<Int>,
    onSelectionChanged: (Set<Int>) -> Unit
) {
    items.forEach { (id, name) ->
        val checked = id in selectedIds
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newSet = if (checked) selectedIds - id
                    else if (selectedIds.size < 2) selectedIds + id
                    else selectedIds - selectedIds.first() + id
                    onSelectionChanged(newSet)
                }
                .padding(vertical = 2.dp)
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (selectedIds.size) {
        2 -> {
            val names = selectedIds.mapNotNull { id -> items.find { it.first == id }?.second }
            Text(
                "✓ ${names.joinToString(" + ")} werden zusammengelegt",
                style = MaterialTheme.typography.labelSmall,
                color = GoldfishOrange
            )
        }
        1 -> Text(
            "Wähle noch eine weitere Bibliothek",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (selectedIds.isNotEmpty()) {
        TextButton(onClick = { onSelectionChanged(emptySet()) }) {
            Text("Zusammenlegung aufheben", color = MaterialTheme.colorScheme.error)
        }
    }
}
