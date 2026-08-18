package com.goldfish.android.ui.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.TimeBar
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.goldfish.android.data.trickplay.TrickplayFrame
import com.goldfish.android.data.trickplay.TrickplayParser
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.ui.theme.GoldfishOrange
import dagger.hilt.android.EntryPointAccessors

@Composable
fun PlayerScreen(
    itemId: Int,
    onBack: () -> Unit,
    onNextRandom: (() -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showQualityMenu by remember { mutableStateOf(false) }
    var controllerVisible by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Nach erfolgreichem Löschen zurueck navigieren.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    // Trickplay-State
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    // (left, top, width) der TimeBar im PlayerView-Koordinatensystem
    var timeBarBounds by remember { mutableStateOf(Triple(0, 0, 0)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GoldfishOrange)
                }
            }
            state.errorMessage != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Fehler: ${state.errorMessage}", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text("Zurück") }
                }
            }
            (state.playbackInfo != null || state.localFilePath != null) -> {
                val playbackUrl = viewModel.buildPlaybackUrl()
                val isHls = viewModel.isHls()
                val resumeMs = state.resumePositionMs

                val totalDurationMs = (
                    (state.item?.durationSec?.takeIf { it > 0 }
                        ?: state.playbackInfo?.item?.durationSec?.takeIf { it > 0 }
                        ?: 0.0) * 1000.0
                ).toLong()

                GoldfishPlayer(
                    context = context,
                    playbackUrl = playbackUrl,
                    isHls = isHls,
                    resumePositionMs = resumeMs,
                    totalDurationMs = totalDurationMs,
                    subtitleOptions = state.subtitleOptions,
                    selectedSubtitleKey = state.selectedSubtitleKey,
                    onPositionChanged = viewModel::saveResumePosition,
                    onNextRandom = onNextRandom,
                    onSettingsClick = { showQualityMenu = true },
                    onControllerVisibilityChanged = { controllerVisible = it },
                    onScrubPositionChanged = { scrubPositionMs = it },
                    onTimeBarBoundsChanged = { l, t, w -> timeBarBounds = Triple(l, t, w) },
                    modifier = Modifier.fillMaxSize()
                )

                // Trickplay-Vorschau über dem Scrub-Daumen
                val scrubMs = scrubPositionMs
                if (scrubMs != null && state.trickplayFrames.isNotEmpty() && totalDurationMs > 0) {
                    TrickplayOverlay(
                        positionMs = scrubMs,
                        totalDurationMs = totalDurationMs,
                        frames = state.trickplayFrames,
                        timeBarLeftPx = timeBarBounds.first,
                        timeBarTopPx = timeBarBounds.second,
                        timeBarWidthPx = timeBarBounds.third,
                        spriteUrlFor = { sprite -> viewModel.spriteUrl(sprite) }
                    )
                }

                // Back-Button + Titel — synchron mit nativer ControlBar ein-/ausgeblendet
                AnimatedVisibility(
                    visible = controllerVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(8.dp)
                            .statusBarsPadding()
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White)
                        }
                        state.item?.let { item ->
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = item.displayTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            )
                            // „Offline"-Indikator: zeigt klar, dass die lokale
                            // Variante abgespielt wird (kein WLAN-Stream).
                            // state.localFilePath wird in PlayerViewModel.load
                            // gesetzt, sobald ein passender Download in der
                            // Room-DB existiert.
                            if (state.localFilePath != null) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xCC1B5E20))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "📥 Offline",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Aktionen oben rechts — synchron mit ControlBar: Favorit (immer),
                // Löschen (Admin), Settings (nur bei Server-Stream mit playbackInfo).
                AnimatedVisibility(
                    visible = controllerVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(8.dp)
                            .statusBarsPadding()
                    ) {
                        state.item?.let { item ->
                            IconButton(onClick = { viewModel.toggleFavorite() }) {
                                Icon(
                                    if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = "Favorit",
                                    tint = if (item.favorite) Color(0xFFE91E63) else Color.White
                                )
                            }
                            if (state.isAdmin) {
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = Color.White)
                                }
                            }
                        }
                        if (state.playbackInfo != null) {
                            IconButton(onClick = { showQualityMenu = true }) {
                                Icon(Icons.Filled.Settings, "Qualität", tint = Color.White)
                            }
                        }
                    }
                }

                // Lösch-Bestätigung (Admin)
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Video löschen?") },
                        text = {
                            Text(
                                "„${state.item?.displayTitle ?: ""}“ wird inklusive Datei " +
                                "unwiderruflich vom Server gelöscht."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirm = false
                                viewModel.deleteItem()
                            }) {
                                Text("Löschen", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") }
                        }
                    )
                }

                // Quality-Menu — anchor top-end (am Ort des Compose-Settings-Buttons)
                state.playbackInfo?.let { info ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 56.dp)
                            .statusBarsPadding()
                    ) {
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false }
                        ) {
                            Text(
                                "Wiedergabe-Modus",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            listOf(
                                null to "Auto (${info.mode})",
                                "direct" to "Direct Play",
                                "transcode" to "Transcode"
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (state.selectedMode == mode)
                                                GoldfishOrange else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.changeQuality(mode, state.selectedProfile)
                                        showQualityMenu = false
                                    }
                                )
                            }

                            if (info.mode == "transcode" || state.selectedMode == "transcode") {
                                HorizontalDivider()
                                Text(
                                    "Qualitätsprofil",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                (listOf(null to "Auto") + (info.profiles?.map { it.id to it.label } ?: emptyList()))
                                    .forEach { (profileId, label) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    label,
                                                    color = if (state.selectedProfile == profileId)
                                                        GoldfishOrange else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                viewModel.changeQuality(state.selectedMode, profileId)
                                                showQualityMenu = false
                                            }
                                        )
                                    }
                            }

                            // Untertitel-Auswahl (nur wenn überhaupt welche da sind außer "Aus")
                            if (state.subtitleOptions.size > 1) {
                                HorizontalDivider()
                                Text(
                                    "Untertitel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                state.subtitleOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                opt.label,
                                                color = if (state.selectedSubtitleKey == opt.key)
                                                    GoldfishOrange else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            viewModel.selectSubtitle(opt.key)
                                            showQualityMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoldfishPlayer(
    context: Context,
    playbackUrl: String,
    isHls: Boolean,
    resumePositionMs: Long,
    totalDurationMs: Long = 0L,
    subtitleOptions: List<SubtitleOption> = emptyList(),
    selectedSubtitleKey: String = "off",
    onPositionChanged: (Long) -> Unit,
    onNextRandom: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onControllerVisibilityChanged: (Boolean) -> Unit = {},
    onScrubPositionChanged: (Long?) -> Unit = {},
    onTimeBarBoundsChanged: (left: Int, top: Int, width: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val apiClientProvider = remember {
        try {
            EntryPointAccessors.fromApplication<ApiClientProviderEntryPoint>(
                context.applicationContext
            ).apiClientProvider()
        } catch (e: Exception) {
            null
        }
    }

    // Virtueller Offset (ms) des aktuell laufenden Transcode-Streams: ffmpeg
    // wurde mit -ss <offset> gestartet, die lokale HLS-Playlist zaehlt aber
    // wieder ab 0. Absolute Position im Film = exoPlayer.currentPosition +
    // virtualOffset. Bei Direct Play / lokalen Dateien bleibt der Wert 0.
    // remember(playbackUrl): bei neuem Item/Quality-Wechsel zurueck auf 0.
    val virtualOffset = remember(playbackUrl) { mutableStateOf(0L) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val absPos = currentPosition + virtualOffset.value
                    if (!isPlaying && absPos > 5000) {
                        onPositionChanged(absPos)
                    }
                }
            })
        }
    }

    // Aktuelle Werte über rememberUpdatedState — der wrappedPlayer wird nur
    // einmal erzeugt und liest beim Aufruf der Override-Methoden den jeweils
    // aktuellen Wert. Sonst würde ein neuer wrappedPlayer-Instance bei jeder
    // Änderung erzeugt und der PlayerView hielte weiterhin den alten — wodurch
    // die Duration-Override nie greifen würde, weil totalDurationMs initial 0
    // ist (bevor das Item geladen ist).
    val currentTotalDurationMs by rememberUpdatedState(totalDurationMs)
    val currentOnNext by rememberUpdatedState(onNextRandom)
    val currentSubtitleOptions by rememberUpdatedState(subtitleOptions)

    // Baut eine HLS-MediaSource (optional mit ffmpeg-Startoffset) und haengt sie
    // an den ExoPlayer. Aufgerufen beim ersten Laden UND bei jedem Transcode-
    // Seek-Restart. startSec null/0 => Stream ab Anfang.
    val loadHls: (Long?) -> Unit = { startSec ->
        val subConfigs = currentSubtitleOptions.mapNotNull { opt ->
            val uri = opt.externalUri ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
                .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                .setLanguage(opt.language)
                .build()
        }
        val url = if (startSec != null && startSec > 0) {
            val sep = if (playbackUrl.contains("?")) "&" else "?"
            // _t bricht den OkHttp-Cache fuer die m3u8 (jede Session frisch);
            // der Server ignoriert _t beim Session-Key.
            "$playbackUrl${sep}start=$startSec&_t=${System.currentTimeMillis()}"
        } else playbackUrl
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subConfigs)
            .build()
        val okHttpClient = apiClientProvider?.okHttpClient
        val dataSourceFactory = if (okHttpClient != null) {
            OkHttpDataSource.Factory(okHttpClient)
        } else {
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
        }
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    // Transcode-Seek-Restart: Ziel ausserhalb des bereits produzierten HLS-
    // Materials => neue ffmpeg-Session ab der Zielsekunde (analog Browser
    // restartTranscodeAt + virtualOffset). Lokale Playlist startet wieder bei 0,
    // virtualOffset traegt die absolute Basis.
    val restartTranscode: (Long) -> Unit = { absoluteMs ->
        val target = absoluteMs.coerceAtLeast(0)
        virtualOffset.value = target
        loadHls(target / 1000)
        exoPlayer.playWhenReady = true
    }

    val currentRestart by rememberUpdatedState(restartTranscode)
    val currentIsTranscode by rememberUpdatedState(isHls)

    val wrappedPlayer = remember(exoPlayer) {
        object : androidx.media3.common.ForwardingPlayer(exoPlayer) {
            private fun offset(): Long = if (currentIsTranscode) virtualOffset.value else 0L

            override fun hasNextMediaItem(): Boolean = currentOnNext != null
            override fun isCommandAvailable(command: Int): Boolean {
                if (currentOnNext != null && (command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                            command == Player.COMMAND_SEEK_TO_NEXT)) return true
                return super.isCommandAvailable(command)
            }
            override fun seekToNextMediaItem() {
                currentOnNext?.invoke() ?: super.seekToNextMediaItem()
            }
            override fun seekToNext() {
                currentOnNext?.invoke() ?: super.seekToNext()
            }

            // --- Absolute Position (lokale Playlist-Zeit + virtualOffset) ---
            // TimeBar und Skip-Buttons lesen diese Werte; so zeigt der Balken die
            // echte Position im Film, auch wenn die ffmpeg-Session mitten im Film
            // gestartet wurde.
            override fun getCurrentPosition(): Long = super.getCurrentPosition() + offset()
            override fun getContentPosition(): Long = super.getContentPosition() + offset()
            override fun getBufferedPosition(): Long = super.getBufferedPosition() + offset()
            override fun getContentBufferedPosition(): Long = super.getContentBufferedPosition() + offset()

            // --- Seek-Interception ---
            // Zielposition ist absolut (TimeBar arbeitet auf der vollen Dauer).
            // Liegt sie im bereits produzierten Material der laufenden Session,
            // lokal seeken; sonst ffmpeg ab der Zielsekunde neu starten.
            private fun handleAbsoluteSeek(absoluteMs: Long) {
                if (!currentIsTranscode) {
                    super.seekTo(absoluteMs.coerceAtLeast(0))
                    return
                }
                val off = virtualOffset.value
                val localTarget = absoluteMs - off
                val realDur = super.getDuration()
                val producedEnd = maxOf(
                    super.getContentBufferedPosition(),
                    if (realDur != C.TIME_UNSET) realDur else 0L
                )
                // +5s Toleranz: knapp vor dem produzierten Rand wartet ExoPlayer
                // auf das naechste Segment (Session produziert ohnehin weiter).
                if (localTarget in 0..(producedEnd + 5000)) {
                    super.seekTo(localTarget.coerceAtLeast(0))
                } else {
                    currentRestart(absoluteMs.coerceAtLeast(0))
                }
            }
            override fun seekTo(positionMs: Long) = handleAbsoluteSeek(positionMs)
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) = handleAbsoluteSeek(positionMs)
            override fun seekForward() = handleAbsoluteSeek(currentPosition + seekForwardIncrement)
            override fun seekBack() = handleAbsoluteSeek(currentPosition - seekBackIncrement)

            // Volle Item-Dauer aus DB statt wachsender HLS-Playlist-Dauer anzeigen.
            // Media3's PlayerControlView liest Window.durationUs DIREKT aus der
            // Timeline, daher muss die Timeline gewrappt werden — nicht nur die
            // getContentDuration()-Methode.
            override fun getCurrentTimeline(): Timeline {
                val original = super.getCurrentTimeline()
                val overrideMs = currentTotalDurationMs
                if (overrideMs <= 0 || original.isEmpty) return original
                return DurationOverrideTimeline(original, overrideMs * 1000L)
            }
            override fun getDuration(): Long {
                val real = super.getDuration()
                return if (currentTotalDurationMs > 0) currentTotalDurationMs else real
            }
            override fun getContentDuration(): Long {
                val real = super.getContentDuration()
                return if (currentTotalDurationMs > 0) currentTotalDurationMs else real
            }
            override fun isCurrentMediaItemLive(): Boolean = false
            override fun isCurrentMediaItemDynamic(): Boolean = false
        }
    }

    LaunchedEffect(playbackUrl) {
        if (playbackUrl.isBlank()) return@LaunchedEffect

        if (isHls) {
            // Transcode/HLS: Resume NICHT per seekTo — die wachsende Playlist
            // reicht nur bis zum produzierten Segment, ein seekTo wuerde clampen.
            // Stattdessen ffmpeg direkt ab der Resume-Sekunde starten und
            // virtualOffset setzen, damit die Zeitanzeige absolut bleibt.
            val startSec = if (resumePositionMs > 5000) resumePositionMs / 1000 else 0L
            virtualOffset.value = if (startSec > 0) startSec * 1000 else 0L
            loadHls(if (startSec > 0) startSec else null)
        } else {
            // Direct Play / lokale Datei: ganze Datei per Range verfuegbar,
            // klassisches seekTo fuer Resume.
            val subConfigs = subtitleOptions.mapNotNull { opt ->
                val uri = opt.externalUri ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
                    .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                    .setLanguage(opt.language)
                    .build()
            }
            val mediaItem = MediaItem.Builder()
                .setUri(playbackUrl)
                .setSubtitleConfigurations(subConfigs)
                .build()
            val isLocal = playbackUrl.startsWith("file://") || playbackUrl.startsWith("content://")
            val okHttpClient = apiClientProvider?.okHttpClient
            if (okHttpClient != null && !isLocal) {
                // Server-Stream → über OkHttp (Auth-Cookie, Caching)
                val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                exoPlayer.setMediaSource(mediaSource)
            } else {
                // Lokal (file:// oder content:// von SAF) → DefaultDataSource via setMediaItem
                exoPlayer.setMediaItem(mediaItem)
            }
            exoPlayer.prepare()
            if (resumePositionMs > 5000) {
                exoPlayer.seekTo(resumePositionMs)
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            onPositionChanged(exoPlayer.currentPosition + virtualOffset.value)
            exoPlayer.release()
        }
    }

    // Untertitel-Auswahl an ExoPlayer durchreichen. Sprach-basierter Selektor
    // greift auf alle Tracks (extern UND eingebettet wie PGS bei Direct Play).
    LaunchedEffect(selectedSubtitleKey, subtitleOptions) {
        val opt = subtitleOptions.firstOrNull { it.key == selectedSubtitleKey }
        val params = exoPlayer.trackSelectionParameters.buildUpon()
        if (opt == null || opt.key == "off" || opt.language.isNullOrBlank()) {
            params.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            params.setPreferredTextLanguage(opt.language)
        }
        exoPlayer.trackSelectionParameters = params.build()
    }

    // Aktuelle Callbacks (so dass die einmal-gefangenen Lambdas in der Factory immer die neusten Funktionen rufen)
    val currentOnVisChanged by rememberUpdatedState(onControllerVisibilityChanged)
    val currentOnSettingsClick by rememberUpdatedState(onSettingsClick)
    val currentOnScrubChanged by rememberUpdatedState(onScrubPositionChanged)
    val currentOnBoundsChanged by rememberUpdatedState(onTimeBarBoundsChanged)

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = wrappedPlayer
                useController = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        currentOnVisChanged(visibility == View.VISIBLE)
                    }
                )
                // Native Children sind erst nach Layout greifbar
                val playerView = this
                post {
                    // Native Settings-Button ausblenden — Compose-Zahnrad oben rechts ersetzt es
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    val timeBarView = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                    if (timeBarView is TimeBar) {
                        timeBarView.addListener(object : TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                                updateTimeBarBounds(playerView, timeBarView, currentOnBoundsChanged)
                                currentOnScrubChanged(position)
                            }
                            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                                currentOnScrubChanged(position)
                            }
                            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                                currentOnScrubChanged(null)
                            }
                        })
                    }
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Timeline-Wrapper, der die Window-Duration ersetzt. Media3's PlayerControlView
 * liest die Window.durationUs direkt für die TimeBar — Override von
 * Player.getDuration() reicht nicht.
 */
private class DurationOverrideTimeline(
    private val original: Timeline,
    private val overrideDurationUs: Long
) : Timeline() {
    override fun getWindowCount(): Int = original.windowCount
    override fun getPeriodCount(): Int = original.periodCount
    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        original.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.durationUs = overrideDurationUs
        window.isDynamic = false
        return window
    }
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        return original.getPeriod(periodIndex, period, setIds)
    }
    override fun getIndexOfPeriod(uid: Any): Int = original.getIndexOfPeriod(uid)
    override fun getUidOfPeriod(periodIndex: Int): Any = original.getUidOfPeriod(periodIndex)
}

private fun updateTimeBarBounds(
    playerView: View,
    timeBar: View,
    onChanged: (left: Int, top: Int, width: Int) -> Unit
) {
    // TimeBar-Position relativ zum PlayerView ermitteln
    val playerLoc = IntArray(2)
    val timeBarLoc = IntArray(2)
    playerView.getLocationInWindow(playerLoc)
    timeBar.getLocationInWindow(timeBarLoc)
    val left = timeBarLoc[0] - playerLoc[0]
    val top = timeBarLoc[1] - playerLoc[1]
    onChanged(left, top, timeBar.width)
}

@Composable
private fun TrickplayOverlay(
    positionMs: Long,
    totalDurationMs: Long,
    frames: List<TrickplayFrame>,
    timeBarLeftPx: Int,
    timeBarTopPx: Int,
    timeBarWidthPx: Int,
    spriteUrlFor: (String) -> String
) {
    val frame = remember(positionMs, frames) { TrickplayParser.frameAt(frames, positionMs) }
        ?: return
    val context = LocalContext.current
    val spriteUrl = remember(frame.sprite) { spriteUrlFor(frame.sprite) }

    var bitmap by remember(spriteUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(spriteUrl) {
        if (spriteUrl.isBlank()) return@LaunchedEffect
        try {
            val loader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context).data(spriteUrl).build()
            val result = loader.execute(request)
            val img = result.image ?: return@LaunchedEffect
            bitmap = img.toBitmap().asImageBitmap()
        } catch (_: Exception) { /* still */ }
    }

    val density = LocalDensity.current
    // Tile-Größe in dp (basierend auf den Frame-Pixeln, mit etwas Vergrößerung)
    val tileWidthDp = with(density) { (frame.w * 1.6f).toDp() }
    val tileHeightDp = with(density) { (frame.h * 1.6f).toDp() }

    // x-Position: zentriert über dem Scrub-Daumen, geclippt an den TimeBar-Rändern
    val ratio = (positionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    val thumbXPx = timeBarLeftPx + (ratio * timeBarWidthPx).toInt()
    val tileWidthPx = with(density) { tileWidthDp.toPx().toInt() }
    val rawLeftPx = thumbXPx - tileWidthPx / 2
    // Innerhalb des PlayerView-Frames bleiben (8dp Mindest-Rand)
    val marginPx = with(density) { 8.dp.toPx().toInt() }
    val leftPx = rawLeftPx.coerceAtLeast(marginPx)
    val tileTopPx = timeBarTopPx - with(density) { (tileHeightDp + 32.dp).toPx().toInt() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(leftPx, tileTopPx.coerceAtLeast(0))
            }
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(tileWidthDp, tileHeightDp).clip(RoundedCornerShape(4.dp))) {
                bitmap?.let { bmp ->
                    drawImage(
                        image = bmp,
                        srcOffset = IntOffset(frame.x, frame.y),
                        srcSize = IntSize(frame.w, frame.h),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }
            Text(
                text = formatTime(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ApiClientProviderEntryPoint {
    fun apiClientProvider(): ApiClientProvider
}
