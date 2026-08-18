package com.goldfish.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.data.model.FolderItem
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.model.LibraryStats
import com.goldfish.android.data.model.SeasonInfo
import com.goldfish.android.ui.components.VideoCard
import com.goldfish.android.ui.theme.GoldfishOrange
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    libraryId: Int,
    folder: String?,
    drilldownActive: Boolean = false,
    mergedLibraryIds: List<Int> = emptyList(),   // leer = normale Einzellib
    onNavigateToItem: (Int) -> Unit,
    onNavigateToPlayer: (Int) -> Unit = {},
    onNavigateToFolder: (Int, String, Boolean) -> Unit,
    onNavigateHome: () -> Unit = {},
    onBack: () -> Unit,
    onPlayRandom: ((Int, Int) -> Unit)? = null,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    LaunchedEffect(libraryId, folder, drilldownActive, mergedLibraryIds) {
        if (mergedLibraryIds.size > 1) {
            viewModel.loadMerged(mergedLibraryIds, folder, drilldownActive)
        } else {
            viewModel.load(libraryId, folder, drilldownActive)
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val library = state.library
    val isTvLib = library?.kind == "tv"
    val isPoster = library?.kind == "movies" || library?.kind == "tv"

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        isPoster && screenWidthDp >= 840 -> if (screenWidthDp >= 1200) 6 else 5
        isPoster -> 3
        screenWidthDp >= 840 -> 4
        else -> 2
    }
    val gap = 8.dp
    val cardWidth = ((screenWidthDp.dp - gap * (columns + 1)) / columns)

    var showSortMenu by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var downloadToDelete by remember { mutableStateOf<Item?>(null) }
    // Drilldown-Toggle-Dialog: wird beim Long-Press auf eine Folder-Card
    // (Admin-only) gesetzt. Zeigt einen Bestaetigungsdialog zum An-/Aus-
    // schalten von "Unterordner als Ebene anzeigen".
    var drilldownDialogFor by remember { mutableStateOf<FolderItem?>(null) }
    val coroutineScope = rememberCoroutineScope()

    drilldownDialogFor?.let { f ->
        val turningOn = !f.drilldown
        AlertDialog(
            onDismissRequest = { drilldownDialogFor = null },
            title = { Text(if (turningOn) "Unterordner als Ebene anzeigen?" else "Unterordner-Ebene ausschalten?") },
            text = {
                Text(
                    if (turningOn)
                        "Beim Klick auf „${f.folder.substringAfterLast('/')}“ werden " +
                        "die direkten Unterordner als Kacheln gezeigt, statt alle " +
                        "Videos flach aufzulisten."
                    else
                        "Klick auf „${f.folder.substringAfterLast('/')}“ zeigt " +
                        "wieder alle Videos flach (rekursiv durch alle Unterordner)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val folderName = f.folder
                    val newState = turningOn
                    viewModel.setFolderDrilldown(folderName, newState)
                    drilldownDialogFor = null
                }) { Text(if (turningOn) "Einschalten" else "Ausschalten") }
            },
            dismissButton = {
                TextButton(onClick = { drilldownDialogFor = null }) { Text("Abbrechen") }
            }
        )
    }

    downloadToDelete?.let { dnldItem ->
        AlertDialog(
            onDismissRequest = { downloadToDelete = null },
            title = { Text("Download entfernen?") },
            text = {
                Text("${dnldItem.displayTitle} wird vom Geraet entfernt. " +
                     "Das Server-Original bleibt erhalten und kann erneut " +
                     "heruntergeladen werden.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = dnldItem.id
                    downloadToDelete = null
                    viewModel.deleteDownloadForItem(id)
                }) {
                    Text("Entfernen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadToDelete = null }) { Text("Abbrechen") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = viewModel::onSearchChange,
                                placeholder = { Text("Suchen…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GoldfishOrange
                                )
                            )
                        } else {
                            Column {
                                Text(
                                    text = state.selectedSeason?.let {
                                        it.name.ifBlank { "Staffel ${it.seasonNumber}" }
                                    } ?: folder ?: (library?.name ?: "Bibliothek"),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Subtitle: bei Folder-Ansicht den Library-Namen,
                                // sonst die Counts (analog Browser-Breadcrumb).
                                val subtitle = when {
                                    state.selectedSeason != null -> null
                                    folder != null && library != null -> library.name
                                    library != null -> formatLibraryStats(library, state.stats)
                                    else -> null
                                }
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state.selectedSeason != null) {
                                viewModel.selectSeason(null)
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateHome) {
                            Icon(Icons.Filled.Home, contentDescription = "Startseite")
                        }
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Suchen",
                                tint = if (searchExpanded || state.searchQuery.isNotBlank()) GoldfishOrange
                                       else LocalContentColor.current
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                FilterToolbar(
                    state = state,
                    isTvLib = isTvLib,
                    onSortMenuClick = { showSortMenu = true },
                    onSortDirectionToggle = viewModel::toggleSortDirection,
                    onWatchedFilterChange = viewModel::onWatchedFilterChange,
                    onFavoritesToggle = viewModel::toggleFavoritesOnly,
                    onFlatViewToggle = viewModel::toggleFlatView,
                    onSeasonViewToggle = viewModel::toggleSeasonView,
                    onSelectionModeToggle = viewModel::toggleSelectionMode,
                    onToggleBucket = viewModel::toggleBucket,
                    onRatingChange = viewModel::setMinRating,
                    onRandomItem = {
                        coroutineScope.launch {
                            val item = viewModel.randomItem()
                            if (item != null) {
                                // Zusammengelegte Bibliothek: eigene Random-Route
                                if (onPlayRandom != null) onPlayRandom(libraryId, item.id)
                                else onNavigateToPlayer(item.id)
                            }
                        }
                    }
                )

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    listOf(
                        SORT_TITLE to "Titel",
                        SORT_RELEASED to "Veröffentlicht",
                        SORT_ADDED to "Hinzugefügt",
                        SORT_DURATION to "Laufzeit",
                        SORT_PLAYED to "Zuletzt gespielt"
                    ).forEach { (key, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (state.sortMode == key) GoldfishOrange
                                            else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                viewModel.onSortChange(key)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GoldfishOrange)
                }
            }
            state.errorMessage != null -> {
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Fehler: ${state.errorMessage}",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = viewModel::reload) { Text("Erneut versuchen") }
                    }
                }
            }
            state.selectedSeason != null && folder != null -> {
                val season = state.selectedSeason!!
                val baseUrl = state.baseUrl.trimEnd('/')
                val episodes = season.episodes.sortedBy { it.episode }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    items(episodes) { ep ->
                        val matchedItem = state.episodeItems[ep.season to ep.episode]
                        val epDownloadProgress = matchedItem?.id?.let { state.activeDownloads[it] }
                        val epIsSelected = matchedItem?.id?.let { it in state.selectedItemIds } == true
                        EpisodeCard(
                            episode = ep,
                            seasonNumber = season.seasonNumber,
                            imageUrl = viewModel.getEpisodeStillUrl(ep, season),
                            baseUrl = baseUrl,
                            cardWidth = cardWidth,
                            item = matchedItem,
                            isDownloaded = matchedItem?.id?.let { it in state.downloadedItemIds } == true,
                            isDownloading = epDownloadProgress != null,
                            downloadProgress = epDownloadProgress ?: 0f,
                            isSelected = epIsSelected,
                            selectionMode = state.selectionMode,
                            onSelectionToggle = {
                                matchedItem?.id?.let { viewModel.toggleItemSelection(it) }
                            },
                            onClick = { if (ep.owned && ep.itemId != null) onNavigateToItem(ep.itemId) }
                        )
                    }
                }
            }
            state.seasonData != null && folder != null -> {
                val seasons = state.seasonData!!.seasons
                val show = state.seasonData!!.show
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    // Show-Header mit Beschreibung über volle Grid-Breite
                    if (show != null) {
                        val ownedSeasons = seasons.size
                        val ownedEpisodes = seasons.sumOf { it.ownedCount }
                        val totalEpisodes = seasons.sumOf { it.total }
                        item(span = { GridItemSpan(columns) }) {
                            ShowHeader(show, ownedSeasons, ownedEpisodes, totalEpisodes,
                                onRefresh = { viewModel.refreshSeasonData() })
                        }
                    }
                    if (seasons.isEmpty()) {
                        item(span = { GridItemSpan(columns) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Keine Staffeln gefunden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(seasons) { season ->
                            SeasonCard(
                                season = season,
                                posterUrl = viewModel.getSeasonPosterUrl(season),
                                cardWidth = cardWidth,
                                onClick = { viewModel.selectSeason(season) }
                            )
                        }
                    }
                }
            }
            else -> {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val coScope = rememberCoroutineScope()

                // foldersVisible deckt zwei Faelle ab:
                //  - Library-Root (folder == null): Top-Folder-Kacheln
                //  - Drilldown-Subfolder: ViewModel hat hier Subfolder geladen
                // In beiden Faellen NUR wenn flatView aus ist und der
                // ViewModel tatsaechlich Folder geliefert hat.
                // Bei aktivem Filter (Suche/Favoriten/Watched/Buckets/Rating):
                // Folders ausblenden, der User sucht spezifische Items.
                val anyFilterActive = state.searchQuery.isNotBlank() ||
                    state.favoritesOnly ||
                    state.watchedFilter != WATCHED_ALL ||
                    state.selectedBuckets.isNotEmpty() ||
                    state.minRating > 0f
                val foldersVisible = (folder == null || state.currentFolderDrilldown) &&
                    !state.flatView &&
                    state.folders.isNotEmpty() &&
                    !anyFilterActive

                // Buchstaben-Filter (FILTER, nicht Scroll) — Tap auf einen
                // Buchstaben zeigt nur die Folders/Items mit diesem
                // Anfangsbuchstaben. Erneuter Tap löscht den Filter.
                val matchesAlpha: (String) -> Boolean = { name ->
                    val letter = state.alphaFilter
                    if (letter == null) true
                    else {
                        val first = name.trimStart()
                            .firstOrNull { it.isLetterOrDigit() }
                            ?.uppercaseChar() ?: '#'
                        if (letter == "#") !first.isLetter()
                        else first == letter[0]
                    }
                }
                val filteredFolders = if (state.alphaFilter == null) state.folders
                    else state.folders.filter { fl ->
                        // Drilldown: nur Last-Segment fuer Alpha-Filter nutzen,
                        // sonst landen alle Sport/Tennis/Football unter "S".
                        val name = fl.metadata?.title?.takeIf { it.isNotBlank() }
                            ?: fl.folder.substringAfterLast('/')
                        matchesAlpha(name)
                    }
                // Bei TV-Lib im Lib-Root mit aktivem Buchstaben-Filter: nur
                // Show-Folders zeigen, KEINE Episoden — User filtert nach
                // Serien-Anfangsbuchstabe, würde sonst zusätzlich alle B-
                // Episoden quer durch alle Serien sehen.
                // Hartes Render-Gate: bei TV/Privat im Lib-Root NIE einzelne
                // Items flach zeigen, egal was im state.items steht. Das ist
                // die ultimative Safety-Net gegen stale-items-Bugs nach
                // langer Inaktivitaet — der User sah pluetzlich alle Episoden
                // einer TV-Lib als flaches Grid.
                // Bei TV/Privat im Lib-Root NIE einzelne Items zeigen —
                // ausser der User filtert aktiv (Suche/Favoriten/Watched/
                // Buckets/Rating). Dann soll er die Treffer quer ueber alle
                // Show-Folder hinweg finden koennen.
                // Am Lib-Root NUR bei Movies-Libs flache Items zeigen; TV/Privat
                // → Folders. Auch bei noch UNBEKANNTEM Kind (library == null,
                // z. B. waehrend des ersten Ladens) unterdruecken — sonst
                // blitzen kurz alle Items flach auf und verschwinden wieder,
                // sobald die Lib als private/tv erkannt wird ("kein Inhalt
                // gefunden"-Flash). Darum `kind != "movies"` statt `tv||private`.
                // Flat-Sort-Modi (zuletzt abgespielt/hinzugefügt/Laufzeit) zeigen
                // bewusst eine flache library-weite Liste → NICHT unterdruecken.
                val suppressItemsAtRoot = library?.kind != "movies" &&
                    folder == null &&
                    !state.flatView &&
                    !anyFilterActive &&
                    !isFlatSortMode(state.sortMode)
                val filteredItems = when {
                    suppressItemsAtRoot -> emptyList()
                    state.alphaFilter == null -> state.items
                    isTvLib && foldersVisible -> emptyList()
                    else -> state.items.filter { item ->
                        val name = item.metadata?.title?.takeIf { it.isNotBlank() } ?: item.title
                        matchesAlpha(name)
                    }
                }
                val totalKacheln = (if (foldersVisible) state.folders.size else 0) + state.items.size

                // Sidebar IMMER sichtbar wenn ≥10 Kacheln, egal welcher Sort.
                // Filter-Logik funktioniert auch wenn Liste nach Datum sortiert
                // ist — der User filtert auf Buchstabe „D" und sieht alle Filme/
                // Serien mit D als Anfangsbuchstaben.
                val showAlphaSidebar = totalKacheln >= 10

                Row(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        // Filter-Header zeigt aktiven Buchstaben + Reset-Knopf
                        state.alphaFilter?.let { letter ->
                            item(span = { GridItemSpan(columns) }) {
                                Surface(
                                    color = GoldfishOrange.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Filter: Anfangsbuchstabe „$letter\" — ${filteredFolders.size + filteredItems.size} Treffer",
                                            color = GoldfishOrange,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.toggleAlphaFilter(letter) }) {
                                            Text("Aufheben", color = GoldfishOrange)
                                        }
                                    }
                                }
                            }
                        }

                        if (foldersVisible) {
                            items(
                                items = filteredFolders,
                                span = { GridItemSpan(1) }
                            ) { folderItem ->
                                FolderCard(
                                    folder = folderItem,
                                    posterUrl = viewModel.getFolderPosterUrl(folderItem),
                                    isPoster = isPoster,
                                    libraryKind = library?.kind,
                                    isAdmin = state.isAdmin,
                                    onClick = {
                                        onNavigateToFolder(libraryId, folderItem.folder, folderItem.drilldown)
                                    },
                                    onLongClick = { drilldownDialogFor = folderItem }
                                )
                            }
                        }

                        items(filteredItems) { item ->
                            val isDownloaded = item.id in state.downloadedItemIds
                            val isSelected = item.id in state.selectedItemIds
                            val downloadProgress = state.activeDownloads[item.id]
                            VideoCard(
                                item = item,
                                imageUrl = viewModel.getImageUrl(item),
                                isDownloaded = isDownloaded,
                                isDownloading = downloadProgress != null,
                                downloadProgress = downloadProgress ?: 0f,
                                isPoster = isPoster,
                                cardWidth = cardWidth,
                                isSelected = isSelected,
                                selectionMode = state.selectionMode,
                                libraryKind = library?.kind,
                                channelLabelOnTop = library?.channelLabelOnTop ?: true,
                                onClick = { onNavigateToItem(item.id) },
                                onSelectionToggle = { viewModel.toggleItemSelection(item.id) },
                                onDeleteDownload = { dnldItem -> downloadToDelete = dnldItem }
                            )
                        }

                        if (filteredItems.isEmpty() && filteredFolders.isEmpty()) {
                            item(span = { GridItemSpan(columns) }) {
                                Box(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (state.alphaFilter != null)
                                            "Keine Treffer mit „${state.alphaFilter}\""
                                        else
                                            "Keine Inhalte gefunden",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (showAlphaSidebar) {
                        // Tap auf Buchstabe → toggleAlphaFilter setzt einen
                        // Anfangsbuchstaben-Filter (FILTER, nicht Scroll wie im
                        // Browser). User-Wunsch: nur Show-Ordner / Filme mit
                        // diesem Anfangsbuchstaben sichtbar — Show-Übersicht
                        // bleibt erhalten weil wir folders filtern, nicht items.
                        AlphaSidebar(
                            activeLetter = state.alphaFilter,
                            onLetterTap = { letter ->
                                viewModel.toggleAlphaFilter(letter)
                                coScope.launch { gridState.scrollToItem(0) }
                            }
                        )
                    }
                }
            }
        }

        if (state.selectionMode) {
            BulkActionBar(
                selectedCount = state.selectedItemIds.size,
                selectedItemIds = state.selectedItemIds,
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
                onBulkWatched = { viewModel.bulkSetWatched(true) },
                onBulkFavorite = { viewModel.bulkSetFavorite(true) },
                // In Episoden-Ansicht ist state.items leer und die Items
                // liegen in state.episodeItems. Beides reichen wir der
                // bulkDownload-Funktion durch, damit sie die selektierten
                // Items in jedem View-Modus findet.
                onBulkDownload = { viewModel.bulkDownload(state.items + state.episodeItems.values) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        }
    }
}

@Composable
private fun FilterToolbar(
    state: LibraryState,
    isTvLib: Boolean,
    onSortMenuClick: () -> Unit,
    onSortDirectionToggle: () -> Unit,
    onWatchedFilterChange: (String) -> Unit,
    onFavoritesToggle: () -> Unit,
    onFlatViewToggle: () -> Unit,
    onSeasonViewToggle: () -> Unit,
    onSelectionModeToggle: () -> Unit,
    onToggleBucket: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit = {},
    onRandomItem: () -> Unit = {}
) {
    var showResMenu by remember { mutableStateOf(false) }
    var showRatingMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            FilterChip(
                selected = state.sortMode != SORT_TITLE,
                onClick = onSortMenuClick,
                label = {
                    Text(when (state.sortMode) {
                        SORT_RELEASED -> "Veröffentlicht"
                        SORT_ADDED -> "Hinzugefügt"
                        SORT_DURATION -> "Laufzeit"
                        SORT_PLAYED -> "Zuletzt gespielt"
                        else -> "Titel"
                    }, fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                }
            )
        }
        IconButton(onClick = onSortDirectionToggle, modifier = Modifier.size(32.dp)) {
            Icon(
                if (state.sortAscending) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null, tint = GoldfishOrange, modifier = Modifier.size(20.dp)
            )
        }

        FilterChip(selected = state.watchedFilter == WATCHED_ALL,
            onClick = { onWatchedFilterChange(WATCHED_ALL) },
            label = { Text("Alle", fontSize = 13.sp) })
        FilterChip(selected = state.watchedFilter == WATCHED_UNWATCHED,
            onClick = { onWatchedFilterChange(WATCHED_UNWATCHED) },
            label = { Text("Ungesehen", fontSize = 13.sp) })
        FilterChip(selected = state.watchedFilter == WATCHED_WATCHED,
            onClick = { onWatchedFilterChange(WATCHED_WATCHED) },
            label = { Text("Gesehen", fontSize = 13.sp) })

        FilterChip(
            selected = state.favoritesOnly, onClick = onFavoritesToggle,
            label = { Text("♡ Favoriten", fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = GoldfishOrange.copy(alpha = 0.2f),
                selectedLabelColor = GoldfishOrange
            )
        )

        Box {
            FilterChip(
                selected = state.selectedBuckets.isNotEmpty(),
                onClick = { showResMenu = true },
                label = {
                    Text(if (state.selectedBuckets.isEmpty()) "Auflösung"
                         else state.selectedBuckets.joinToString(", "), fontSize = 13.sp)
                }
            )
            DropdownMenu(expanded = showResMenu, onDismissRequest = { showResMenu = false }) {
                ALL_BUCKETS.forEach { bucket ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = bucket in state.selectedBuckets, onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = GoldfishOrange))
                                Text(bucket)
                            }
                        },
                        onClick = { onToggleBucket(bucket) }
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = state.minRating > 0f,
                onClick = { showRatingMenu = true },
                label = {
                    Text(if (state.minRating > 0f) "★ ≥ ${"%.0f".format(state.minRating)}"
                         else "Bewertung", fontSize = 13.sp)
                }
            )
            DropdownMenu(expanded = showRatingMenu, onDismissRequest = { showRatingMenu = false }) {
                listOf(0f, 5f, 6f, 7f, 7.5f, 8f, 8.5f, 9f).forEach { r ->
                    DropdownMenuItem(
                        text = { Text(if (r == 0f) "Alle" else "★ ≥ ${"%.1f".format(r)}",
                            color = if (state.minRating == r) GoldfishOrange else MaterialTheme.colorScheme.onSurface) },
                        onClick = { onRatingChange(r); showRatingMenu = false }
                    )
                }
            }
        }

        FilterChip(selected = state.flatView, onClick = onFlatViewToggle,
            label = { Text("📂 Flach", fontSize = 13.sp) })
        if (isTvLib) {
            FilterChip(selected = state.seasonView, onClick = onSeasonViewToggle,
                label = { Text("📺 Staffeln", fontSize = 13.sp) })
        }

        FilterChip(selected = false, onClick = onRandomItem,
            label = { Text("🎲 Zufall", fontSize = 13.sp) })

        FilterChip(selected = state.selectionMode, onClick = onSelectionModeToggle,
            label = { Text("☑ Auswählen", fontSize = 13.sp) })
    }
}

/**
 * Vertikale A-Z-Sidebar rechts vom Grid für schnellen Buchstaben-Sprung.
 * Tap auf einen Buchstaben scrollt zum ersten Item mit diesem Anfangsbuchstaben.
 * Drag entlang der Sidebar funktioniert auch (pointerInput mit y-basiertem
 * Index in die Buchstaben-Liste).
 */
@Composable
private fun AlphaSidebar(
    activeLetter: String?,
    onLetterTap: (String) -> Unit
) {
    val letters = remember { listOf("#") + ('A'..'Z').map { it.toString() } }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(30.dp)
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(6.dp)
            ),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { letter ->
            val isActive = activeLetter == letter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (isActive) Modifier.background(GoldfishOrange)
                        else Modifier
                    )
                    .clickable { onLetterTap(letter) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = letter,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.Black
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BulkActionBar(
    selectedCount: Int,
    selectedItemIds: Set<Int>,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBulkWatched: () -> Unit,
    onBulkFavorite: () -> Unit,
    onBulkDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedCount ausgewählt",
                    style = MaterialTheme.typography.labelLarge,
                    color = GoldfishOrange,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSelectAll) {
                    Text("Alle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClearSelection) {
                    Text("Keine", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectedCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onBulkWatched,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("✓ Gesehen", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onBulkFavorite,
                        colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
                    ) {
                        Text("♡ Favorit", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onBulkDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("⬇ Download", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private val deNumberFormat: NumberFormat = NumberFormat.getInstance(Locale.GERMAN)

// Pendant zum Browser-loadCount in views.js. TV-Library-Root: "N Folgen ·
// M Serien". Sonst: "N Videos". Returnt null wenn noch nichts geladen.
private fun formatLibraryStats(library: Library, stats: LibraryStats?): String? {
    if (stats == null) return null
    val n = stats.totalItems
    val nFmt = deNumberFormat.format(n)
    return if (library.kind == "tv" && stats.folderCount > 0) {
        val showWord = if (stats.folderCount == 1) "Serie" else "Serien"
        val epWord = if (n == 1) "Folge" else "Folgen"
        val mFmt = deNumberFormat.format(stats.folderCount)
        "$nFmt $epWord · $mFmt $showWord"
    } else {
        val word = if (n == 1) "Video" else "Videos"
        "$nFmt $word"
    }
}

private val SHOW_STATUS_DE = mapOf(
    "Returning Series" to "Laufend",
    "Ended" to "Beendet",
    "Canceled" to "Abgesetzt",
    "Cancelled" to "Abgesetzt",
    "In Production" to "In Produktion",
    "Planned" to "Geplant",
    "Pilot" to "Pilot"
)

@Composable
private fun ShowHeader(
    show: com.goldfish.android.data.model.ShowInfo,
    ownedSeasons: Int,
    ownedEpisodes: Int,
    totalEpisodesFromSeasons: Int,
    onRefresh: () -> Unit = {}
) {
    val rating = show.rating?.takeIf { it > 0 }
    val genres = show.genres.take(4).joinToString(" · ")

    val y1 = show.firstAirDate?.take(4).orEmpty()
    val y2 = show.lastAirDate?.take(4).orEmpty()
    val yearRange = when {
        y1.isNotBlank() && y2.isNotBlank() && y1 != y2 -> "$y1–$y2"
        y1.isNotBlank() -> y1
        y2.isNotBlank() -> y2
        else -> show.year?.takeIf { it > 0 }?.toString().orEmpty()
    }
    val statusLabel = show.status?.let { SHOW_STATUS_DE[it] ?: it }.orEmpty()

    val totalSeasons = show.numberOfSeasons ?: ownedSeasons
    val totalEpisodes = show.numberOfEpisodes ?: totalEpisodesFromSeasons
    val seasonsLabel = when {
        totalSeasons <= 0 -> ""
        ownedSeasons in 1 until totalSeasons -> "$ownedSeasons/$totalSeasons Staffeln"
        totalSeasons == 1 -> "1 Staffel"
        else -> "$totalSeasons Staffeln"
    }
    val episodesLabel = when {
        totalEpisodes <= 0 -> ""
        ownedEpisodes in 1 until totalEpisodes -> "$ownedEpisodes/$totalEpisodes Folgen"
        else -> "$totalEpisodes Folgen"
    }

    val infoLine = listOf(yearRange, statusLabel, seasonsLabel, episodesLabel)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Titel + Refresh-Button (rechts)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = show.title.orEmpty().ifBlank { "Unbenannt" },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // ↻ TMDB neu laden — fuer den Fall, dass im Browser die
                // Zuordnung korrigiert wurde und die App noch die alte zeigt.
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "TMDB neu laden",
                        tint = GoldfishOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            // Jahres-Range · Status · Staffeln · Folgen
            if (infoLine.isNotBlank()) {
                Text(
                    text = infoLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Rating + Genres
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rating != null) {
                    Text(
                        text = "★ ${"%.1f".format(rating)}",
                        color = GoldfishOrange,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (genres.isNotBlank()) Spacer(Modifier.width(8.dp))
                }
                if (genres.isNotBlank()) {
                    Text(
                        text = genres,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Beschreibung
            show.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(
    season: SeasonInfo,
    posterUrl: String,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 1.5f
    val ownedText = if (season.total > 0) "${season.ownedCount}/${season.total} Folgen"
                    else "${season.ownedCount} Folgen"

    Column(
        modifier = Modifier.width(cardWidth).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth).height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (posterUrl.isNotBlank()) {
                AsyncImage(
                    model = posterUrl, contentDescription = season.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.VideoLibrary, null, tint = GoldfishOrange,
                        modifier = Modifier.size(48.dp))
                }
            }
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(ownedText, color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = season.name.ifBlank { "Staffel ${season.seasonNumber}" },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EpisodeCard(
    episode: com.goldfish.android.data.model.EpisodeInfo,
    seasonNumber: Int,
    imageUrl: String,
    baseUrl: String,
    cardWidth: Dp,
    item: Item? = null,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onSelectionToggle: () -> Unit = {},
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 9f / 16f
    val epCode = "S${seasonNumber.toString().padStart(2,'0')}E${episode.episode.toString().padStart(2,'0')}"

    val resText = item?.let { resolutionLabel(it.width, it.height) } ?: ""

    // Episoden-Selection: nur Episoden mit echtem itemId koennen selektiert
    // werden ("Fehlt"-Platzhalter sind nicht selektierbar). Im Selection-Mode
    // togglet ein Tap die Auswahl, sonst navigiert er.
    val canSelect = episode.owned && episode.itemId != null
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(enabled = canSelect) {
                if (selectionMode) onSelectionToggle() else onClick()
            }
            .then(if (!episode.owned) Modifier.alpha(0.45f) else Modifier)
    ) {
        Box(
            modifier = Modifier.width(cardWidth).height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (isSelected) Modifier.border(2.dp, GoldfishOrange, RoundedCornerShape(6.dp))
                    else Modifier
                )
        ) {
            AsyncImage(
                model = imageUrl, contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (episode.owned) Color(0xCC000000) else Color(0xCCCC0000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(epCode, color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold)
            }
            if (resText.isNotEmpty()) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(Color(0xBB000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(resText, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            // Offline-/Download-Indikator — gleiches Pill-Design wie in
            // VideoCard, damit der Indikator nicht uebersehen wird (vorher
            // war es ein 16dp-Icon ohne Hintergrund in der dunklen
            // Bottom-Gradient-Ecke und kaum sichtbar). Position: TopEnd,
            // weil TopStart vom SxxExx-Code belegt ist.
            if (isDownloading || isDownloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDownloading) {
                        if (downloadProgress > 0f && downloadProgress.isFinite()) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                color = GoldfishOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                color = GoldfishOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Offline verfügbar",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            if (!episode.owned) {
                Box(
                    modifier = Modifier.align(Alignment.Center).padding(4.dp)
                        .clip(RoundedCornerShape(3.dp)).background(Color(0xCCCC0000))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text("Fehlt", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            // Selection-Overlay analog VideoCard: oranger Tint + zentrierter
            // Haken bei isSelected. Nur sichtbar wenn die Episode selektierbar
            // ist (owned + itemId). Sonst waere der Mode misleading.
            if (selectionMode && canSelect) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isSelected) Color(0x44F97316) else Color.Transparent
                        )
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Ausgewählt",
                        tint = GoldfishOrange,
                        modifier = Modifier.align(Alignment.Center).size(32.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = episode.title ?: epCode,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = if (episode.owned) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun resolutionLabel(width: Int, height: Int): String {
    val effectiveHeight = maxOf(height, (width * 9.0 / 16.0).toInt())
    return when {
        effectiveHeight >= 2160 -> "4K"
        effectiveHeight >= 1440 -> "2K"
        effectiveHeight >= 1080 -> "1080p"
        effectiveHeight >= 720  -> "720p"
        effectiveHeight >= 576  -> "576p"
        effectiveHeight >= 480  -> "480p"
        effectiveHeight > 0     -> "360p"
        else -> ""
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: FolderItem,
    posterUrl: String,
    isPoster: Boolean,
    libraryKind: String? = null,
    isAdmin: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isTvLib = libraryKind == "tv"
    val isPrivateLib = libraryKind == "private"
    val countLabel = when {
        isTvLib -> "${folder.count} Folgen"
        isPrivateLib -> "${folder.count} Videos"
        else -> "${folder.count}"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = if (isAdmin) ({ onLongClick() }) else null
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (isPoster) 2f / 3f else 16f / 9f)
            ) {
                if (posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = folder.folder,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = GoldfishOrange,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                folder.metadata?.rating?.takeIf { it > 0 }?.let { rating ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "★ ${"%.1f".format(rating)}",
                            color = GoldfishOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Drilldown-Indikator: zeigt an, dass dieser Folder
                // Unterordner als Navigations-Ebene exponiert (Browser-
                // Pendant: das ▸-Icon auf der Folder-Kachel).
                if (folder.drilldown) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "▸",
                            color = GoldfishOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (folder.count > 0 && (isTvLib || isPrivateLib)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xBB000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = countLabel,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                // Display-Name: TMDB-Titel wenn vorhanden, sonst nur das
                // letzte Pfad-Segment (sodass im Drilldown nicht der ganze
                // Parent-Pfad mitlaeuft, z.B. "Sport/Tennis" → "Tennis").
                // folder.folder bleibt unangetastet — wird fuer die Subfolder-
                // Navigation gebraucht.
                val displayName = folder.metadata?.title?.takeIf { it.isNotBlank() }
                    ?: folder.folder.substringAfterLast('/')
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                folder.metadata?.year?.takeIf { it > 0 }?.let { year ->
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
