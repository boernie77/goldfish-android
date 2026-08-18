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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val deleted: Boolean = false
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

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update { it.copy(baseUrl = settings.serverUrl) }
            }
        }
    }

    fun load(itemId: Int) {
        viewModelScope.launch {
            val admin = authRepository.getCurrentStatus()?.isAdmin ?: false
            _state.update { it.copy(isLoading = true, errorMessage = null, trickplayFrames = emptyList(), isAdmin = admin) }
            // Trickplay parallel im Hintergrund laden — non-blocking, UI startet ohne darauf zu warten
            launch {
                val frames = itemRepository.getTrickplayFrames(itemId)
                _state.update { it.copy(trickplayFrames = frames) }
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
                return@launch
            }

            // Server-seitig als "zuletzt gespielt" markieren — wie der Browser
            // beim Player-Open. Ohne diesen Call setzt die App nie
            // last_played_at, und in der App gespielte Videos erscheinen nie in
            // der Online-Sortierung "Zuletzt gespielt". Fire-and-forget.
            launch { itemRepository.markPlayed(itemId) }

            val resumePosSec = try { itemRepository.getResumePosition(itemId) } catch (_: Exception) {
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
                        resumePositionMs = resumeMs
                    )
                }
                return@launch
            }
            // Kein Download → Server-Stream noetig (auch wenn offlineOnly an
            // ist, ist das ein klarer Hinweis dass dieses Item nicht offline
            // verfuegbar ist; getPlayback() schlaegt ggf. fehl und liefert
            // eine sinnvolle Fehlermeldung).
            // Fetch playback info from server
            when (val pbResult = itemRepository.getPlayback(itemId)) {
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
                            selectedSubtitleKey = "off"
                        )
                    }
                }
                is Result.Error -> {
                    _state.update {
                        it.copy(isLoading = false, errorMessage = pbResult.message)
                    }
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
        val itemId = _state.value.item?.id ?: return
        val durationMs = (_state.value.item?.durationSec ?: 0.0) * 1000
        // Only save if not nearly at the end (> 95%)
        val shouldMark = durationMs > 0 && positionMs.toDouble() / durationMs > 0.9
        viewModelScope.launch {
            if (shouldMark) {
                itemRepository.setWatched(itemId, true)
            } else {
                itemRepository.setResume(itemId, positionMs / 1000.0)
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
}
