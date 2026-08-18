package com.goldfish.android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.data.model.HomeSection
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Library
import com.goldfish.android.ui.components.VideoCard
import com.goldfish.android.ui.theme.GoldfishOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToItem: (Int) -> Unit,
    onNavigateToLibrary: (Int) -> Unit,
    onNavigateToLocalLibrary: (Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToCollections: () -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToMergedLibrary: (List<Int>) -> Unit = {},
    onNavigateToMergedLocalLibrary: (List<Int>) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Abmelden") },
            text = { Text("Wirklich abmelden?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                    onLogout()
                }) {
                    Text("Abmelden", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Goldfish",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoldfishOrange
                        )
                    )
                },
                actions = {
                    state.username?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Suchen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToCollections) {
                        Icon(Icons.Filled.Collections, contentDescription = "Sammlungen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToPlaylists) {
                        Icon(Icons.Filled.PlaylistPlay, contentDescription = "Playlists", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Abmelden")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GoldfishOrange)
            }
        } else if (state.errorMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Fehler: ${state.errorMessage}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = viewModel::loadData) {
                        Text("Erneut versuchen")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Offline-Filter-Toggle ueber der Library-Liste. Persistent
                // ueber DataStore — gilt fuer Home, Library, Folder, Random.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = state.offlineOnly,
                            onClick = { viewModel.setOfflineOnly(!state.offlineOnly) },
                            label = { Text("📥 Nur Offline", fontSize = 13.sp) }
                        )
                        if (state.offlineOnly) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Es werden nur Inhalte angezeigt, die lokal verfuegbar sind.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }

                // Library shortcuts — Server + Lokal kombiniert. Server-Libs
                // im Offline-Mode aus dem Room-Cache, sonst aus der API-Antwort.
                // Lokale Libs (on-device via SAF) bekommen ein 📁-Badge.
                val visibleLibraries = viewModel.displayLibraries()
                if (visibleLibraries.isNotEmpty()) {
                    item {
                        LibraryRow(
                            libraries = visibleLibraries,
                            onLibraryClick = { lib ->
                                if (lib.isLocal) {
                                    // Wenn die Bibliothek Teil eines Merge-Paares ist,
                                    // direkt die zusammengelegte Ansicht oeffnen —
                                    // unabhaengig davon welche der beiden getippt wurde.
                                    val mergedLocal = viewModel.mergedLocalLibraryIds()
                                    if (mergedLocal.size == 2 && lib.id in mergedLocal) {
                                        onNavigateToMergedLocalLibrary(mergedLocal)
                                    } else {
                                        onNavigateToLocalLibrary(lib.id)
                                    }
                                } else {
                                    val mergedServer = viewModel.mergedLibraryIds()
                                    if (mergedServer.size == 2 && lib.id in mergedServer) {
                                        onNavigateToMergedLibrary(mergedServer)
                                    } else {
                                        onNavigateToLibrary(lib.id)
                                    }
                                }
                            }
                        )
                    }
                } else if (state.offlineOnly) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Keine Offline-Inhalte vorhanden.\nLade Videos ueber den Detail-Screen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Zusammengelegte Server-Bibliothek
                val mergedIds = viewModel.mergedLibraryIds()
                if (mergedIds.size == 2 && !state.offlineOnly) {
                    val serverLibIds = state.libraries.map { it.id }.toSet()
                    val bothAvailable = mergedIds.all { it in serverLibIds }
                    item {
                        MergedLibraryChip(
                            ids = mergedIds,
                            libraries = state.libraries,
                            available = bothAvailable,
                            onClick = { if (bothAvailable) onNavigateToMergedLibrary(mergedIds) }
                        )
                    }
                }

                // Zusammengelegte lokale Bibliothek
                val mergedLocalIds = viewModel.mergedLocalLibraryIds()
                if (mergedLocalIds.size == 2) {
                    val localLibIds = state.localLibraries.map { it.id }.toSet()
                    val bothLocalAvailable = mergedLocalIds.all { it in localLibIds }
                    item {
                        MergedLocalLibraryChip(
                            ids = mergedLocalIds,
                            localLibraries = state.localLibraries,
                            available = bothLocalAvailable,
                            onClick = { if (bothLocalAvailable) onNavigateToMergedLocalLibrary(mergedLocalIds) }
                        )
                    }
                }

                // Home-Layout seit 2026-05-10 (passt zum Browser): zwei
                // library-uebergreifende Streifen ganz oben — „Fortsetzen"
                // und „Als naechstes" — danach pro Library „Zuletzt hinzu-
                // gefuegt" in der Reihenfolge der Topbar (server liefert
                // Sections nach libraries.sort_order sortiert).
                // Im Offline-Mode komplett ausblenden — die Strips kommen
                // alle vom Server und sind nicht offline-tauglich.
                val sections = if (state.offlineOnly) emptyList()
                    else state.homeData?.sections.orEmpty()
                val allContinue = sections.flatMap { it.continueItems }
                    .sortedByDescending { it.releasedAt ?: it.addedAt ?: "" }
                    .take(24)
                val allNextUp = sections.flatMap { it.nextUp }
                    .sortedByDescending { it.addedAt ?: "" }
                    .take(24)
                // Lookup libraryId → kind, damit Items in cross-library
                // Streifen ihren eigenen Lib-Kind kennen (Movies-Items
                // bekommen Poster, TV-Episoden Thumb, etc).
                val kindByLibId = sections.associate { it.library.id to it.library.kind }
                val kindForItem: (Item) -> String = { kindByLibId[it.libraryId] ?: "" }
                // Lookup libraryId → channelLabelOnTop. Wenn die Lib in den
                // Home-Sections nicht enthalten ist (selten), default true.
                val channelLabelOnTopByLibId = sections.associate { it.library.id to it.library.channelLabelOnTop }
                val channelLabelOnTopForItem: (Item) -> Boolean = {
                    channelLabelOnTopByLibId[it.libraryId] ?: true
                }
                if (allContinue.isNotEmpty()) {
                    item {
                        HomeSectionRow(
                            title = "▶ Fortsetzen",
                            subtitle = "alle Bibliotheken",
                            items = allContinue,
                            libraryKind = "mixed",
                            baseUrl = state.baseUrl,
                            getImageUrl = viewModel::getImageUrl,
                            onItemClick = onNavigateToItem,
                            kindForItem = kindForItem,
                            channelLabelOnTopForItem = channelLabelOnTopForItem
                        )
                    }
                }
                if (allNextUp.isNotEmpty()) {
                    item {
                        HomeSectionRow(
                            title = "📺 Als nächstes",
                            subtitle = "alle Bibliotheken",
                            items = allNextUp,
                            libraryKind = "mixed",
                            baseUrl = state.baseUrl,
                            getImageUrl = viewModel::getImageUrl,
                            onItemClick = onNavigateToItem,
                            kindForItem = kindForItem,
                            channelLabelOnTopForItem = channelLabelOnTopForItem
                        )
                    }
                }
                // Pro Library nur „Zuletzt hinzugefuegt", in API-Reihenfolge.
                sections.forEach { section ->
                    if (section.recent.isNotEmpty()) {
                        item {
                            HomeSectionRow(
                                title = "🆕 Zuletzt hinzugefügt",
                                subtitle = section.library.name,
                                items = section.recent,
                                libraryKind = section.library.kind,
                                baseUrl = state.baseUrl,
                                getImageUrl = viewModel::getImageUrl,
                                onItemClick = onNavigateToItem,
                                channelLabelOnTopForItem = { section.library.channelLabelOnTop }
                            )
                        }
                    }
                }

                if (state.homeData?.sections.isNullOrEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Keine Inhalte gefunden.\nBitte zuerst eine Bibliothek scannen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    libraries: List<LibraryDisplay>,
    onLibraryClick: (LibraryDisplay) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Bibliotheken",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(libraries) { library ->
                LibraryChip(library = library, onClick = { onLibraryClick(library) })
            }
        }
    }
}

@Composable
private fun LibraryChip(library: LibraryDisplay, onClick: () -> Unit) {
    val kindIcon = when (library.kind) {
        "movies" -> "🎬"
        "tv" -> "📺"
        "private" -> "🏠"
        else -> "📁"
    }
    ElevatedCard(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = kindIcon, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = library.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Badge fuer lokale Libs (on-device via SAF) — visuell klar
            // unterscheidbar von Server-Libs.
            if (library.isLocal) {
                Text(text = "📁", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    title: String,
    subtitle: String,
    items: List<Item>,
    libraryKind: String,
    baseUrl: String,
    getImageUrl: (Int, Int?, String) -> String,
    onItemClick: (Int) -> Unit,
    // Pro-Item-Kind-Lookup: noetig fuer cross-library Streifen (Continue/
    // NextUp), wo Items aus verschiedenen Libs gemischt sind. Default schaut
    // auf libraryKind, dann hat ein Movies-Item in einer „mixed"-Section
    // trotzdem ein Poster statt eines Thumbs.
    kindForItem: (Item) -> String = { libraryKind },
    // Per-Item-Lookup fuer das channel_label_on_top-Flag aus der Library.
    // Default true (Server-Default), wirkt nur bei kind=private.
    channelLabelOnTopForItem: (Item) -> Boolean = { true }
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = GoldfishOrange
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                val itemKind = kindForItem(item)
                val isPoster = itemKind == "movies" || itemKind == "tv"
                val imageUrl = getImageUrl(item.id, item.metadataId, itemKind)
                VideoCard(
                    item = item,
                    imageUrl = imageUrl,
                    isPoster = isPoster,
                    cardWidth = if (isPoster) 110.dp else 180.dp,
                    libraryKind = itemKind,
                    channelLabelOnTop = channelLabelOnTopForItem(item),
                    onClick = { onItemClick(item.id) }
                )
            }
        }
    }
}

/** Kachel für eine zusammengelegte lokale Bibliothek. */
@Composable
fun MergedLocalLibraryChip(
    ids: List<Int>,
    localLibraries: List<com.goldfish.android.data.local.LocalLibraryEntity>,
    available: Boolean,
    onClick: () -> Unit
) {
    val names = ids.mapNotNull { id -> localLibraries.find { it.id == id }?.name }
    val label = if (names.size == 2) "${names[0]} + ${names[1]}" else "Zusammengelegt (lokal)"
    Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = false,
            onClick = onClick,
            enabled = available,
            label = {
                Text(
                    text = "🔗 $label",
                    fontSize = 13.sp,
                    color = if (available)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        )
        if (!available) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Eine Bibliothek nicht verfügbar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Kachel für die zusammengelegte Server-Bibliothek im HomeScreen. */
@Composable
fun MergedLibraryChip(
    ids: List<Int>,
    libraries: List<com.goldfish.android.data.model.Library>,
    available: Boolean,
    onClick: () -> Unit
) {
    val names = ids.mapNotNull { id -> libraries.find { it.id == id }?.name }
    val label = if (names.size == 2) "${names[0]} + ${names[1]}" else "Zusammengelegt"
    Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = false,
            onClick = onClick,
            enabled = available,
            label = {
                Text(
                    text = "🔗 $label",
                    fontSize = 13.sp,
                    color = if (available)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        )
        if (!available) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Eine Bibliothek nicht verfügbar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
