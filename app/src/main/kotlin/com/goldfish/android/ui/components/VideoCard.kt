package com.goldfish.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.goldfish.android.data.model.Item
import com.goldfish.android.ui.theme.GoldfishOrange

private fun resLabel(width: Int, height: Int): String {
    val effectiveHeight = maxOf(height, (width * 9.0 / 16.0).toInt())
    return when {
        effectiveHeight >= 2160 -> "4K"
        effectiveHeight >= 1440 -> "2K"
        effectiveHeight >= 1080 -> "1080p"
        effectiveHeight >= 720  -> "720p"
        effectiveHeight >= 576  -> "576p"
        effectiveHeight >= 480  -> "480p"
        effectiveHeight > 0     -> "360p"
        else -> ""
    }
}

private fun fmtDuration(seconds: Double): String {
    if (seconds <= 0) return ""
    val totalMins = (seconds / 60).toInt()
    val hours = totalMins / 60
    val mins = totalMins % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    item: Item,
    imageUrl: String?,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    isPoster: Boolean = true,
    cardWidth: Dp = 130.dp,
    onFavoriteToggle: ((Item) -> Unit)? = null,
    onWatchedToggle: ((Item) -> Unit)? = null,
    // Wird bei Long-Press auf einem heruntergeladenen Item gerufen — UI zeigt
    // dann einen Confirm-Dialog. null = kein Long-Press-Handling.
    onDeleteDownload: ((Item) -> Unit)? = null,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    // libraryKind == "private" + channelLabelOnTop=true: Top-Zeile zeigt den
    // Kanal/Top-Folder (relPath[0]), Titel rutscht in eine zweite Zeile
    // darunter mit etwas dickerer Schrift. Pendant zum Browser-cards.js
    // fuer YouTube/Urlaubsvideos-Libs. channelLabelOnTop ist die per-Library-
    // Einstellung aus dem Browser-Library-Manager (Default true).
    libraryKind: String? = null,
    channelLabelOnTop: Boolean = true,
    onClick: () -> Unit,
    // Wird im selectionMode=true aufgerufen statt onClick. Lass leer, wenn die
    // Karte nicht selektierbar sein soll (Detail, Home-Strip, etc.) — dann
    // bleibt onClick auch im Selection-Modus aktiv.
    onSelectionToggle: () -> Unit = onClick
) {
    val cardHeight = if (isPoster) (cardWidth * 1.5f) else (cardWidth * 9f / 16f)
    val posterAlpha = if (item.watched) 0.65f else 1.0f

    val resText = resLabel(item.width, item.height)
    val durText = fmtDuration(item.durationSec)
    val variantCount = 0 // populated from outside if needed

    Column(
        modifier = Modifier
            .width(cardWidth)
            // Klick-Entscheidung INNERHALB des Modifier-Closures: Compose liest
            // selectionMode beim Klick aus dem aktuellen Composition-Scope —
            // wenn der State im Aussenkontext liegt, wuerde eine Aussen-Lambda
            // ggf. einen stale Wert capturen (Recomposition-Skip). Hier ist
            // selectionMode ein direkter Composable-Param und immer aktuell.
            // Long-Press auf heruntergeladenen Items → Delete-Hook.
            .combinedClickable(
                onClick = { if (selectionMode) onSelectionToggle() else onClick() },
                onLongClick = {
                    if (isDownloaded && onDeleteDownload != null) onDeleteDownload(item)
                }
            )
    ) {
        // --- Poster box ---
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (isSelected) Modifier.border(2.dp, GoldfishOrange, RoundedCornerShape(6.dp))
                    else Modifier
                )
        ) {
            // Image
            AsyncImage(
                model = imageUrl,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(posterAlpha)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // Bottom gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f)
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xBB000000))
                        )
                    )
            )

            // Resume progress bar
            item.resumePosSec?.let { resumeSec ->
                if (resumeSec > 0 && item.durationSec > 0) {
                    val progress = (resumeSec / item.durationSec).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomStart),
                        color = GoldfishOrange,
                        trackColor = Color(0x44FFFFFF)
                    )
                }
            }

            // TOP LEFT: Watched toggle
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0x88000000))
                    .clickable(enabled = onWatchedToggle != null) {
                        onWatchedToggle?.invoke(item)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = if (item.watched) "Gesehen" else "Ungesehen",
                    tint = if (item.watched) Color(0xFF4CAF50) else Color(0x88FFFFFF),
                    modifier = Modifier.size(16.dp)
                )
            }

            // TOP RIGHT: Rating pill
            item.metadata?.rating?.let { rating ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "★ ${"%.1f".format(rating)}",
                        color = GoldfishOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // TOP RIGHT (under rating): Favorite button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 26.dp, end = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable(enabled = onFavoriteToggle != null) {
                        onFavoriteToggle?.invoke(item)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Favorit",
                    tint = if (item.favorite) Color(0xFFE91E63) else Color(0x88FFFFFF),
                    modifier = Modifier.size(13.dp)
                )
            }

            // BOTTOM LEFT: Resolution badge
            if (resText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 5.dp, bottom = if (item.resumePosSec != null && item.resumePosSec > 0) 7.dp else 4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = resText,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // BOTTOM RIGHT: Duration
            if (durText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 5.dp, bottom = if (item.resumePosSec != null && item.resumePosSec > 0) 7.dp else 4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xBB000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = durText,
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            // Offline-/Download-Indikator: oben links UNTER dem Watched-Toggle.
            // Während Download → orangener Progress-Ring; nach Abschluss → grünes Cloud-Done.
            if (isDownloading || isDownloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 5.dp, top = 32.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDownloading) {
                        if (downloadProgress > 0f && downloadProgress.isFinite()) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                color = GoldfishOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                color = GoldfishOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Offline verfügbar",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Selection checkbox overlay
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isSelected) Color(0x44F97316) else Color.Transparent
                        )
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Ausgewählt",
                        tint = GoldfishOrange,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                    )
                }
            }
        }

        // --- Below poster: title + year/episode ---
        Spacer(modifier = Modifier.height(4.dp))

        // Bei Private-Libs (YouTube etc.) UND wenn channelLabelOnTop=true:
        // top = Kanal/Top-Folder aus relPath, Titel kommt darunter dicker.
        // Sonst klassisches Layout (Titel oben).
        val isPrivate = libraryKind == "private" && channelLabelOnTop
        val channelName: String? = if (isPrivate) {
            item.relPath?.split('/')?.firstOrNull { it.isNotBlank() }?.takeIf { it.isNotBlank() }
        } else null
        val topText = channelName ?: item.displayTitle

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = topText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Confirmed tick
            if (item.metadataConfirmed) {
                Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }
        }

        // Sub-Text-Logik: TV-Episode > Filme-Jahr > Veröffentlichungsdatum (YouTube etc.)
        val meta = item.metadata
        val subText = when {
            meta?.season != null && meta.episode != null ->
                "S${meta.season.toString().padStart(2,'0')}E${meta.episode.toString().padStart(2,'0')}"
            meta?.year != null && meta.year > 0 -> "${meta.year}"
            !item.releasedAt.isNullOrBlank() -> formatReleasedAt(item.releasedAt)
            else -> null
        }
        subText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        // Bei Private-Libs: Titel als dritte Zeile, etwas dicker als der subText.
        if (isPrivate && channelName != null) {
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Formatiert ein ISO-8601-Datum (YYYY-MM-DDTHH:MM:SSZ) zu "DD.MM.YYYY". */
private fun formatReleasedAt(iso: String): String? {
    if (iso.length < 10) return null
    val datePart = iso.substring(0, 10)
    val parts = datePart.split("-")
    if (parts.size != 3) return null
    val (yyyy, mm, dd) = parts
    if (yyyy.length != 4 || yyyy == "0001") return null
    return "$dd.$mm.$yyyy"
}

@Composable
fun PlayButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        containerColor = GoldfishOrange,
        contentColor = Color.Black
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Abspielen",
            modifier = Modifier.size(28.dp)
        )
    }
}
