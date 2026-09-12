package com.goldfish.android.ui.music

import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.repository.ItemRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Haelt EINEN ExoPlayer + EINE MediaSession fuer Musik-Hintergrundwiedergabe —
 * ueberlebt Navigation/Backgrounding, liefert Lockscreen-/Benachrichtigungs-
 * Controls "for free" ueber Media3s Standardmuster (kein eigener Notification-
 * Code noetig, MediaSessionService baut die Notification aus der MediaItem-
 * Metadata). Der Rest der App spricht NIE direkt mit dem ExoPlayer hier,
 * sondern ueber `MusicPlayerController` (MediaController-Verbindung), siehe
 * dort.
 *
 * Analog zum bestehenden Video-Player (`PlayerScreen.GoldfishPlayer`) wird die
 * Server-Auth ueber denselben `ApiClientProvider.okHttpClient` (Cookie-Jar)
 * durchgereicht — kein separates Token/Cookie-Handling noetig.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaSessionService() {

    @Inject lateinit var apiClientProvider: ApiClientProvider
    @Inject lateinit var itemRepository: ItemRepository

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Letztes Item, fuer das ein "start"-Log-Zustand aktiv ist (fuer den
    // parity-Server-Call POST /api/playback/{id}/start|stop, siehe Listener
    // unten) — getrennt vom aktuell laufenden MediaItem, weil ein Pause
    // keinen Track-Wechsel bedeutet.
    private var activeLoggedItemId: Int? = null

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = OkHttpDataSource.Factory(apiClientProvider.okHttpClient)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
            .apply { playWhenReady = true }

        player.addListener(PlaybackParityListener())

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /** Server-Parity zum Video-Player: Resume-Position/"Gesehen" (90%-Schwelle,
     *  identisch zu PlayerViewModel.saveResumePosition) + die neuen
     *  Activity-Log-Endpoints /playback/{id}/start|stop — bisher NIRGENDS in
     *  der App aufgerufen, hier bewusst NUR fuer Musik ergaenzt (additiv,
     *  kein Risiko fuer den bestehenden Video-Pfad). */
    private inner class PlaybackParityListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val previousId = activeLoggedItemId
            if (previousId != null) reportStop(previousId, "ended")
            activeLoggedItemId = mediaItem?.mediaId?.toIntOrNull()
            activeLoggedItemId?.let { reportStart(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = mediaSession?.player ?: return
            val itemId = player.currentMediaItem?.mediaId?.toIntOrNull() ?: return
            val positionMs = player.currentPosition
            if (!isPlaying && positionMs > 5_000) {
                saveResumeOrWatched(itemId, positionMs, player.duration)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                activeLoggedItemId?.let { reportStop(it, "ended") }
                activeLoggedItemId = null
            }
        }
    }

    private fun saveResumeOrWatched(itemId: Int, positionMs: Long, durationMs: Long) {
        val shouldMarkWatched = durationMs > 0 && positionMs.toDouble() / durationMs > 0.9
        serviceScope.launch {
            if (shouldMarkWatched) itemRepository.setWatched(itemId, true)
            else itemRepository.setResume(itemId, positionMs / 1000.0)
        }
    }

    private fun reportStart(itemId: Int) {
        serviceScope.launch { itemRepository.reportPlaybackStart(itemId) }
    }

    private fun reportStop(itemId: Int, reason: String) {
        val player = mediaSession?.player
        val positionSec = ((player?.currentPosition ?: 0L) / 1000.0)
        val durationSec = ((player?.duration ?: 0L).coerceAtLeast(0L) / 1000.0)
        serviceScope.launch { itemRepository.reportPlaybackStop(itemId, reason, positionSec, durationSec) }
    }
}
