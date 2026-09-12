package com.goldfish.android.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/** Voller Player-Screen — liest MusicPlayerController.uiState (ueber ein
 *  duennes ViewModel-Wrapper, damit hiltViewModel() greift), loest
 *  Transport-Befehle direkt am Controller aus. Zeigt zusaetzlich die
 *  komplette Warteschlange (Tap springt direkt zu diesem Titel). */
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel()
) {
    val ui by viewModel.playerController.uiState.collectAsStateWithLifecycle()

    // Positions-Polling — Media3 feuert keinen periodischen Zeit-Callback.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.playerController.pollPosition()
            delay(500)
        }
    }

    Scaffold { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Schliessen")
                    }
                    Spacer(Modifier.height(24.dp))
                    val item = ui.currentItem
                    AsyncImage(
                        model = item?.musicAlbumId?.let { "${viewModel.baseUrl.trimEnd('/')}/api/poster/album/$it" },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(item?.displayTitle ?: "Kein Titel", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(item?.artist, item?.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    val duration = ui.durationMs.coerceAtLeast(1L)
                    Slider(
                        value = (ui.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { fraction -> viewModel.seekTo((fraction * duration).toLong()) }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatMs(ui.positionMs), style = MaterialTheme.typography.labelSmall)
                        Text(formatMs(ui.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (ui.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.skipPrevious() }) { Icon(Icons.Filled.SkipPrevious, "Zurueck") }
                        FilledIconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                            Icon(
                                if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.skipNext() }) { Icon(Icons.Filled.SkipNext, "Weiter") }
                        IconButton(onClick = { viewModel.toggleRepeatMode() }) {
                            Icon(
                                if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "Wiederholen",
                                tint = if (ui.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (ui.queue.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "Warteschlange",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            itemsIndexed(ui.queue) { index, track ->
                val isCurrent = index == ui.queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.skipToQueueIndex(index) }
                        .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        track.artist?.takeIf { it.isNotBlank() }?.let {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (isCurrent) {
                        Icon(
                            if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
