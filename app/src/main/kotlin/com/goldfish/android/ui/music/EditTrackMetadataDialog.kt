package com.goldfish.android.ui.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import com.goldfish.android.data.model.Item

/** Admin-only, bearbeitet EINEN Track direkt (PUT /api/items/{id}/music-metadata)
 *  — bewusst NICHT der TMDB-orientierte "metadata-manual"-Pfad fuer Filme/Serien. */
@Composable
fun EditTrackMetadataDialog(
    item: Item,
    onDismiss: () -> Unit,
    onSave: (title: String?, artist: String?, album: String?, trackNo: Int?, genre: String?) -> Unit
) {
    var title by remember { mutableStateOf(item.title) }
    var artist by remember { mutableStateOf(item.artist.orEmpty()) }
    var album by remember { mutableStateOf(item.album.orEmpty()) }
    var trackNo by remember { mutableStateOf(item.trackNo?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(item.genre.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Titel bearbeiten") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Kuenstler") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = trackNo,
                    onValueChange = { trackNo = it.filter(Char::isDigit) },
                    label = { Text("Track-Nr.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title.ifBlank { null }, artist.ifBlank { null }, album.ifBlank { null }, trackNo.toIntOrNull(), genre.ifBlank { null })
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
