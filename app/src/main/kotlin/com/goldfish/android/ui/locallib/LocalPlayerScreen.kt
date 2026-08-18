package com.goldfish.android.ui.locallib

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.goldfish.android.ui.theme.GoldfishOrange

/**
 * Player fuer lokale (SAF-URI) Videos. ExoPlayer rendert direkt aus
 * content:// — kein Server-Stream, kein Transcode. Bei nicht-
 * unterstuetzten Codecs zeigt der ErrorListener eine sinnvolle
 * Meldung mit Codec-Namen.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LocalPlayerScreen(
    itemId: Int,
    onBack: () -> Unit,
    // Bei Zufallswiedergabe: triggert das naechste zufaellige Video aus
    // derselben Library/dem selben Folder. null = kein ⏭-Button anzeigen.
    onNextRandom: (() -> Unit)? = null,
    // Bei Zufallswiedergabe: triggert das vorherige Item aus der
    // Zufalls-History. null = ⏮-Button bleibt grau/inaktiv (z.B. beim
    // allerersten Random-Video, wenn es noch keine History gibt).
    onPreviousRandom: (() -> Unit)? = null,
    // Wenn ExoPlayer scheitert, kann der User auf den internen VLC-
    // Player wechseln (libVLC-Engine mit FFmpeg-Demuxern). null =
    // VLC-Button im Error-State ausblenden.
    onOpenInVlc: (() -> Unit)? = null,
    viewModel: LocalPlayerViewModel = hiltViewModel()
) {
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler { onBack() }

    // Sichtbarkeit der Steuersymbole — Title + Back + ⏭-Random sind daran
    // gekoppelt, blenden zusammen mit den Controls aus.
    var controllerVisible by remember { mutableStateOf(true) }

    // Resume-Dialog-State — analog zum Server-Player. Zeigt sich wenn
    // resumePosSec > 30s und nicht > 95% der Filmlaenge.
    var resumeChoice by remember { mutableStateOf<Double?>(null) }
    var resumeDialogShown by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        // NextRenderersFactory bringt die FFmpeg-Decoder-Extension mit —
        // erlaubt Wiedergabe von Files mit Codecs die das System-MediaCodec
        // nicht kennt (AC-3, DTS, einige HEVC-Profile, etc.).
        // EXTENSION_RENDERER_MODE_PREFER nutzt FFmpeg WENN er kann, sonst
        // faellt er auf den eingebauten Decoder zurueck — kein Performance-
        // Verlust fuer Standard-Inhalte.
        val renderers = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
        // Tolerante MediaSource: ergaenzt die Default-Extractor-Liste um
        // einen Mp4Extractor der KEINEN Sniff macht (= akzeptiert auch
        // .mp4 mit unbekanntem ftyp-Brand, die sonst mit "NoDeclaredBrand"
        // abgelehnt werden). Da der ForcedMp4Extractor erst getriggert
        // wird wenn ALLE Default-Sniffs fehlschlagen, gibt es keine
        // Regression fuer normale Files.
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            context, TolerantExtractorsFactory()
        )
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    // Error-Listener installieren: PlaybackException → state.errorMessage
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // KEIN Auto-Fallback mehr — libVLC crasht bei bestimmten
                // Files nativ (SIGSEGV), wodurch die App abstuerzt. Der
                // User sieht jetzt einen klaren Error-Screen mit drei
                // Optionen: VLC-Engine (Crash-Risiko), externer Player,
                // oder zurueck. Auto-Fallback wieder einbauen sobald
                // libVLC-Crash verstanden ist.
                viewModel.setPlaybackError(formatPlaybackError(error, state.item?.videoCodec))
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            // Resume-Position speichern, dann Player freigeben
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (pos > 5_000 && dur > 0) {
                viewModel.saveResume(pos / 1000.0, dur / 1000.0)
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // MediaItem setzen wenn das Item da ist — Wiedergabe wartet auf
    // Resume-Dialog-Antwort wenn ein sinnvoller Resume-Stand vorliegt.
    LaunchedEffect(state.item) {
        val item = state.item ?: return@LaunchedEffect
        val uri = Uri.parse(item.documentUri)
        val displayTitle = item.title?.takeIf { it.isNotBlank() }
            ?: item.parsedTitle.takeIf { it.isNotBlank() }
            ?: item.fileName.substringBeforeLast('.', item.fileName)
        val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(displayTitle)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(mediaMetadata)
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        // Resume-Logik (analog Server-Player):
        // > 30s gespeichert UND nicht > 95% Filmlaenge → Dialog zeigen
        // sonst: kein Dialog, automatisch von Anfang spielen.
        val resumeSec = item.resumePosSec
        val dur = item.durationSec
        val shouldShowDialog = resumeSec > 30 &&
            (dur <= 0 || resumeSec / dur < 0.95) &&
            !resumeDialogShown
        if (shouldShowDialog) {
            resumeDialogShown = true
            exoPlayer.playWhenReady = false  // pause bis User entschieden hat
        } else {
            exoPlayer.playWhenReady = true
        }
    }

    // Effective Resume-Choice: wenn der User „Fortsetzen" wählt, seek + play.
    LaunchedEffect(resumeChoice) {
        val target = resumeChoice ?: return@LaunchedEffect
        exoPlayer.seekTo((target * 1000).toLong())
        exoPlayer.playWhenReady = true
        resumeChoice = null
    }

    val resumeItem = state.item
    if (resumeDialogShown && resumeItem != null && resumeChoice == null && !exoPlayer.playWhenReady) {
        val resumeSec = resumeItem.resumePosSec
        ResumeDialog(
            resumeSec = resumeSec,
            onFromStart = {
                viewModel.saveResume(0.0, resumeItem.durationSec)
                resumeChoice = 0.0
            },
            onResume = { resumeChoice = resumeSec }
        )
    }

    // Wenn Zufallswiedergabe aktiv ist: ExoPlayer mit einem ForwardingPlayer
    // wrappen, der die eingebauten Skip-Buttons im Media3-Controller
    // aktiviert und auf die Random-Callbacks umleitet. Sonst zeigt der
    // Player die nativen Buttons grau/deaktiviert (weil keine Playlist).
    val playerForView: Player = remember(exoPlayer, onNextRandom, onPreviousRandom) {
        if (onNextRandom != null) {
            randomNextPlayer(exoPlayer, onNext = onNextRandom, onPrevious = onPreviousRandom)
        } else exoPlayer
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (state.errorMessage == null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playerForView
                        useController = true
                        setShowNextButton(true)
                        // Previous-Button immer einblenden — die Aktivierung
                        // steuert der ForwardingPlayer per Commands. So sieht
                        // der User den ⏮-Button konsistent neben dem ⏭.
                        setShowPreviousButton(true)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controllerVisible = visibility == View.VISIBLE
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Fehleranzeige mit klarer Codec-/Grund-Info + Zurueck-Button
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠ Wiedergabe nicht moeglich",
                    style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.errorMessage ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                // Fallback: wenn unser Player + FFmpeg-Extension nicht
                // koennen, an einen externen Player (VLC, MX, …) per
                // ACTION_VIEW-Intent uebergeben. Funktioniert mit SAF-URIs
                // weil wir vorher die persistierte Read-Berechtigung haben.
                // Interner VLC-Player als optionaler Versuch. Crash-Risiko
                // bei manchen Files (libVLC SIGSEGV bei kaputten Containern),
                // daher kein Auto-Fallback mehr — nur manuell per Klick.
                if (onOpenInVlc != null) {
                    Button(
                        onClick = onOpenInVlc,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF666666))
                    ) { Text("⚠ Mit VLC-Engine versuchen (kann crashen)") }
                    Spacer(Modifier.height(8.dp))
                }
                state.item?.documentUri?.let { uriStr ->
                    val fname = state.item?.fileName.orEmpty()
                    // MIME exakt aus der Datei-Endung ableiten — VLC und MX
                    // sind in einigen Versionen strikt und ignorieren
                    // "video/*" stillschweigend (Chooser-Klick "macht nichts").
                    val mime = remember(fname) {
                        val ext = fname.substringAfterLast('.', "").lowercase()
                        android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext) ?: "video/*"
                    }
                    val pm = context.packageManager
                    val uri = Uri.parse(uriStr)
                    // Pruefen ob VLC installiert ist — wir bieten dann einen
                    // dedizierten "Mit VLC oeffnen"-Button mit explizitem
                    // setPackage + direktem Permission-Grant. Sicherer Pfad
                    // als ueber den Android-Chooser, wo VLC manche Grants
                    // verliert.
                    val vlcInstalled = remember {
                        try {
                            pm.getPackageInfo("org.videolan.vlc", 0); true
                        } catch (_: Exception) { false }
                    }

                    fun openWithPackage(pkg: String?) {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                clipData = android.content.ClipData.newRawUri(fname, uri)
                                if (pkg != null) setPackage(pkg)
                            }
                            if (pkg != null) {
                                // Permission explizit vor Activity-Start an
                                // den Ziel-Package ausstellen. Ohne das
                                // bekommt VLC trotz FLAG_GRANT manchmal
                                // SecurityException beim Open.
                                context.grantUriPermission(pkg, uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(intent)
                            } else {
                                val matches = pm.queryIntentActivities(intent, 0)
                                for (ri in matches) {
                                    context.grantUriPermission(ri.activityInfo.packageName,
                                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent,
                                    "Mit anderem Player oeffnen").apply {
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context,
                                "Konnte Player nicht oeffnen: ${e.message}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }

                    if (vlcInstalled) {
                        Button(
                            onClick = { openWithPackage("org.videolan.vlc") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8800))
                        ) { Text("Mit VLC oeffnen") }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { openWithPackage(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
                    ) { Text(if (vlcInstalled) "Mit anderem Player oeffnen" else "In anderem Player oeffnen") }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) { Text("Zurück") }
            }
        }

        // Top-Bar: Back + Datei-Titel + (optional) Shuffle-Next. Sichtbar
        // synchron zu den Steuersymbolen — blendet beim "User inactive" weg.
        val displayTitle = state.item?.let {
            it.title?.takeIf { t -> t.isNotBlank() }
                ?: it.parsedTitle.takeIf { t -> t.isNotBlank() }
                ?: it.fileName.substringBeforeLast('.', it.fileName)
        }.orEmpty()
        AnimatedVisibility(
            visible = controllerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp).statusBarsPadding()
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurueck", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Kein zusaetzlicher Top-Right-Button mehr — der native
                // ⏭-Button des Media3-Controllers triggert das naechste
                // Zufallsvideo via RandomNextPlayer-ForwardingPlayer.
            }
        }
    }
}

/** ForwardingPlayer-Wrapper, der die eingebauten Skip-Buttons des Media3
 *  PlayerView-Controllers aktiviert und ihre Klicks auf `onNext`/
 *  `onPrevious` umleitet. Ohne das sind die Buttons grau, weil der
 *  zugrundeliegende ExoPlayer eine 1-Item-Playlist hat (`hasNextMediaItem`/
 *  `hasPreviousMediaItem` = false). `onPrevious=null` haelt den ⏮-Button
 *  weiter deaktiviert (z.B. beim allerersten Random-Video ohne History). */
@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun randomNextPlayer(
    base: Player,
    onNext: () -> Unit,
    onPrevious: (() -> Unit)? = null
): Player {
    return object : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            if (onPrevious != null) {
                b.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                b.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious != null
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = onPrevious != null

        override fun seekToNext() { onNext() }
        override fun seekToNextMediaItem() { onNext() }
        override fun seekToPrevious() { onPrevious?.invoke() }
        override fun seekToPreviousMediaItem() { onPrevious?.invoke() }
    }
}

/** Mappt eine PlaybackException auf eine User-freundliche Meldung. Wenn der
 *  Decoder einen unbekannten Codec meldet, zeigen wir den probed Codec-Namen
 *  aus der DB an — der User weiß dann woran's lag. */
private fun formatPlaybackError(error: PlaybackException, knownCodec: String?): String {
    val codeName = error.errorCodeName
    val cause = error.message ?: error.cause?.message
    val deepestCause = generateSequence(error.cause) { it.cause }
        .lastOrNull()?.message?.takeIf { !it.isNullOrBlank() && it != cause }
    val codecHint = knownCodec?.takeIf { it.isNotBlank() }?.let { " (Codec: $it)" } ?: ""
    val base = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Codec wird nicht unterstuetzt$codecHint."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "Decoder konnte nicht initialisiert werden$codecHint."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Datei nicht gefunden. Wurde der Ordner umbenannt oder geloescht?"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "Keine Berechtigung fuer diese Datei. SAF-Permission verloren?"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "Container/Format wird nicht unterstuetzt$codecHint."
        else -> "Fehler [$codeName]$codecHint"
    }
    // Detailzeile mit dem konkreten ExoPlayer-Cause anhaengen — hilft beim
    // Debug, was genau scheiterte (z.B. "MediaCodecVideoRenderer error,
    // index=0, format=Format(..., codecs=hev1.2.4.L153.B0)").
    val details = listOfNotNull(cause, deepestCause).distinct().joinToString("\n\n")
    return if (details.isNotBlank()) "$base\n\n$details" else base
}

@Composable
private fun ResumeDialog(
    resumeSec: Double,
    onFromStart: () -> Unit,
    onResume: () -> Unit
) {
    val mins = (resumeSec / 60).toInt()
    val secs = (resumeSec % 60).toInt()
    val timeStr = if (mins >= 60) {
        val h = mins / 60
        val m = mins % 60
        "%d:%02d:%02d h".format(h, m, secs)
    } else {
        "%d:%02d min".format(mins, secs)
    }
    AlertDialog(
        onDismissRequest = onResume,  // Click outside = fortsetzen (Default-Wunsch)
        title = { Text("Wiedergabe fortsetzen?") },
        text = {
            Text("Du hast bei $timeStr aufgehoert. Fortsetzen oder von Anfang an?")
        },
        confirmButton = {
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = GoldfishOrange)
            ) { Text("Fortsetzen ab $timeStr") }
        },
        dismissButton = {
            TextButton(onClick = onFromStart) { Text("Von Anfang") }
        }
    )
}
