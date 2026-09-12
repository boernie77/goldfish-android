package com.goldfish.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.MusicAlbum
import com.goldfish.android.ui.components.AlbumCard
import com.goldfish.android.ui.components.MusicTrackRow

private fun albumCoverUrl(albumId: Int, baseUrl: String): String =
    "${baseUrl.trimEnd('/')}/api/poster/album/$albumId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(
    libraryId: Int,
    libraryName: String,
    onBack: () -> Unit,
    onOpenAlbum: (Int) -> Unit,
    onOpenMusicPlaylists: () -> Unit,
    viewModel: MusicLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showGenreDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var addToPlaylistItem by remember { mutableStateOf<Item?>(null) }
    val context = LocalContext.current

    LaunchedEffect(libraryId) { viewModel.load(libraryId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(libraryName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck") }
                },
                actions = {
                    IconButton(onClick = onOpenMusicPlaylists) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Playlists") }
                    IconButton(onClick = { showSortDialog = true }) { Icon(Icons.Filled.Sort, "Sortierung") }
                    IconButton(onClick = { showGenreDialog = true }) { Icon(Icons.Filled.FilterList, "Genre-Filter") }
                    IconButton(onClick = { viewModel.toggleFavoritesOnly() }) {
                        Icon(
                            if (state.favoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Nur Favoriten"
                        )
                    }
                    IconButton(onClick = {
                        viewModel.setDisplayMode(
                            when (state.displayMode) {
                                MusicDisplayMode.GRID -> MusicDisplayMode.LIST
                                MusicDisplayMode.LIST -> MusicDisplayMode.ALL_TRACKS
                                MusicDisplayMode.ALL_TRACKS -> MusicDisplayMode.GRID
                            }
                        )
                    }) {
                        Icon(
                            when (state.displayMode) {
                                MusicDisplayMode.GRID -> Icons.Filled.GridView
                                MusicDisplayMode.LIST -> Icons.AutoMirrored.Filled.List
                                MusicDisplayMode.ALL_TRACKS -> Icons.Filled.MusicNote
                            },
                            "Ansicht wechseln"
                        )
                    }
                    IconButton(onClick = { viewModel.shufflePlayLibrary() }) { Icon(Icons.Filled.Shuffle, "Zufall") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Titel, Kuenstler oder Album durchsuchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )

            when {
                state.errorMessage != null -> {
                    Text(
                        "Fehler: ${state.errorMessage}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                // Suche zeigt IMMER die Treffer-TITEL als flache Liste, nie als
                // Alben gebuendelt (exakt das gleiche Bug-Muster wie zuvor im
                // Browser/iOS gefixt — siehe Server-CLAUDE.md).
                state.searchQuery.isNotBlank() || state.displayMode == MusicDisplayMode.ALL_TRACKS -> {
                    TrackList(
                        items = state.filteredTracks,
                        onFavoriteToggle = viewModel::toggleTrackFavorite,
                        onAddToPlaylist = { addToPlaylistItem = it }
                    ) {
                        viewModel.playTrack(it, state.filteredTracks)
                    }
                }
                state.displayMode == MusicDisplayMode.LIST -> {
                    LazyColumn {
                        items(state.filteredAlbums, key = { it.id }) { album ->
                            AlbumListRow(album, viewModel, onOpenAlbum)
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(12.dp)) {
                        items(state.filteredAlbums, key = { it.id }) { album ->
                            AlbumCard(
                                album = album,
                                coverUrl = albumCoverUrl(album.id, state.baseUrl),
                                onClick = { onOpenAlbum(album.id) },
                                onFavoriteToggle = { viewModel.toggleAlbumFavorite(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGenreDialog) {
        GenreFilterDialog(
            genres = state.availableGenres,
            selected = state.selectedGenres,
            onDismiss = { showGenreDialog = false },
            onConfirm = { viewModel.setSelectedGenres(it); showGenreDialog = false }
        )
    }
    if (showSortDialog) {
        SortPickerDialog(
            currentSort = state.sortMode,
            ascending = state.sortAscending,
            showRecentlyPlayed = state.displayMode == MusicDisplayMode.ALL_TRACKS,
            recentlyPlayedFirst = state.recentlyPlayedFirst,
            onDismiss = { showSortDialog = false },
            onConfirm = { sort, asc -> viewModel.setSort(sort, asc); showSortDialog = false },
            onToggleRecentlyPlayed = { viewModel.toggleRecentlyPlayedFirst() }
        )
    }
    addToPlaylistItem?.let { item ->
        AddToPlaylistDialog(
            itemId = item.id,
            onDismiss = { addToPlaylistItem = null },
            onDone = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                addToPlaylistItem = null
            }
        )
    }
}

@Composable
private fun TrackList(
    items: List<Item>,
    onFavoriteToggle: (Item) -> Unit,
    onAddToPlaylist: (Item) -> Unit,
    onClick: (Item) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Titel gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn {
        items(items, key = { it.id }) { item ->
            MusicTrackRow(
                item = item,
                onClick = { onClick(item) },
                onFavoriteToggle = onFavoriteToggle,
                onAddToPlaylist = onAddToPlaylist
            )
        }
    }
}

@Composable
private fun AlbumListRow(
    album: MusicAlbum,
    viewModel: MusicLibraryViewModel,
    onOpenAlbum: (Int) -> Unit
) {
    ListItem(
        headlineContent = { Text(album.displayTitle) },
        supportingContent = {
            val sub = listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.genre, album.trackCount?.let { "$it Titel" })
                .joinToString(" · ")
            Text(sub)
        },
        trailingContent = {
            IconButton(onClick = { viewModel.toggleAlbumFavorite(album) }) {
                Icon(
                    if (album.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null
                )
            }
        },
        modifier = Modifier.clickable { onOpenAlbum(album.id) }
    )
}
