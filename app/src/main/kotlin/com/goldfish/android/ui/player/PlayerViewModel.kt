package com.goldfish.android.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.PlaybackInfo
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.DownloadRepository
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.OfflineRepository
import com.goldfish.android.data.repository.Result
import com.goldfish.android.data.trickplay.TrickplayFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Countdown-Länge des „Nächste Folge"-Hinweises in Sekunden — Browser-Parität
 *  (NEXT_EPISODE_SECONDS in player.js). */
const val NEXT_EPISODE_SECONDS = 10

data class PlayerState(
    val isLoading: Boolean = true,
    val item: Item? = null,
    val playbackInfo: PlaybackInfo? = null,
    val errorMessage: String? = null,
    val baseUrl: String = "",
    val localFilePath: String? = null,
    val resumePositionMs: Long = 0L,
    // Qualitäts-Einstellungen
    val selectedMode: String? = null,    // null = auto, "direct", "transcode"
    val selectedProfile: String? = null, // null = auto, "orig", "1080p", etc.
    // Trickplay
    val trickplayFrames: List<TrickplayFrame> = emptyList(),
    // Untertitel
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val selectedSubtitleKey: String = "off",
    // Admin-Flag → steuert Sichtbarkeit des Lösch-Buttons im Player.
    val isAdmin: Boolean = false,
    // Wird true nachdem das Item gelöscht wurde → Screen navigiert zurück.
    val deleted: Boolean = false,
    // --- "Nächste Folge automatisch starten" (Server: api/playback_next.go) ---
    // Pro-Konto-Schalter vom Server (Default AUS). Ist er aus, passiert am
    // Folgenende exakt das, was die App bisher tat: die Wiedergabe endet.
    val autoplayNextEnabled: Boolean = false,
    // Nächste Folge DERSELBEN Serie (GET api/items/{id}/next-episode).
    // null = letzte Folge der Serie / kein Serien-Item → kein Hinweis.
    val nextEpisode: Item? = null,
    val showNextEpisodeOverlay: Boolean = false,
    val nextEpisodeCountdown: Int = NEXT_EPISODE_SECONDS,
    // Zuletzt gewähltes Auflösungsprofil (Persistenz: SettingsDataStore) —
    // wird NUR beim Autoplay-Start der nächsten Folge angewandt, ein normal
    // geöffneter Titel startet weiterhin ohne Begrenzung (Browser-Parität).
    val lastPlaybackProfile: String? = null,
    // --- "Vorspann überspringen" (Server: internal/api/introskip.go) ---
    // Absolutes Zeitfenster des erkannten Vorspanns in Millisekunden.
    // Beide null = keine Erkennung für dieses Item → es gibt keinen Button.
    // Wird bei JEDEM loadItem() zuerst geleert, damit beim In-Place-Wechsel
    // zur nächsten Folge nie das Fenster der Vorfolge stehen bleibt.
    val introStartMs: Long? = null,
    val introEndMs: Long? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val downloadRepository: DownloadRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val offlineRepository: OfflineRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    // User-Frage 2026-09-14: "Wir haben heute Fehler in der iOS behoben. Gelten
    // die auch für Android?" — Untersuchung ergab: der Music-Player
    // (MusicPlaybackService) meldet Start/Stop bereits korrekt bei jedem
    // Titelwechsel, aber der VIDEO-Player hier rief `reportPlaybackStart`/
    // `reportPlaybackStop` bis dahin NIRGENDS auf — ein noch grösseres Loch als
    // der iOS-Bug von heute (dort fehlte der Stop-Report nur bei Next/Prev/
    // Shuffle, hier komplett). Serverseitige Transcode-Sessions blieben dadurch
    // bis zu 30 Min. aktiv, unabhängig vom `StopAllForItem`/`stopSuppressWindow`-
    // Fix von heute im Server-Repo, der ja einen Stop-Report voraussetzt.
    // `onNextRandom` (siehe Navigation.kt) navigiert bei jedem Weiter-Klick zu
    // einer NEUEN Route mit `popUpTo(...){inclusive=true}` — anders als bei
    // Apples PlayerView (wiederverwendete View-Instanz) bekommt hier JEDE
    // Wiedergabe ihre eigene ViewModel-Instanz, `onCleared()` ist also der
    // richtige, zuverlässige Ort für den Stop-Report (feuert bei Zurück-
    // Navigation UND bei jedem Weiter/Zufall-Wechsel).
    // WICHTIG seit dem Autoplay-Nachfolger (unten): der In-Place-Wechsel zur
    // nächsten Folge zerstört diese Instanz NICHT — dort meldet
    // `finishCurrentPlayback()` den Stop-Report (und die Resume-Position) des
    // alten Items explizit, bevor die neue Folge geladen wird. Pro Item bleibt
    // es damit bei genau EINER Server-Session (Start + Stop).
    private var serverPlaybackReported = false
    private var lastKnownPositionMs = 0L
    // Einmal pro Player-Instanz geladen — der Schalter ändert sich während
    // einer Wiedergabe nicht von selbst.
    private var autoplayPrefLoaded = false
    private var nextEpisodeCountdownJob: Job? = null
    // Item, für das der Nutzer den Hinweis weggeklickt hat. Solange gilt:
    // kein erneuter Hinweis für dieselbe Folge (Abbrechen = bisheriges
    // Verhalten, auch wenn ExoPlayer STATE_ENDED nochmals meldet).
    private var nextEpisodeSuppressedItemId: Int? = null
    // In-Memory-Spiegel des persistierten Profils (AppSettings.lastPlaybackProfile).
    private var lastPlaybackProfile: String? = null

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                lastPlaybackProfile = settings.lastPlaybackProfile.takeIf { it.isNotBlank() }
                _state.update {
                    it.copy(baseUrl = settings.serverUrl, lastPlaybackProfile = lastPlaybackProfile)
                }
            }
        }
    }

    fun load(itemId: Int) {
        viewModelScope.launch {
            // Pro-Konto-Schalter einmal holen (Fehler/alter Serverstand → AUS,
            // also exakt das bisherige Verhalten).
            if (!autoplayPrefLoaded) {
                autoplayPrefLoaded = true
                val on = itemRepository.getAutoplayNext()
                _state.update { it.copy(autoplayNextEnabled = on) }
            }
            loadItem(itemId, profile = null, skipResume = false)
        }
    }

    /**
     * Lädt ein Item in den laufenden Player-Zustand. Wird sowohl beim Öffnen
     * des Screens (`load`) als auch beim In-Place-Wechsel zur nächsten Folge
     * (`switchToNextEpisode`) aufgerufen — deshalb KEIN Navigationswechsel und
     * keine neue ViewModel-Instanz.
     *
     * `profile`: Auflösungsprofil für `GET api/playback/{id}?profile=…`
     * (null = Auto, wie bisher). `skipResume`: bei der automatisch gestarteten
     * nächsten Folge wird — wie im Browser (`skipResume: true`) — NICHT an der
     * alten Position fortgesetzt, die Folge startet von vorn.
     */
    private suspend fun loadItem(itemId: Int, profile: String?, skipResume: Boolean) {
        val admin = authRepository.getCurrentStatus()?.isAdmin ?: false
        // Neue Folge → ein früheres "Abbrechen" gilt nicht mehr.
        nextEpisodeSuppressedItemId = null
        _state.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                trickplayFrames = emptyList(),
                isAdmin = admin,
                // Vorspann-Fenster der vorigen Folge sofort verwerfen.
                introStartMs = null,
                introEndMs = null
            )
        }
        // Trickplay parallel im Hintergrund laden — non-blocking, UI startet ohne darauf zu warten.
        // Eigener viewModelScope-Job (loadItem ist eine suspend-Funktion ohne
        // CoroutineScope-Receiver): das Ergebnis wird verworfen, wenn inzwischen
        // eine andere Folge läuft (In-Place-Wechsel).
        viewModelScope.launch {
            val frames = itemRepository.getTrickplayFrames(itemId)
            if (_state.value.item?.id == itemId || _state.value.item == null) {
                _state.update { it.copy(trickplayFrames = frames) }
            }
        }

        // Check for local download
        val download = downloadRepository.getDownload(itemId)

        // Item-Quelle waehlen — bei Offline-Filter ODER vorhandenem Download
        // OHNE Netz versuchen wir den lokalen Cache. Bei Netz-Fehler fallen
        // wir auch auf den Cache zurueck (offlineRepository.item).
        // Item-Quelle: Server zuerst mit try/catch im Repo, sonst Cache.
        // Bei lokalem Download (download != null) reicht der Cache —
        // wir spielen ohnehin file://, kein Server noetig.
        val itemFromServer = (itemRepository.getItem(itemId) as? Result.Success)?.data
        val itemFromOffline = offlineRepository.item(itemId)
        val item = itemFromServer ?: itemFromOffline
        if (item == null) {
            _state.update {
                it.copy(isLoading = false, errorMessage = "Video nicht gefunden")
            }
            return
        }

        // "Vorspann überspringen" (Browser-Parität: maybeToggleIntroSkip in
        // player.js). Fenster NUR übernehmen, wenn der Server BEIDE Felder
        // liefert und das Fenster plausibel ist (Ende > Start) — sonst bleibt
        // es null und es erscheint kein Button. Offline/aus dem Cache
        // geladene Items haben die Felder nicht (Listen-Endpoints liefern sie
        // nicht) → dort gibt es die Funktion schlicht nicht.
        var introStartMs: Long? = null
        var introEndMs: Long? = null
        val introStartSec = item.introStartSec
        val introEndSec = item.introEndSec
        if (introStartSec != null && introEndSec != null &&
            introEndSec > introStartSec && introEndSec > 0.0
        ) {
            introStartMs = (introStartSec * 1000).toLong().coerceAtLeast(0L)
            introEndMs = (introEndSec * 1000).toLong()
        }

        // Server-seitig als "zuletzt gespielt" markieren — wie der Browser
        // beim Player-Open. Ohne diesen Call setzt die App nie
        // last_played_at, und in der App gespielte Videos erscheinen nie in
        // der Online-Sortierung "Zuletzt gespielt". Fire-and-forget.
        viewModelScope.launch { itemRepository.markPlayed(itemId) }

        val resumePosSec = if (skipResume) 0.0 else try {
            itemRepository.getResumePosition(itemId)
        } catch (_: Exception) {
            item.resumePosSec ?: 0.0
        }
        val resumeMs = (resumePosSec * 1000).toLong()

        if (download != null) {
            // Offline-Abspielzeit lokal stempeln → treibt den Offline-Sort
            // "Zuletzt abgespielt" (Server-last_played_at ist offline nicht da).
            downloadRepository.markPlayed(itemId)
            // Play locally — kein Server-Call mehr noetig.
            _state.update {
                it.copy(
                    isLoading = false,
                    item = item,
                    localFilePath = download.localPath,
                    resumePositionMs = resumeMs,
                    introStartMs = introStartMs,
                    introEndMs = introEndMs
                )
            }
            return
        }
        // Kein Download → Server-Stream noetig (auch wenn offlineOnly an
        // ist, ist das ein klarer Hinweis dass dieses Item nicht offline
        // verfuegbar ist; getPlayback() schlaegt ggf. fehl und liefert
        // eine sinnvolle Fehlermeldung).
        // Fetch playback info from server
        when (val pbResult = itemRepository.getPlayback(itemId, null, profile)) {
            is Result.Success -> {
                val info = pbResult.data
                android.util.Log.d("GF-Player",
                    "item.durationSec=${item.durationSec} " +
                    "playbackInfo.mode=${info.mode} " +
                    "playbackInfo.item.durationSec=${info.item?.durationSec} " +
                    "subtitleStreams=${info.streams?.count { it.type == "subtitle" } ?: 0}")
                val subOptions = buildSubtitleOptions(info, _state.value.baseUrl, itemId)
                _state.update {
                    it.copy(
                        isLoading = false,
                        item = item,
                        playbackInfo = info,
                        resumePositionMs = resumeMs,
                        subtitleOptions = subOptions,
                        selectedSubtitleKey = "off",
                        introStartMs = introStartMs,
                        introEndMs = introEndMs
                    )
                }
                // Nur im Server-Streaming-Zweig (nicht bei `download != null`
                // oben, der early-returned) — lokale Offline-Wiedergabe hat
                // keine Server-Session, die gemeldet werden müsste.
                serverPlaybackReported = true
                lastKnownPositionMs = resumeMs
                viewModelScope.launch { itemRepository.reportPlaybackStart(itemId) }
                // "Zuletzt gewählte Auflösung merken" (Browser-Parität:
                // state.lastProfile in player.js) — das Profil, mit dem diese
                // Folge läuft, gilt auch für die automatisch folgende Folge.
                val effectiveProfile = profile ?: info.profile
                if (!effectiveProfile.isNullOrBlank()) {
                    lastPlaybackProfile = effectiveProfile
                    _state.update { it.copy(lastPlaybackProfile = effectiveProfile) }
                    viewModelScope.launch { settingsDataStore.saveLastPlaybackProfile(effectiveProfile) }
                }
            }
            is Result.Error -> {
                _state.update {
                    it.copy(isLoading = false, errorMessage = pbResult.message)
                }
            }
        }
    }

    /** Favorit umschalten — optimistisch im State, Server-Call im Hintergrund. */
    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val newFav = !item.favorite
        _state.update { it.copy(item = item.copy(favorite = newFav)) }
        viewModelScope.launch {
            if (itemRepository.setFavorite(item.id, newFav) is Result.Error) {
                // Rollback bei Fehler
                _state.update { st -> st.item?.let { st.copy(item = it.copy(favorite = !newFav)) } ?: st }
            }
        }
    }

    /** Item inkl. Datei löschen (Admin). Setzt bei Erfolg state.deleted=true,
     *  worauf der Screen zurueck navigiert. */
    fun deleteItem() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            when (itemRepository.deleteItem(item.id, deleteFile = true)) {
                is Result.Success -> _state.update { it.copy(deleted = true) }
                is Result.Error -> { /* Fehler still — Player bleibt offen */ }
            }
        }
    }

    fun saveResumePosition(positionMs: Long) {
        lastKnownPositionMs = positionMs
        val item = _state.value.item ?: return
        saveResumeForItem(item, positionMs)
    }

    /** Resume-/Watched-Update für EIN bestimmtes Item. Beim In-Place-Wechsel
     *  muss das ALTE Item explizit adressiert werden — `saveResumePosition`
     *  liest `state.item`, das dann schon die neue Folge wäre. */
    private fun saveResumeForItem(item: Item, positionMs: Long) {
        val durationMs = (item.durationSec ?: 0.0) * 1000
        // Only save if not nearly at the end (> 95%)
        val shouldMark = durationMs > 0 && positionMs.toDouble() / durationMs > 0.9
        viewModelScope.launch {
            if (shouldMark) {
                itemRepository.setWatched(item.id, true)
            } else {
                itemRepository.setResume(item.id, positionMs / 1000.0)
            }
        }
    }

    fun buildPlaybackUrl(): String {
        val state = _state.value
        state.localFilePath?.let { local ->
            // SAF-Downloads liefern eine content:// URI, normale Downloads einen Datei-Pfad
            return if (local.startsWith("content://") || local.startsWith("file://")) local
            else "file://$local"
        }

        val info = state.playbackInfo ?: return ""
        val base = state.baseUrl.trimEnd('/')

        return when (info.mode) {
            "direct" -> "$base/${info.url.trimStart('/')}"
            "transcode" -> {
                val url = info.url.trimStart('/')
                "$base/$url"
            }
            else -> "$base/${info.url.trimStart('/')}"
        }
    }

    fun isHls(): Boolean {
        val info = _state.value.playbackInfo ?: return false
        return info.mode == "transcode" || info.url.contains(".m3u8")
    }

    // Neu laden mit gewähltem Modus/Profil
    fun changeQuality(mode: String?, profile: String?) {
        val itemId = _state.value.item?.id ?: return
        _state.update { it.copy(selectedMode = mode, selectedProfile = profile, isLoading = true) }
        viewModelScope.launch {
            // Vom User explizit gewähltes Profil merken — es gilt auch für die
            // automatisch folgende Folge (siehe switchToNextEpisode).
            if (!profile.isNullOrBlank()) {
                lastPlaybackProfile = profile
                settingsDataStore.saveLastPlaybackProfile(profile)
            }
            when (val pbResult = itemRepository.getPlayback(itemId, mode, profile)) {
                is Result.Success -> {
                    _state.update { it.copy(isLoading = false, playbackInfo = pbResult.data) }
                }
                is Result.Error -> {
                    _state.update { it.copy(isLoading = false, errorMessage = pbResult.message) }
                }
            }
        }
    }

    // ── "Nächste Folge automatisch starten" ─────────────────────────────────
    // Browser-Parität (player.js, maybeAutoplayNextEpisode): am Ende einer
    // Folge wird der Server gefragt, welche Folge als nächste dran wäre, und
    // bei aktivem Pro-Konto-Schalter ein Hinweis mit Countdown gezeigt. Ohne
    // Zutun des Users startet nichts; "Abbrechen" lässt die Wiedergabe am
    // Ende stehen (exakt das bisherige Verhalten).

    /**
     * Vom PlayerScreen gerufen, wenn ExoPlayer das Ende der aktuellen
     * Wiedergabe erreicht (Player.STATE_ENDED, abgesichert gegen ein vorzeitiges
     * Ende der wachsenden Transcode-Playlist — siehe PlayerScreen).
     *
     * Fehler und "keine nächste Folge" dürfen das Wiedergabe-Ende NIE stören:
     * schlimmstenfalls erscheint einfach kein Hinweis.
     */
    fun onPlaybackEnded() {
        val st = _state.value
        if (st.showNextEpisodeOverlay) return          // Countdown läuft schon
        if (!st.autoplayNextEnabled) return            // Option AUS → nichts tun
        val currentId = st.item?.id ?: return
        if (nextEpisodeSuppressedItemId == currentId) return  // "Abbrechen" → Ruhe
        viewModelScope.launch {
            val next = itemRepository.getNextEpisode(currentId)
            // null = letzte Folge / kein Serien-Item (Server-Normalfall) und
            // next.id == currentId wäre eine Schleife → kein Overlay, kein Fehler.
            if (next == null || next.id == currentId) return@launch
            _state.update {
                it.copy(
                    nextEpisode = next,
                    showNextEpisodeOverlay = true,
                    nextEpisodeCountdown = NEXT_EPISODE_SECONDS
                )
            }
            startNextEpisodeCountdown()
        }
    }

    private fun startNextEpisodeCountdown() {
        nextEpisodeCountdownJob?.cancel()
        nextEpisodeCountdownJob = viewModelScope.launch {
            var left = NEXT_EPISODE_SECONDS
            while (left > 0) {
                delay(1000L)
                left -= 1
                _state.update { it.copy(nextEpisodeCountdown = left) }
            }
            // Countdown abgelaufen → nächste Folge starten (wie "Jetzt abspielen").
            startNextEpisode()
        }
    }

    /** "Abbrechen" — Overlay weg, es startet nichts (bisheriges Verhalten). */
    fun cancelNextEpisode() {
        nextEpisodeCountdownJob?.cancel()
        nextEpisodeCountdownJob = null
        nextEpisodeSuppressedItemId = _state.value.item?.id
        _state.update { it.copy(showNextEpisodeOverlay = false, nextEpisode = null) }
    }

    /** "Jetzt abspielen" bzw. abgelaufener Countdown. */
    fun startNextEpisode() {
        val next = _state.value.nextEpisode ?: return
        nextEpisodeCountdownJob?.cancel()
        nextEpisodeCountdownJob = null
        _state.update { it.copy(showNextEpisodeOverlay = false, nextEpisode = null) }
        viewModelScope.launch { switchToNextEpisode(next) }
    }

    /**
     * In-Place-Wechsel auf die nächste Folge: dieselbe ViewModel-Instanz,
     * derselbe Screen, KEIN neuer Eintrag auf dem Navigationsstapel (kein
     * `navController.navigate`). `PlayerScreen`/`GoldfishPlayer` erkennen den
     * Wechsel an der neuen Playback-URL (`LaunchedEffect(playbackUrl)`) und
     * setzen den vorhandenen ExoPlayer neu auf.
     *
     * Vorher wird die ALTE Folge sauber abgeschlossen (Stop-Report + Resume) —
     * genau eine Server-Session pro Item.
     */
    private suspend fun switchToNextEpisode(next: Item) {
        finishCurrentPlayback()
        // Reste der alten Folge aus dem State nehmen, damit der Player die neue
        // Quelle wirklich neu aufsetzt (Modus/Profil gelten ab jetzt für die
        // neue Folge und werden dort frisch ermittelt).
        _state.update {
            it.copy(
                playbackInfo = null,
                localFilePath = null,
                subtitleOptions = emptyList(),
                selectedSubtitleKey = "off",
                selectedMode = null,
                selectedProfile = null,
                trickplayFrames = emptyList(),
                errorMessage = null,
                introStartMs = null,
                introEndMs = null
            )
        }
        // Auflösungsprofil der vorigen Folge übernehmen (Server: ?profile=…);
        // Resume wird übersprungen — die Folge startet von vorn (Browser-Parität).
        loadItem(next.id, profile = lastPlaybackProfile, skipResume = true)
    }

    /** Schliesst die laufende Server-Session des aktuellen Items ab:
     *  Resume-Position/Watched + EIN Stop-Report. Wird nur beim In-Place-Wechsel
     *  gebraucht — beim Verlassen des Players erledigt das `onCleared()`. */
    private suspend fun finishCurrentPlayback() {
        val item = _state.value.item ?: return
        saveResumeForItem(item, lastKnownPositionMs)
        if (serverPlaybackReported) {
            serverPlaybackReported = false
            itemRepository.reportPlaybackStop(
                item.id,
                "ended",
                lastKnownPositionMs / 1000.0,
                item.durationSec ?: 0.0
            )
        }
    }

    fun selectSubtitle(key: String) {
        _state.update { it.copy(selectedSubtitleKey = key) }
    }

    fun spriteUrl(sprite: String): String {
        val itemId = _state.value.item?.id ?: return ""
        return itemRepository.getTrickplaySpriteUrl(itemId, sprite, _state.value.baseUrl)
    }

    fun currentModeLabel(): String {
        val st = _state.value
        val info = st.playbackInfo ?: return "—"
        return when (info.mode) {
            "direct" -> "Direct Play"
            "transcode" -> {
                val profile = st.selectedProfile ?: info.profile ?: "auto"
                val p = info.profiles?.find { it.id == profile }
                "Transcode: ${p?.label ?: profile}"
            }
            else -> info.mode
        }
    }

    /** Meldet das Ende der Server-Wiedergabe, sobald diese ViewModel-Instanz
     *  zerstört wird — sowohl bei Zurück-Navigation als auch bei jedem
     *  Weiter/Zufall-Wechsel (siehe Kommentar bei `serverPlaybackReported`
     *  oben). `viewModelScope` ist zu diesem Zeitpunkt bereits storniert,
     *  deshalb ein eigener, kurzlebiger Scope für den Fire-and-forget-Call —
     *  gleiches "best effort"-Muster wie `ItemRepository.reportPlaybackStop`. */
    override fun onCleared() {
        super.onCleared()
        nextEpisodeCountdownJob?.cancel()
        if (serverPlaybackReported) {
            val itemId = _state.value.item?.id ?: return
            val positionSec = lastKnownPositionMs / 1000.0
            val durationSec = _state.value.item?.durationSec ?: 0.0
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                itemRepository.reportPlaybackStop(itemId, "closed", positionSec, durationSec)
            }
        }
    }
}
