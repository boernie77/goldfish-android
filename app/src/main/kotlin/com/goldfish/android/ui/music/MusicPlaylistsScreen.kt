package com.goldfish.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.ui.components.MusicTrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlaylistsScreen(
    onBack: () -> Unit,
    viewModel: MusicPlaylistsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedPlaylist?.name ?: "Musik-Playlists") },
                navigationIcon = {
                    IconButton(onClick = { if (state.selectedPlaylist != null) viewModel.closePlaylist() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck")
                    }
                },
                actions = {
                    if (state.selectedPlaylist == null) {
                        IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Filled.Add, "Neue Playlist") }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selected = state.selectedPlaylist
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                selected == null -> {
                    if (state.playlists.isEmpty()) {
                        Text(
                            "Noch keine Musik-Playlists.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(state.playlists, key = { it.id }) { playlist ->
                                ListItem(
                                    headlineContent = { Text(playlist.name) },
                                    supportingContent = { Text("${playlist.itemCount} Titel") },
                                    trailingContent = {
                                        IconButton(onClick = { viewModel.deletePlaylist(playlist.id) }) {
                                            Icon(Icons.Filled.Delete, "Loeschen")
                                        }
                                    },
                                    modifier = Modifier.clickable { viewModel.openPlaylist(playlist) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (state.isLoadingItems) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn {
                            itemsIndexed(state.playlistItems) { index, item ->
                                MusicTrackRow(
                                    item = item,
                                    onClick = { viewModel.playTrack(index) },
                                    onFavoriteToggle = { viewModel.toggleFavorite(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newName = "" },
            title = { Text("Neue Musik-Playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) { viewModel.createPlaylist(newName.trim()); showCreateDialog = false; newName = "" }
                }) { Text("Erstellen") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false; newName = "" }) { Text("Abbrechen") } }
        )
    }
}
