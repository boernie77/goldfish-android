package com.goldfish.android.ui.locallib

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldfish.android.ui.theme.GoldfishOrange
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Player auf Basis von libVLC. Aufgerufen vom LocalPlayerScreen-Auto-
 * Fallback wenn ExoPlayer scheitert.
 *
 * **Maximal defensiv** — wir hatten 1.2.43 native Crashes:
 * - LibVLC ohne jegliche Custom-Args (manche bringen Tablets zum Crash)
 * - HW-Decoding ausgeschaltet (SW ist zuverlaessiger, Performance bei
 *   einzelnen Files OK)
 * - Media via URI (kein FD-Handling — libVLC 3.7 versteht content://)
 * - Jeder Native-Call in try/catch (Throwable, deckt auch Errors ab —
 *   echte SIGSEGV koennen wir nicht fangen, aber wenigstens Java-side
 *   Probleme zeigen wir als Fehlertext statt App-Crash)
 */
@Composable
fun LocalVlcPlayerScreen(
    itemId: Int,
    onBack: () -> Unit,
    viewModel: LocalPlayerViewModel = hiltViewModel()
) {
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler { onBack() }

    var fatalError by remember { mutableStateOf<String?>(null) }

    // LibVLC + MediaPlayer init — schlankester Init, alles try/catch.
    val libVlc: LibVLC? = remember {
        try { LibVLC(context) } catch (t: Throwable) {
            Log.e("GF-VLC", "LibVLC init", t)
            fatalError = "VLC-Engine konnte nicht starten: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }
    val mediaPlayer: MediaPlayer? = remember(libVlc) {
        libVlc?.let {
            try { MediaPlayer(it) } catch (t: Throwable) {
                Log.e("GF-VLC", "MediaPlayer init", t)
                fatalError = "MediaPlayer-Init: ${t.message}"
                null
            }
        }
    }

    DisposableEffect(mediaPlayer, libVlc) {
        val listener = MediaPlayer.EventListener { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) {
                fatalError = "VLC konnte die Datei nicht abspielen."
            }
        }
        try { mediaPlayer?.setEventListener(listener) } catch (_: Throwable) {}
        onDispose {
            try { mediaPlayer?.setEventListener(null) } catch (_: Throwable) {}
            try { mediaPlayer?.stop() } catch (_: Throwable) {}
            try { mediaPlayer?.detachViews() } catch (_: Throwable) {}
            try { mediaPlayer?.release() } catch (_: Throwable) {}
            try { libVlc?.release() } catch (_: Throwable) {}
        }
    }

    // Media setzen sobald das Item geladen ist.
    LaunchedEffect(state.item, mediaPlayer, libVlc) {
        val item = state.item ?: return@LaunchedEffect
        val player = mediaPlayer ?: return@LaunchedEffect
        val vlc = libVlc ?: return@LaunchedEffect
        if (fatalError != null) return@LaunchedEffect
        val uri = try { Uri.parse(item.documentUri) } catch (_: Exception) { return@LaunchedEffect }
        try {
            // SAF-content://-URI in einen nackten int-FD aufloesen und libVLC
            // via fd://-Schema uebergeben. Genau so macht es die offizielle
            // VLC-App. detachFd() gibt Ownership an libVLC — wir DUERFEN den
            // FD selbst nicht mehr schliessen, libVLC kuemmert sich.
            // Vorteil ggu. Media(vlc, uri): umgeht den fragilen ContentResolver-
            // Pfad in libVLC, der bei kaputten Containern SIGSEGV produzierte.
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fdInt = pfd?.detachFd()
            val media = if (fdInt != null && fdInt >= 0) {
                Media(vlc, "fd://$fdInt")
            } else {
                Log.w("GF-VLC", "openFileDescriptor returned null/invalid, falling back to URI")
                Media(vlc, uri)
            }
            try {
                // HW-Decoding bewusst AUS — SW-Decode ist robuster fuer
                // exotische Codecs.
                media.setHWDecoderEnabled(false, false)
                player.media = media
            } finally {
                try { media.release() } catch (_: Throwable) {}
            }
            player.play()
        } catch (t: Throwable) {
            Log.e("GF-VLC", "media load/play failed", t)
            fatalError = "VLC: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (fatalError == null && mediaPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        try {
                            mediaPlayer.attachViews(layout, null, false, false)
                        } catch (t: Throwable) {
                            Log.e("GF-VLC", "attachViews", t)
                            fatalError = "VLC-View-Attach: ${t.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "⚠ Wiedergabe nicht moeglich",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    fatalError ?: "Unbekannter Fehler",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
                ) { Text("Zurück") }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Zurueck",
                tint = Color.White
            )
        }
    }
}
