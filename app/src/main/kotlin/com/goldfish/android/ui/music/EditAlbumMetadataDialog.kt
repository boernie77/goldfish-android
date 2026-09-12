package com.goldfish.android.ui.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goldfish.android.data.model.MusicAlbum

/** Admin-only, bearbeitet ALLE Tracks des Albums auf einmal (PUT
 *  /api/albums/{id}/metadata) — separat von EditTrackMetadataDialog. */
@Composable
fun EditAlbumMetadataDialog(
    album: MusicAlbum,
    onDismiss: () -> Unit,
    onSave: (artist: String?, album: String?, genre: String?, year: Int?) -> Unit
) {
    var artist by remember { mutableStateOf(album.artist) }
    var albumName by remember { mutableStateOf(album.album) }
    var genre by remember { mutableStateOf(album.genre.orEmpty()) }
    var year by remember { mutableStateOf(album.year?.takeIf { it > 0 }?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Album bearbeiten") },
        text = {
            Column {
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Kuenstler") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = albumName, onValueChange = { albumName = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it.filter(Char::isDigit) },
                    label = { Text("Jahr") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(artist.ifBlank { null }, albumName.ifBlank { null }, genre.ifBlank { null }, year.toIntOrNull())
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
