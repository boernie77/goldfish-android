package com.goldfish.android.ui.locallib

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.data.model.TmdbSearchResult
import com.goldfish.android.ui.theme.GoldfishOrange
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLibraryScreen(
    libraryId: Int,
    folder: String?,
    mergedLibraryIds: List<Int> = emptyList(),   // leer = normale Einzellib
    onBack: () -> Unit,
    onNavigateToFolder: (Int, String) -> Unit,
    onPlayItem: (Int) -> Unit,
    onPlayRandom: (libraryId: Int, itemId: Int, folder: String?) -> Unit,
    onNavigateHome: () -> Unit = {},
    viewModel: LocalLibraryViewModel = hiltViewModel()
) {
    LaunchedEffect(libraryId, folder, mergedLibraryIds) {
        if (mergedLibraryIds.size > 1) viewModel.loadMerged(mergedLibraryIds)
        else viewModel.load(libraryId, folder)
    }
    // Nach Rueckkehr aus dem Player (ON_RESUME) still neu laden, damit frisch
    // gestempelte Werte (lastPlayedAt fuer "Zuletzt gespielt", watched, resume)
    // erscheinen. Der Idempotenz-Guard in load() blockt sonst das Refresh.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshCurrent()
        onPauseOrDispose { }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPosterLib = state.library?.kind == "movies" || state.library?.kind == "tv"
    val isTvLib = state.library?.kind == "tv"
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var reMatchDialogOpen by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<com.goldfish.android.data.local.LocalItemEntity?>(null) }
    var itemContextMenu by remember { mutableStateOf<com.goldfish.android.data.local.LocalItemEntity?>(null) }
    var itemToRename by remember { mutableStateOf<com.goldfish.android.data.local.LocalItemEntity?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Lokale Suchfilter — wirkt auf die aktuell sichtbaren Folders/Items.
    // Wird nur angewendet wenn der User aktiv getippt hat (sonst Identitaet).
    val q = searchQuery.trim().lowercase()
    val filteredFolders = if (q.isBlank()) state.folders
        else state.folders.filter {
            it.name.lowercase().contains(q) ||
                (it.showTitle?.lowercase()?.contains(q) == true)
        }
    // Bei aktiver Suche: allItemsFlat durchsuchen statt nur state.items.
    // state.items enthaelt im Private-Lib-Root nur flat-Files (kein '/'),
    // Items innerhalb von Channel-Ordnern wuerden sonst nie gefunden.
    val filteredItems = if (q.isBlank()) state.items
        else state.allItemsFlat.filter { v ->
            val item = v.item
            (item.title?.lowercase()?.contains(q) == true) ||
                item.parsedTitle.lowercase().contains(q) ||
                item.fileName.lowercase().contains(q)
        }

    // Back-Handler fuer Staffel-Ebene: wenn der User in einer Staffel ist
    // (currentSeason gesetzt), fuehrt Zurueck auf die Staffel-Liste, nicht
    // direkt auf den Library-Root.
    androidx.activity.compose.BackHandler(enabled = state.currentSeason != null) {
        viewModel.selectSeason(null)
    }

    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    bulkDeleteConfirm.takeIf { it }?.let {
        AlertDialog(
            onDismissRequest = { bulkDeleteConfirm = false },
            title = { Text("${state.selectedItemIds.size} Videos loeschen?") },
            text = {
                Text("Die ausgewaehlten Dateien werden vom Geraet entfernt und " +
                     "aus der lokalen Bibliothek geloescht. Diese Aktion kann " +
                     "nicht rueckgaengig gemacht werden.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = state.selectedItemIds.size
                    bulkDeleteConfirm = false
                    viewModel.bulkDeleteSelected(context) { ok, fail ->
                        val msg = if (fail == 0) "$ok von $count Videos geloescht."
                                  else "$ok geloescht, $fail fehlgeschlagen."
                        android.widget.Toast.makeText(context, msg,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("Loeschen", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { bulkDeleteConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            if (state.selectionMode) {
                Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            // Inset gegen die System-Navigation-Bar (3-Button-
                            // Nav oder Gesten-Bar), sonst werden Loeschen/Alle/
                            // Keine vom System-UI verdeckt + nicht klickbar.
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "${state.selectedItemIds.size} ausgewaehlt",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.selectAllVisible() }) {
                            Icon(Icons.Filled.SelectAll, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Alle")
                        }
                        TextButton(onClick = { viewModel.clearSelection() }) { Text("Keine") }
                        FilledTonalButton(
                            onClick = { if (state.selectedItemIds.isNotEmpty()) bulkDeleteConfirm = true },
                            enabled = state.selectedItemIds.isNotEmpty(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0x33E53935),
                                contentColor = Color(0xFFE53935)
                            )
                        ) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Loeschen")
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("In dieser Ansicht suchen…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(state.library?.name ?: "Lokale Bibliothek")
                            folder?.let { f ->
                                val sub = state.currentSeason?.let { s ->
                                    if (s == -1) "$f · Ohne Staffel" else "$f · Staffel $s"
                                } ?: f
                                Text(sub, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchExpanded) { searchExpanded = false; searchQuery = "" }
                        else if (state.currentSeason != null) viewModel.selectSeason(null)
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) searchQuery = ""
                    }) {
                        Icon(
                            if (searchExpanded) Icons.Filled.Clear else Icons.Filled.Search,
                            contentDescription = if (searchExpanded) "Suche schliessen" else "Suchen"
                        )
                    }
                    // Vergleichs-Filter — nur sichtbar wenn der User in
                    // den Settings mind. 2 Libs ausgewaehlt hat. Aktiv =
                    // GoldfishOrange, sonst grau.
                    val compareCount by viewModel.compareLibraryCount.collectAsStateWithLifecycle()
                    if (compareCount >= 2) {
                        IconButton(onClick = { viewModel.toggleCompareMode() }) {
                            Icon(
                                Icons.Filled.CompareArrows,
                                contentDescription = if (state.compareMode) "Vergleich aus" else "Vergleich an",
                                tint = if (state.compareMode) GoldfishOrange
                                       else androidx.compose.material3.LocalContentColor.current
                            )
                        }
                    }
                    // Mehrfachauswahl-Toggle — aktiv = orange. In den Items
                    // wird dann statt Play die Selektion getoggelt; eine
                    // Bottom-Bar erscheint mit Bulk-Aktionen.
                    IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                        Icon(
                            Icons.Filled.Checklist,
                            contentDescription = if (state.selectionMode) "Auswahl beenden" else "Auswaehlen",
                            tint = if (state.selectionMode) GoldfishOrange
                                   else androidx.compose.material3.LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val item = viewModel.randomItem()
                            if (item != null) onPlayRandom(libraryId, item.id, folder)
                            else android.widget.Toast.makeText(context,
                                "Keine Videos zum Abspielen.",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.Casino, "Zufallswiedergabe", tint = GoldfishOrange)
                    }
                    // ⋮-Menu: nur in TV-Show-Folder (re-match macht sonst keinen Sinn)
                    if (isTvLib && folder != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, "Aktionen")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Show neu zuordnen…") },
                                    onClick = {
                                        menuExpanded = false
                                        reMatchDialogOpen = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GoldfishOrange)
            }
            return@Scaffold
        }

        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        // Bei Privat-Libs (YouTube/Urlaubsvideos) groessere Kacheln —
        // 16:9-Verhaeltnis ist deutlich flacher als 2:3-Poster, daher
        // brauchen die Kacheln mehr Breite um vergleichbar gross zu wirken.
        // Movies/TV bleiben bei ~140dp, Privat-Libs bei ~210dp.
        val cellTargetDp = if (state.library?.kind == "private") 210 else 140
        val columns = (screenWidthDp / cellTargetDp).coerceAtLeast(2)
        val cardWidth = (screenWidthDp.dp - 16.dp) / columns - 8.dp

        if (state.folders.isEmpty() && state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center) {
                Text("Keine Inhalte. Im Settings-Menue kannst du diese Library scannen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }

        // Expliziter Grid-State, sodass die Scroll-Position ueber Navigation
        // hinweg (z.B. nach Schliessen eines Videos im LocalPlayer) erhalten
        // bleibt. rememberLazyGridState nutzt intern rememberSaveable.
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LocalSortToolbar(
                sortMode = state.sortMode,
                sortAscending = state.sortAscending,
                watchedFilter = state.watchedFilter,
                selectedBuckets = state.selectedBuckets,
                isPrivateLib = state.library?.kind == "private",
                onSortChange = viewModel::setSortMode,
                onDirectionToggle = viewModel::toggleSortDirection,
                onWatchedChange = viewModel::setWatchedFilter,
                onBucketToggle = viewModel::toggleBucket
            )

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredFolders) { f ->
                LocalFolderCard(
                    folder = f,
                    isPoster = isPosterLib,
                    cardWidth = cardWidth,
                    onClick = {
                        if (f.seasonNumber != null) viewModel.selectSeason(f.seasonNumber)
                        else onNavigateToFolder(libraryId, f.name)
                    }
                )
            }
            items(filteredItems) { v ->
                val isDuplicate = state.compareMode && v.item.fileName in state.duplicateFileNames
                val isSelected = v.item.id in state.selectedItemIds
                LocalVideoCard(
                    video = v,
                    isPoster = isPosterLib,
                    cardWidth = cardWidth,
                    isDuplicate = isDuplicate,
                    selectionMode = state.selectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (state.selectionMode) viewModel.toggleItemSelection(v.item.id)
                        else onPlayItem(v.item.id)
                    },
                    onLongPress = {
                        if (state.selectionMode) viewModel.toggleItemSelection(v.item.id)
                        else itemContextMenu = v.item
                    },
                    onWatchedToggle = { viewModel.toggleWatched(v.item.id) }
                )
            }
        }
        }
    }

    // Long-Press-Aktionsmenue: bietet Umbenennen + Loeschen + Abbrechen.
    itemContextMenu?.let { item ->
        AlertDialog(
            onDismissRequest = { itemContextMenu = null },
            title = { Text(item.fileName, style = MaterialTheme.typography.titleSmall) },
            text = { Text("Was moechtest du mit dieser Datei tun?") },
            confirmButton = {
                TextButton(onClick = {
                    val it = itemContextMenu
                    itemContextMenu = null
                    itemToRename = it
                }) { Text("Umbenennen") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val it = itemContextMenu
                        itemContextMenu = null
                        itemToDelete = it
                    }) {
                        Text("Loeschen", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { itemContextMenu = null }) { Text("Abbrechen") }
                }
            }
        )
    }

    // Rename-Dialog mit TextField, prefill mit aktuellem Dateinamen.
    itemToRename?.let { item ->
        var newName by remember(item.id) { mutableStateOf(item.fileName) }
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Datei umbenennen") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Neuer Dateiname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Datei-Endung mit angeben (z.B. .mp4). Verbotene Zeichen (<>:\"/\\|?*) werden ersetzt.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = item.id
                        val target = newName.trim()
                        itemToRename = null
                        if (target.isNotEmpty() && target != item.fileName) {
                            viewModel.renameItem(id, target, context) { ok ->
                                android.widget.Toast.makeText(context,
                                    if (ok) "Datei umbenannt."
                                    else "Umbenennen fehlgeschlagen (Berechtigung?).",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = newName.trim().isNotEmpty() && newName.trim() != item.fileName
                ) { Text("Umbenennen") }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text("Abbrechen") }
            }
        )
    }

    itemToDelete?.let { item ->
        val displayName = item.title?.takeIf { it.isNotBlank() }
            ?: item.parsedTitle.takeIf { it.isNotBlank() }
            ?: item.fileName
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Datei loeschen?") },
            text = {
                Column {
                    Text(displayName, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(item.fileName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Die Datei wird unwiderruflich vom Geraet entfernt.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = item.id
                    itemToDelete = null
                    viewModel.deleteItem(id, context) { ok ->
                        android.widget.Toast.makeText(context,
                            if (ok) "Datei geloescht."
                            else "Loeschen fehlgeschlagen (Berechtigung?).",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Loeschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) { Text("Abbrechen") }
            }
        )
    }

    if (reMatchDialogOpen && folder != null) {
        ReMatchShowDialog(
            initialQuery = folder,
            onDismiss = { reMatchDialogOpen = false },
            onSearch = { q -> viewModel.searchShows(q) },
            onSelect = { hit ->
                reMatchDialogOpen = false
                viewModel.reMatchShow(hit) { count ->
                    android.widget.Toast.makeText(context,
                        "$count Folgen neu zugeordnet zu: ${hit.title}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun LocalFolderCard(
    folder: LocalCard.Folder,
    isPoster: Boolean,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val cardHeight = if (isPoster) cardWidth * 1.5f else cardWidth * 9f / 16f
    Card(
        modifier = Modifier.width(cardWidth).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.width(cardWidth).height(cardHeight)
            .clip(RoundedCornerShape(6.dp))) {
            if (!folder.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = folder.posterUrl,
                    contentDescription = folder.showTitle ?: folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, null, tint = GoldfishOrange,
                        modifier = Modifier.size(48.dp))
                }
            }
            // Item-Count unten rechts
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0xBB000000))
                .padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text("${folder.itemCount}", color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(
                text = folder.showTitle ?: folder.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalVideoCard(
    video: LocalCard.Video,
    isPoster: Boolean,
    cardWidth: Dp,
    isDuplicate: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onWatchedToggle: () -> Unit
) {
    val cardHeight = if (isPoster) cardWidth * 1.5f else cardWidth * 9f / 16f
    val item = video.item
    val displayTitle = item.title?.takeIf { it.isNotBlank() } ?: item.parsedTitle
    val resText = resolutionLabel(item.width, item.height)
    val durText = formatDuration(item.durationSec)
    val episodeBadge = if (item.parsedSeason != null && item.parsedEpisode != null) {
        "S%02dE%02d".format(item.parsedSeason, item.parsedEpisode)
    } else null
    val alpha = if (item.watched) 0.65f else 1f

    // Vergleichs-Filter: roter Rahmen markiert Dateien, die in
    // mindestens 2 der ausgewaehlten Libs vorkommen.
    // Im Selection-Mode UND selektiert: blauer Rahmen (analog Browser).
    val borderModifier = when {
        selectionMode && isSelected -> Modifier.border(
            width = 3.dp,
            color = Color(0xFF1976D2),
            shape = RoundedCornerShape(6.dp)
        )
        isDuplicate -> Modifier.border(
            width = 3.dp,
            color = Color(0xFFE53935),
            shape = RoundedCornerShape(6.dp)
        )
        else -> Modifier
    }

    Column(modifier = Modifier.width(cardWidth)
        .combinedClickable(onClick = onClick, onLongClick = onLongPress)) {
        Box(modifier = Modifier.width(cardWidth).height(cardHeight)
            .clip(RoundedCornerShape(6.dp))
            .then(borderModifier)) {
            if (!video.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = video.posterUrl,
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(alpha)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.VideoLibrary, null, tint = GoldfishOrange,
                        modifier = Modifier.size(40.dp))
                }
            }

            // Top-left: Watched toggle ODER Selektions-Checkbox im Auswahl-Modus.
            if (selectionMode) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isSelected) Color(0xFF1976D2) else Color(0xAA000000)),
                    contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Ausgewaehlt",
                            tint = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        // leerer Kreis-Outline
                        Box(modifier = Modifier.size(16.dp)
                            .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            } else {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0x88000000))
                    .clickable { onWatchedToggle() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CheckCircle,
                        contentDescription = if (item.watched) "Gesehen" else "Ungesehen",
                        tint = if (item.watched) Color(0xFF4CAF50) else Color(0x88FFFFFF),
                        modifier = Modifier.size(16.dp))
                }
            }

            // Top-right: S01E04 (TV)
            episodeBadge?.let {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text(it, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                }
            }

            // Bottom-left: Auflösung
            if (resText.isNotEmpty()) {
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xBB000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text(resText, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            // Bottom-right: Dauer
            if (durText.isNotEmpty()) {
                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xBB000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)) {
                    Text(durText, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        val sub = listOfNotNull(
            item.year?.toString()?.takeIf { it != "0" },
            item.parsedYear?.toString()?.takeIf { it != "0" && item.year == null }
        ).firstOrNull()
        sub?.let {
            Text(it, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun resolutionLabel(width: Int, height: Int): String {
    val effective = maxOf(height, (width * 9.0 / 16.0).toInt())
    return when {
        effective >= 2160 -> "4K"
        effective >= 1440 -> "2K"
        effective >= 1080 -> "1080p"
        effective >= 720 -> "720p"
        effective >= 576 -> "576p"
        effective >= 480 -> "480p"
        effective > 0 -> "360p"
        else -> ""
    }
}

private fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return ""
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) String.format(Locale.GERMAN, "%d:%02d h", h, m)
           else String.format(Locale.GERMAN, "%d min", m)
}

/** Sort+Filter-Toolbar fuer lokale Libraries — abgespeckte Variante des
 *  FilterToolbar im Server-LibraryScreen. Drei Chips:
 *   - Sortier-Modus (Dropdown)
 *   - Richtung (↑/↓ Toggle)
 *   - Watched-Filter (Cycle Alle → Ungesehen → Gesehen → Alle) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalSortToolbar(
    sortMode: String,
    sortAscending: Boolean,
    watchedFilter: String,
    selectedBuckets: Set<String>,
    isPrivateLib: Boolean,
    onSortChange: (String) -> Unit,
    onDirectionToggle: () -> Unit,
    onWatchedChange: (String) -> Unit,
    onBucketToggle: (String) -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    var resolutionMenu by remember { mutableStateOf(false) }
    val allBuckets = listOf("4K", "2K", "1080p", "720p", "576p", "480p", "360p")
    // Sortier-Optionen: Privat-Libs zeigen „Veroeffentlicht" prominent;
    // alle Libs zeigen Titel + Datum + Laufzeit + Hinzugefuegt.
    val sortOptions = listOf(
        LOCAL_SORT_TITLE to "Titel",
        LOCAL_SORT_RELEASED to (if (isPrivateLib) "Veroeffentlicht" else "Veroeffentlicht"),
        LOCAL_SORT_ADDED to "Hinzugefuegt",
        LOCAL_SORT_DURATION to "Laufzeit",
        LOCAL_SORT_PLAYED to "Zuletzt gespielt"
    )
    val sortLabel = sortOptions.firstOrNull { it.first == sortMode }?.second ?: "Titel"
    val watchedLabel = when (watchedFilter) {
        LOCAL_WATCHED_UNWATCHED -> "Ungesehen"
        LOCAL_WATCHED_WATCHED -> "Gesehen"
        else -> "Alle"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AssistChip(
                onClick = { sortMenu = true },
                label = { Text("Sortieren: $sortLabel") }
            )
            DropdownMenu(
                expanded = sortMenu,
                onDismissRequest = { sortMenu = false }
            ) {
                sortOptions.forEach { (key, lbl) ->
                    DropdownMenuItem(
                        text = { Text(lbl) },
                        onClick = {
                            sortMenu = false
                            onSortChange(key)
                        }
                    )
                }
            }
        }
        AssistChip(
            onClick = onDirectionToggle,
            label = { Text(if (sortAscending) "↑" else "↓") }
        )
        AssistChip(
            onClick = {
                val next = when (watchedFilter) {
                    LOCAL_WATCHED_ALL -> LOCAL_WATCHED_UNWATCHED
                    LOCAL_WATCHED_UNWATCHED -> LOCAL_WATCHED_WATCHED
                    else -> LOCAL_WATCHED_ALL
                }
                onWatchedChange(next)
            },
            label = { Text(watchedLabel) }
        )
        // Aufloesungs-Filter — Multi-Select; Label zeigt Anzahl oder „Alle".
        Box {
            val bucketLabel = if (selectedBuckets.isEmpty()) "Aufloesung"
                else "Aufloesung (${selectedBuckets.size})"
            AssistChip(
                onClick = { resolutionMenu = true },
                label = { Text(bucketLabel) }
            )
            DropdownMenu(
                expanded = resolutionMenu,
                onDismissRequest = { resolutionMenu = false }
            ) {
                allBuckets.forEach { b ->
                    val isOn = b in selectedBuckets
                    DropdownMenuItem(
                        text = { Text(if (isOn) "✓ $b" else b) },
                        onClick = { onBucketToggle(b) }  // menu bleibt offen fuer Multi-Select
                    )
                }
            }
        }
    }
}

/** Dialog zur manuellen Re-Zuordnung einer Show. Initial-Suche wird mit dem
 *  Folder-Namen vorbefuellt; User kann editieren. Ergebnisse vom Server-
 *  TMDB-Proxy werden mit Poster + Titel + Jahr gerendert. Auswahl wendet
 *  die Show auf alle Episoden im Folder an (Backend in ViewModel/Enricher). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReMatchShowDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSearch: suspend (String) -> List<TmdbSearchResult>,
    onSelect: (TmdbSearchResult) -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<TmdbSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Initiale Suche beim Oeffnen
    LaunchedEffect(Unit) {
        searching = true
        results = onSearch(initialQuery)
        if (results.isEmpty()) errorMsg = "Keine Treffer. Suchbegriff anpassen?"
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show neu zuordnen") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Show-Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            scope.launch {
                                searching = true
                                errorMsg = null
                                results = onSearch(query)
                                if (results.isEmpty()) errorMsg = "Keine Treffer."
                                searching = false
                            }
                        }) { Text("Suchen") }
                    }
                )
                Spacer(Modifier.height(8.dp))
                if (searching) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GoldfishOrange)
                    }
                } else if (errorMsg != null && results.isEmpty()) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        lazyItems(results) { hit ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onSelect(hit) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!hit.posterPath.isNullOrBlank()) {
                                    AsyncImage(
                                        model = "https://image.tmdb.org/t/p/w92${hit.posterPath}",
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(width = 46.dp, height = 69.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                } else {
                                    Box(modifier = Modifier.size(width = 46.dp, height = 69.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hit.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = listOfNotNull(
                                        hit.year.takeIf { it > 0 }?.toString(),
                                        hit.originalTitle.takeIf { it.isNotBlank() && it != hit.title }
                                    ).joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        Text(sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
