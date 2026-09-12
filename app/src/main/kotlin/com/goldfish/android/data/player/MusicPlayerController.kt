package com.goldfish.android.data.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import com.goldfish.android.ui.music.MusicPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** UI-Zustand des Musik-Players — einzige Quelle der Wahrheit fuer alle
 *  Musik-ViewModels (Mini-Player, Now-Playing, Album-Detail-Play-Button). */
data class MusicPlayerUiState(
    val currentItem: Item? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Item> = emptyList(),
    val queueIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    // Player.REPEAT_MODE_OFF (0) | REPEAT_MODE_ONE (1) | REPEAT_MODE_ALL (2)
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

/**
 * Haelt die client-seitige `MediaController`-Verbindung zum
 * `MusicPlaybackService` — der einzige Ort in der App, der Transport-Befehle
 * fuer Musik ausloest. ViewModels injecten dieses Singleton und lesen
 * [uiState]; niemand ausser [MusicPlaybackService] haelt einen eigenen
 * ExoPlayer fuer Musik.
 */
@Singleton
class MusicPlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val settingsDataStore: SettingsDataStore
) {
    private var controller: MediaController? = null
    private var connecting = false

    private val _uiState = MutableStateFlow(MusicPlayerUiState())
    val uiState: StateFlow<MusicPlayerUiState> = _uiState

    private var currentQueue: List<Item> = emptyList()

    private suspend fun ensureConnected(): MediaController? {
        controller?.let { return it }
        if (connecting) {
            // Sehr einfache Wartestrategie: kurz pollen bis die einzige
            // parallele connect()-Anfrage fertig ist. Transport-Aufrufe sind
            // ohnehin User-Klicks, kein Hot-Path.
            var waited = 0
            while (connecting && waited < 5000) {
                kotlinx.coroutines.delay(50)
                waited += 50
            }
            return controller
        }
        connecting = true
        return try {
            val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val result = suspendCoroutine<MediaController?> { cont ->
                future.addListener({
                    val c = try { future.get() } catch (_: Exception) { null }
                    cont.resume(c)
                }, MoreExecutors.directExecutor())
            }
            result?.let { registerListener(it) }
            controller = result
            result
        } finally {
            connecting = false
        }
    }

    private fun registerListener(mc: MediaController) {
        mc.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying, positionMs = mc.currentPosition)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val itemId = mediaItem?.mediaId?.toIntOrNull()
                val item = currentQueue.find { it.id == itemId } ?: _uiState.value.currentItem
                val idx = currentQueue.indexOfFirst { it.id == itemId }.takeIf { it >= 0 } ?: _uiState.value.queueIndex
                _uiState.value = _uiState.value.copy(
                    currentItem = item,
                    queueIndex = idx,
                    positionMs = 0L,
                    durationMs = mc.duration.coerceAtLeast(0L)
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(durationMs = mc.duration.coerceAtLeast(0L))
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _uiState.value = _uiState.value.copy(shuffleEnabled = shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _uiState.value = _uiState.value.copy(repeatMode = repeatMode)
            }
        })
    }

    /** Muss regelmaessig (z.B. 1x/Sekunde von einem UI-Timer) aufgerufen
     *  werden, um `positionMs` waehrend der Wiedergabe live zu halten —
     *  Media3 feuert keinen periodischen Positions-Callback von selbst. */
    fun pollPosition() {
        controller?.let { mc ->
            if (mc.isPlaying) _uiState.value = _uiState.value.copy(positionMs = mc.currentPosition)
        }
    }

    private suspend fun mediaItemFor(item: Item): MediaItem? {
        val playback = itemRepository.getPlayback(item.id)
        val url = (playback as? Result.Success)?.data?.url ?: return null
        val baseUrl = try { settingsDataStore.settings.first().serverUrl.trimEnd('/') } catch (_: Exception) { "" }
        // Server liefert playbackInfo.url immer relativ — identische Konvention
        // wie PlayerViewModel.buildPlaybackUrl() fuer den Video-Player.
        val fullUrl = "$baseUrl/${url.trimStart('/')}"
        val artworkUrl = item.musicAlbumId?.let { itemRepository.getAlbumCoverUrl(it, baseUrl) }
        val metadata = MediaMetadata.Builder()
            .setTitle(item.displayTitle)
            .setArtist(item.artist)
            .setAlbumTitle(item.album)
            .apply { artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(fullUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    suspend fun playQueue(items: List<Item>, startIndex: Int = 0, shuffle: Boolean = false) {
        val mc = ensureConnected() ?: return
        val mediaItems = items.mapNotNull { mediaItemFor(it) }
        if (mediaItems.isEmpty()) return
        currentQueue = items
        _uiState.value = _uiState.value.copy(queue = items, shuffleEnabled = shuffle)
        mc.shuffleModeEnabled = shuffle
        mc.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.size - 1), 0L)
        mc.prepare()
        mc.play()
    }

    suspend fun playAlbum(tracks: List<Item>, startIndex: Int = 0) = playQueue(tracks, startIndex, shuffle = false)

    /** Zufalls-Wiedergabe der ganzen Bibliothek — schliesst Hoerbuecher (.m4b)
     *  aus, wie das Browser/iOS-Pendant (shufflePlayLibrary). */
    suspend fun shufflePlayLibrary(libraryId: Int) {
        val items = (itemRepository.getItems(libraryId = libraryId) as? Result.Success)?.data ?: return
        val playable = items.filter { it.container?.lowercase() != "m4b" }
        if (playable.isEmpty()) return
        playQueue(playable.shuffled(), 0, shuffle = true)
    }

    suspend fun togglePlayPause() {
        val mc = ensureConnected() ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    suspend fun seekTo(posMs: Long) {
        ensureConnected()?.seekTo(posMs)
    }

    suspend fun skipNext() {
        ensureConnected()?.seekToNextMediaItem()
    }

    suspend fun skipPrevious() {
        ensureConnected()?.seekToPreviousMediaItem()
    }

    suspend fun toggleShuffle() {
        val mc = ensureConnected() ?: return
        mc.shuffleModeEnabled = !mc.shuffleModeEnabled
    }

    /** Zyklus AUS → ALLE → EINZELN → AUS, wie bei den meisten Musik-Playern. */
    suspend fun toggleRepeatMode() {
        val mc = ensureConnected() ?: return
        mc.repeatMode = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Springt direkt zu einem Titel in der aktuellen Warteschlange (Tap auf
     *  einen Eintrag in der Queue-Ansicht des Now-Playing-Screens). */
    suspend fun skipToQueueIndex(index: Int) {
        val mc = ensureConnected() ?: return
        if (index !in 0 until mc.mediaItemCount) return
        mc.seekTo(index, 0L)
        mc.play()
    }
}
