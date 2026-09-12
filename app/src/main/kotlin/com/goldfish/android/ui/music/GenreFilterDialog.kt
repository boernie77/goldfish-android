package com.goldfish.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Genre-Auswahl — geteilt zwischen Album-Grid und "Alle Titel" (gleiches
 *  Muster wie der Browser-Genre-Picker: Multi-Select-Checkliste). */
@Composable
fun GenreFilterDialog(
    genres: List<String>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var localSelected by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Genre-Filter") },
        text = {
            if (genres.isEmpty()) {
                Text("Keine Genres in dieser Bibliothek.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(genres) { genre ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    localSelected = if (genre in localSelected) localSelected - genre else localSelected + genre
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = genre in localSelected,
                                onCheckedChange = {
                                    localSelected = if (it) localSelected + genre else localSelected - genre
                                }
                            )
                            Text(genre)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(localSelected) }) { Text("Anwenden") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(emptySet()) }) { Text("Zuruecksetzen") }
        }
    )
}
