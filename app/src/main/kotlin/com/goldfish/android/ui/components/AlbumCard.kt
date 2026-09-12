package com.goldfish.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.goldfish.android.data.model.MusicAlbum

/** Album-Kachel (quadratisches Cover) — Musik-Pendant zu VideoCard, aber
 *  bewusst KEINE VideoCard-Wiederverwendung: andere Metadaten (Artist/Jahr/
 *  Genre/Trackzahl statt Aufloesung/Laufzeit), kein Gesehen-Haken. */
@Composable
fun AlbumCard(
    album: MusicAlbum,
    coverUrl: String?,
    cardWidth: Dp = 150.dp,
    onClick: () -> Unit,
    onFavoriteToggle: ((MusicAlbum) -> Unit)? = null
) {
    Column(modifier = Modifier.width(cardWidth).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(cardWidth)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (onFavoriteToggle != null) {
                IconButton(
                    onClick = { onFavoriteToggle(album) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (album.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (album.favorite == true) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            album.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = listOfNotNull(
            album.artist.takeIf { it.isNotBlank() },
            album.year?.takeIf { it > 0 }?.toString()
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
