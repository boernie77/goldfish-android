package com.goldfish.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goldfish.android.data.model.Item

private fun fmtTrackDuration(seconds: Double): String {
    if (seconds <= 0) return ""
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

/** Der EINE flache Track-Zeilen-Renderer, wiederverwendet fuer: "Alle Titel",
 *  Suchtreffer, Album-Detail-Tracklist, Musik-Playlist-Inhalt — Pendant zum
 *  Browser renderMusicTrackRow. Zeigt Track-Nr. (falls im Kontext relevant),
 *  Titel/Artist/Album, Dauer, Favoriten-Herz. */
@Composable
fun MusicTrackRow(
    item: Item,
    showAlbum: Boolean = true,
    showTrackNo: Boolean = false,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    onFavoriteToggle: ((Item) -> Unit)? = null,
    onAddToPlaylist: ((Item) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showTrackNo) {
            Text(
                item.trackNo?.toString().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayTitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(
                item.artist?.takeIf { it.isNotBlank() },
                item.album?.takeIf { it.isNotBlank() && showAlbum }
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            fmtTrackDuration(item.durationSec),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (onAddToPlaylist != null) {
            IconButton(onClick = { onAddToPlaylist(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.PlaylistAdd,
                    contentDescription = "Zu Playlist hinzufuegen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onFavoriteToggle != null) {
            IconButton(onClick = { onFavoriteToggle(item) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (item.favorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
