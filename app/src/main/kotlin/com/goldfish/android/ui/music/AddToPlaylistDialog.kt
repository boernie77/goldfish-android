package com.goldfish.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** "Zu Musik-Playlist hinzufuegen" — bestehende Playlist waehlen ODER
 *  direkt eine neue anlegen und den Track hineinlegen. Server-Konvention:
 *  ein bereits enthaltener Track meldet `added=false`, kein Fehler. */
@Composable
fun AddToPlaylistDialog(
    itemId: Int,
    onDismiss: () -> Unit,
    onDone: (message: String) -> Unit,
    viewModel: AddToPlaylistViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu Playlist hinzufuegen") },
        text = {
            Column {
                if (state.isLoading) {
                    CircularProgressIndicator()
                } else {
                    if (state.playlists.isEmpty()) {
                        Text("Noch keine Musik-Playlists.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(state.playlists, key = { it.id }) { playlist ->
                                ListItem(
                                    headlineContent = { Text(playlist.name) },
                                    supportingContent = { Text("${playlist.itemCount} Titel") },
                                    modifier = Modifier.clickable {
                                        viewModel.addToPlaylist(playlist.id, itemId) { added ->
                                            onDone(if (added) "Zu \"${playlist.name}\" hinzugefuegt" else "War bereits in \"${playlist.name}\"")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.height(16.dp))
                    Text("Neue Playlist:")
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = {
                    viewModel.createAndAdd(newName.trim(), itemId) {
                        onDone("Neue Playlist \"${newName.trim()}\" erstellt und Titel hinzugefuegt")
                    }
                }
            ) { Text("Neu erstellen + hinzufuegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } }
    )
}
