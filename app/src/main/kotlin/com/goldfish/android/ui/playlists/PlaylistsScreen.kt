package com.goldfish.android.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Playlist
import com.goldfish.android.ui.components.VideoCard
import com.goldfish.android.ui.theme.GoldfishOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onNavigateToItem: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidthDp >= 840 -> 4
        else -> 2
    }
    val gap = 8.dp
    val cardWidth = ((screenWidthDp.dp - gap * (columns + 1)) / columns)

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newPlaylistName = "" },
            title = { Text("Neue Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldfishOrange)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName.trim())
                            showCreateDialog = false
                            newPlaylistName = ""
                        }
                    }
                ) { Text("Erstellen", color = GoldfishOrange) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) {
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
                        text = state.selectedPlaylist?.name ?: "Playlists",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedPlaylist != null) viewModel.closePlaylist()
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (state.selectedPlaylist == null) {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Neue Playlist", tint = GoldfishOrange)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
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
                            Text("Fehler: ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = viewModel::loadPlaylists) { Text("Erneut versuchen") }
                        }
                    }
                }
                state.selectedPlaylist != null -> {
                    // Playlist items view
                    if (state.isLoadingItems) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GoldfishOrange)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(gap),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(gap),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.playlistItems) { item ->
                                val isPoster = item.metadata?.tmdbType == "movie" || item.metadata?.tmdbType == "episode"
                                VideoCard(
                                    item = item,
                                    imageUrl = viewModel.getItemImageUrl(item),
                                    isPoster = isPoster,
                                    cardWidth = ((screenWidthDp.dp - gap * 4) / 3),
                                    onClick = { onNavigateToItem(item.id) }
                                )
                            }
                            if (state.playlistItems.isEmpty()) {
                                item(span = { GridItemSpan(3) }) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Keine Videos in dieser Playlist", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Playlists grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.playlists) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                baseUrl = state.baseUrl,
                                cardWidth = cardWidth.value.dp,
                                onDelete = { viewModel.deletePlaylist(playlist.id) },
                                onClick = { viewModel.openPlaylist(playlist) }
                            )
                        }
                        if (state.playlists.isEmpty()) {
                            item(span = { GridItemSpan(columns) }) {
                                Box(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "Keine Playlists vorhanden",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Button(onClick = { showCreateDialog = true }) {
                                            Text("+ Neue Playlist")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    baseUrl: String,
    cardWidth: androidx.compose.ui.unit.Dp,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val cardHeight = cardWidth * 0.8f
    var showDeleteDialog by remember { mutableStateOf(false) }
    val base = baseUrl.trimEnd('/')
    val posterUrl = when {
        playlist.posterMetadataId != null -> "$base/api/poster/metadata/${playlist.posterMetadataId}"
        playlist.posterItemId != null -> "$base/api/thumb/${playlist.posterItemId}"
        else -> null
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Playlist löschen") },
            text = { Text("\"${playlist.name}\" wirklich löschen?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    Card(
        modifier = Modifier
            .width(cardWidth)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
        ) {
            if (posterUrl != null) {
                // Poster des ersten Items + dunkler Verlauf für Lesbarkeit der Badges
                coil3.compose.AsyncImage(
                    model = posterUrl,
                    contentDescription = playlist.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                )
                // Halbtransparenter Verlauf für Lesbarkeit der Overlay-Elemente
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x55000000), Color(0x99000000))
                            )
                        )
                )
                // Dezentes Playlist-Icon oben links
                Icon(
                    imageVector = Icons.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(28.dp)
                )
            } else {
                // Fallback: blauer Verlauf mit großem Icon (leere Playlist ohne Items)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1565C0), Color(0xFF0D47A1))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            // Delete button top-right
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Löschen",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
            // Item count badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${playlist.itemCount} Videos",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
