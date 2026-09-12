package com.goldfish.android.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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

private val SORT_OPTIONS = listOf(
    MUSIC_SORT_ARTIST to "Kuenstler",
    MUSIC_SORT_ALBUM to "Album",
    MUSIC_SORT_YEAR to "Jahr"
)

@Composable
fun SortPickerDialog(
    currentSort: String,
    ascending: Boolean,
    showRecentlyPlayed: Boolean,
    recentlyPlayedFirst: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    onToggleRecentlyPlayed: () -> Unit
) {
    var sort by remember { mutableStateOf(currentSort) }
    var asc by remember { mutableStateOf(ascending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sortierung") },
        text = {
            Column {
                SORT_OPTIONS.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { sort = value }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = sort == value, onClick = { sort = value })
                        Text(label)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { asc = !asc }.padding(vertical = 4.dp)
                ) {
                    RadioButton(selected = asc, onClick = { asc = true })
                    Text("Aufsteigend")
                    RadioButton(selected = !asc, onClick = { asc = false })
                    Text("Absteigend")
                }
                if (showRecentlyPlayed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text("Zuletzt abgespielt zuerst", modifier = Modifier.padding(end = 8.dp))
                        Switch(checked = recentlyPlayedFirst, onCheckedChange = { onToggleRecentlyPlayed() })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sort, asc) }) { Text("Anwenden") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
