package com.goldfish.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.local.ScanProgress
import com.goldfish.android.ui.theme.GoldfishOrange

@Composable
fun LocalLibrariesSection(
    viewModel: LocalLibrariesViewModel = hiltViewModel()
) {
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val scanState by viewModel.scanProgress.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val compareIds by viewModel.compareLibraryIds.collectAsStateWithLifecycle()
    val otherLibraries by viewModel.otherLibraries.collectAsStateWithLifecycle()
    val currentUsername by viewModel.currentUsername.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Add-Dialog-State: wenn pickedTreeUri != null, zeigen wir den Dialog
    // wo der User Name + Kind eingibt.
    var pendingTree by remember { mutableStateOf<PendingTree?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persistente Berechtigung greifen — sonst koennen wir nach App-
        // Neustart nicht mehr scannen.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try { context.contentResolver.takePersistableUriPermission(uri, flags) }
        catch (_: SecurityException) { /* manche Provider erlauben kein takePersistable */ }
        val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty()
        pendingTree = PendingTree(treeUri = uri.toString(), displayName = displayName)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "📁 Lokale Bibliotheken",
                style = MaterialTheme.typography.titleSmall,
                color = GoldfishOrange,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = { folderPicker.launch(null) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Hinzufügen", fontSize = 13.sp)
            }
        }

        Text(
            text = "Scannt Videos aus einem Ordner auf dem Geraet — funktioniert auch ohne Server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (libraries.isEmpty()) {
            Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center) {
                    Text(
                        text = "Noch keine lokalen Bibliotheken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            libraries.forEach { lib ->
                LocalLibraryRow(
                    lib = lib,
                    itemCount = counts[lib.id] ?: 0,
                    scanProgress = scanState[lib.id],
                    onKindChange = { viewModel.changeKind(lib.id, it) },
                    onScan = { viewModel.scanLibrary(lib.id) },
                    onDelete = { viewModel.deleteLibrary(lib.id) },
                    onDismissScan = { viewModel.dismissScanResult(lib.id) }
                )
            }

            // Andere lokale Bibliotheken — gehoeren anderen Usern oder sind
            // noch unbeansprucht. Read-Only mit "Mir zuordnen"-Button damit
            // der User Libs zurueckholen kann, falls die Migration sie dem
            // falschen User zugeordnet hat.
            if (otherLibraries.isNotEmpty() && !currentUsername.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "👥 Andere lokale Bibliotheken",
                            style = MaterialTheme.typography.titleSmall,
                            color = GoldfishOrange
                        )
                        Text(
                            text = "Diese gehören anderen Benutzern oder sind noch nicht zugeordnet. Wenn eine eigentlich dir gehört, kannst du sie übernehmen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        otherLibraries.forEach { lib ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lib.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Eigentümer: " + (lib.ownerUsername?.takeIf { it.isNotBlank() } ?: "(unbeansprucht)"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { viewModel.claimLibrary(lib.id) }) {
                                    Text("Mir zuordnen")
                                }
                            }
                        }
                    }
                }
            }

            // Vergleichs-Filter: User waehlt mind. 2 Libs aus, die im Library-
            // Screen via Topbar-Toggle gegeneinander auf doppelte Dateinamen
            // verglichen werden koennen. Duplikate werden dort mit rotem
            // Rahmen markiert.
            if (libraries.size >= 2) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "🔀 Vergleichs-Filter",
                            style = MaterialTheme.typography.titleSmall,
                            color = GoldfishOrange
                        )
                        Text(
                            text = "Waehle mindestens 2 Bibliotheken aus. Im Bibliotheks-Screen kannst du den Vergleich dann ueber den ⧉-Button in der Topbar einschalten — Dateien, die in mehreren der ausgewaehlten Libs vorkommen, bekommen einen roten Rahmen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        libraries.forEach { lib ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleCompareLibrary(lib.id) }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = lib.id in compareIds,
                                    onCheckedChange = { viewModel.toggleCompareLibrary(lib.id) }
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = lib.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingTree?.let { pending ->
        AddLibraryDialog(
            displayName = pending.displayName,
            onCancel = { pendingTree = null },
            onConfirm = { name, kind ->
                viewModel.addLibrary(
                    name = name,
                    kind = kind,
                    treeUri = pending.treeUri,
                    displayName = pending.displayName
                )
                pendingTree = null
            }
        )
    }
}

@Composable
private fun LocalLibraryRow(
    lib: LocalLibraryEntity,
    itemCount: Int,
    scanProgress: ScanProgress?,
    onKindChange: (String) -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
    onDismissScan: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var kindMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, null, tint = GoldfishOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "$itemCount Videos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onScan,
                    enabled = scanProgress?.running != true,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Refresh, "Scannen",
                        tint = GoldfishOrange, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { confirmDelete = true },
                    enabled = scanProgress?.running != true,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Delete, "Löschen",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }

            Text(
                text = lib.displayName.ifBlank { lib.treeUri },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Typ:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    AssistChip(
                        onClick = { kindMenuExpanded = true },
                        label = { Text(kindLabel(lib.kind), fontSize = 12.sp) }
                    )
                    DropdownMenu(
                        expanded = kindMenuExpanded,
                        onDismissRequest = { kindMenuExpanded = false }
                    ) {
                        listOf("movies" to "Filme", "tv" to "Serien", "private" to "Privat").forEach { (k, lbl) ->
                            DropdownMenuItem(text = { Text(lbl) }, onClick = {
                                kindMenuExpanded = false
                                onKindChange(k)
                            })
                        }
                    }
                }
            }

            // Scan-Progress / Ergebnis-Zeile
            scanProgress?.let { p ->
                when {
                    p.running -> {
                        val totalTxt = if (p.totalFiles > 0) "${p.processedFiles}/${p.totalFiles}" else "${p.processedFiles}"
                        val phaseLabel = when (p.phase) {
                            "enriching" -> "Reichere Metadaten an"
                            else -> "Scanne $totalTxt"
                        }
                        Column {
                            Text(
                                text = "$phaseLabel — ${p.currentFile.take(48)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldfishOrange
                            )
                            if (p.totalFiles > 0 && p.phase != "enriching") {
                                LinearProgressIndicator(
                                    progress = { p.processedFiles.toFloat() / p.totalFiles },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = GoldfishOrange
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = GoldfishOrange
                                )
                            }
                        }
                    }
                    p.errorMessage != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "❌ ${p.errorMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissScan) { Text("OK", fontSize = 12.sp) }
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "✓ Scan fertig — ${p.processedFiles} Videos gefunden",
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldfishOrange,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissScan) { Text("OK", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Bibliothek löschen?") },
            text = { Text("Die Eintraege in der App werden geloescht. Die Dateien auf dem Geraet bleiben unberuehrt.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun AddLibraryDialog(
    displayName: String,
    onCancel: () -> Unit,
    onConfirm: (name: String, kind: String) -> Unit
) {
    var name by remember { mutableStateOf(displayName.ifBlank { "Lokale Bibliothek" }) }
    var kind by remember { mutableStateOf("movies") }
    var kindMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Bibliothek hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Ordner: $displayName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Typ:", modifier = Modifier.width(40.dp))
                    Box {
                        AssistChip(
                            onClick = { kindMenu = true },
                            label = { Text(kindLabel(kind)) }
                        )
                        DropdownMenu(
                            expanded = kindMenu,
                            onDismissRequest = { kindMenu = false }
                        ) {
                            listOf("movies" to "Filme", "tv" to "Serien", "private" to "Privat").forEach { (k, lbl) ->
                                DropdownMenuItem(text = { Text(lbl) }, onClick = {
                                    kind = k
                                    kindMenu = false
                                })
                            }
                        }
                    }
                }
                Text(
                    text = "Nach dem Hinzufuegen wird der Ordner automatisch gescannt.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifBlank { displayName }, kind) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Hinzufügen") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Abbrechen") }
        }
    )
}

private data class PendingTree(val treeUri: String, val displayName: String)

private fun kindLabel(kind: String): String = when (kind) {
    "movies" -> "Filme"
    "tv" -> "Serien"
    "private" -> "Privat"
    else -> kind
}
