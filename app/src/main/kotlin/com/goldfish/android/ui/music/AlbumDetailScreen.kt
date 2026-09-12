package com.goldfish.android.ui.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.goldfish.android.ui.components.MusicTrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Int,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showEditAlbum by remember { mutableStateOf(false) }
    var editTrackId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(albumId) { viewModel.load(albumId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.album?.displayTitle ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck") } },
                actions = {
                    if (state.isAdmin) {
                        IconButton(onClick = { showEditAlbum = true }) { Icon(Icons.Filled.Edit, "Album bearbeiten") }
                    }
                    IconButton(onClick = { viewModel.toggleAlbumFavorite() }) {
                        val fav = state.detail?.album?.favorite == true
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Album-Favorit")
                    }
                }
            )
        }
    ) { padding ->
        val detail = state.detail
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.errorMessage != null -> Text("Fehler: ${state.errorMessage}", modifier = Modifier.padding(padding).padding(16.dp))
            detail != null -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            AsyncImage(
                                model = "${state.baseUrl.trimEnd('/')}/api/poster/album/${detail.album.id}",
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(detail.album.displayTitle, style = MaterialTheme.typography.titleLarge)
                                val sub = listOfNotNull(
                                    detail.album.artist.takeIf { it.isNotBlank() },
                                    detail.album.year?.takeIf { it > 0 }?.toString(),
                                    detail.album.genre
                                ).joinToString(" · ")
                                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    Button(onClick = { viewModel.playAll() }) {
                                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Abspielen")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { viewModel.shuffleAlbum() }) {
                                        Icon(Icons.Filled.Shuffle, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(detail.tracks) { index, track ->
                        MusicTrackRow(
                            item = track,
                            showTrackNo = true,
                            onClick = { viewModel.playTrack(index) },
                            onFavoriteToggle = { viewModel.toggleTrackFavorite(track.id, track.favorite) }
                        )
                        if (state.isAdmin) {
                            TextButton(onClick = { editTrackId = track.id }, modifier = Modifier.padding(start = 40.dp)) {
                                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Bearbeiten", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditAlbum && state.detail != null) {
        EditAlbumMetadataDialog(
            album = state.detail!!.album,
            onDismiss = { showEditAlbum = false },
            onSave = { artist, album, genre, year ->
                viewModel.saveAlbumMetadata(artist, album, genre, year)
                showEditAlbum = false
            }
        )
    }
    val trackToEdit = state.detail?.tracks?.find { it.id == editTrackId }
    if (trackToEdit != null) {
        EditTrackMetadataDialog(
            item = trackToEdit,
            onDismiss = { editTrackId = null },
            onSave = { title, artist, album, trackNo, genre ->
                viewModel.saveTrackMetadata(trackToEdit.id, title, artist, album, trackNo, genre)
                editTrackId = null
            }
        )
    }
}
