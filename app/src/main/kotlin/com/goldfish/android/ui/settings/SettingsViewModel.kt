package com.goldfish.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.AppSettings
import com.goldfish.android.data.CacheSize
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.local.AppDatabase
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.DownloadRepository
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.LocalLibraryRepository
import com.goldfish.android.data.repository.Result
import com.goldfish.android.data.repository.WatchLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val downloadCount: Int = 0,
    val totalDownloadSize: Long = 0,
    val serverLibraries: List<Library> = emptyList(),
    val localLibraries: List<LocalLibraryEntity> = emptyList(),
    // "Nächste Folge automatisch starten" — Pro-Konto-Schalter auf dem SERVER
    // (GET/PUT api/playback/preferences, Default AUS). Liegt serverseitig in
    // user_settings, damit die Einstellung auch auf dem nächsten Gerät gilt.
    val autoplayNext: Boolean = false,
    val autoplayNextLoaded: Boolean = false,
    // Gesehen-Sync: Eintrag nur mit Server-Session sinnvoll; der Untertitel
    // zeigt wie im Browser-Menü eine wartende Anfrage bzw. die Partner an.
    val loggedIn: Boolean = false,
    val watchLinkHint: String = WATCH_LINK_HINT_DEFAULT,
    val watchLinkAttention: Boolean = false
)

const val WATCH_LINK_HINT_DEFAULT = "Gesehen-Status mit einem anderen Konto teilen"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val downloadRepository: DownloadRepository,
    private val database: AppDatabase,
    private val itemRepository: ItemRepository,
    private val localLibraryRepository: LocalLibraryRepository,
    private val authRepository: AuthRepository,
    private val watchLinkRepository: WatchLinkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            downloadRepository.getAllDownloads().collect { downloads ->
                val totalSize = downloads.sumOf { it.fileSize }
                _state.update { it.copy(downloadCount = downloads.size, totalDownloadSize = totalSize) }
            }
        }
        viewModelScope.launch {
            val result = itemRepository.getLibraries()
            if (result is Result.Success) {
                _state.update { it.copy(serverLibraries = result.data) }
            }
        }
        // Lokale Bibliotheken des aktuellen Users für die Merge-Auswahl
        viewModelScope.launch {
            val username = authRepository.getCurrentStatus()?.username
            val flow = if (username != null)
                localLibraryRepository.observeLibrariesForUser(username)
            else
                localLibraryRepository.observeLibraries()
            flow.collect { libs -> _state.update { it.copy(localLibraries = libs) } }
        }
        loadAutoplayNext()
        _state.update { it.copy(loggedIn = authRepository.getCurrentStatus()?.isAuthenticated == true) }
    }

    /** Untertitel des Gesehen-Sync-Eintrags — Texte wie im Browser-Menü
     *  (refreshWatchLinkHint in admin.js). Wird bei jedem Betreten der
     *  Einstellungen neu geladen, damit er nach Aktionen auf der
     *  Gesehen-Sync-Seite stimmt. Fehler → stiller Standardtext. */
    fun refreshWatchLinkHint() {
        if (!_state.value.loggedIn) return
        viewModelScope.launch {
            val links = (watchLinkRepository.getWatchLinks() as? Result.Success)?.data ?: return@launch
            val incoming = links.filter { it.status == "pending_incoming" }
            val active = links.filter { it.status == "accepted" }
            val (hint, attention) = when {
                incoming.isNotEmpty() ->
                    "Anfrage von ${incoming.joinToString(", ") { it.partnerName }} wartet" to true
                active.isNotEmpty() ->
                    "Verknüpft mit ${active.joinToString(", ") { it.partnerName }}" to false
                else -> WATCH_LINK_HINT_DEFAULT to false
            }
            _state.update { it.copy(watchLinkHint = hint, watchLinkAttention = attention) }
        }
    }

    /** Pro-Konto-Schalter laden (Default AUS bei Fehler/altem Serverstand). */
    fun loadAutoplayNext() {
        viewModelScope.launch {
            val on = itemRepository.getAutoplayNext()
            _state.update { it.copy(autoplayNext = on, autoplayNextLoaded = true) }
        }
    }

    /** Pro-Konto-Schalter setzen — optimistisch, Rollback wenn der Server
     *  nicht bestätigt (z. B. offline). */
    fun setAutoplayNext(enabled: Boolean) {
        _state.update { it.copy(autoplayNext = enabled) }
        viewModelScope.launch {
            if (!itemRepository.setAutoplayNext(enabled)) {
                _state.update { it.copy(autoplayNext = !enabled) }
            }
        }
    }

    fun setShowFileSize(kind: String, enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.saveShowFileSize(kind, enabled) }
    }

    fun saveMergedServerLibraryIds(ids: Set<Int>) {
        viewModelScope.launch { settingsDataStore.saveMergedServerLibraryIds(ids) }
    }

    fun saveMergedLocalLibraryIds(ids: Set<Int>) {
        viewModelScope.launch { settingsDataStore.saveMergedLocalLibraryIds(ids) }
    }

    fun saveServerUrl(url: String) {
        viewModelScope.launch {
            settingsDataStore.saveServerUrl(url)
            apiClientProvider.configure(url, _state.value.settings.cacheSizeBytes)
            _state.update { it.copy(saveSuccess = true) }
        }
    }

    fun saveCacheSize(cacheSize: CacheSize) {
        viewModelScope.launch {
            settingsDataStore.saveCacheSize(cacheSize.bytes)
            apiClientProvider.configure(_state.value.settings.serverUrl, cacheSize.bytes)
        }
    }

    fun saveDownloadDir(path: String) {
        viewModelScope.launch {
            settingsDataStore.saveDownloadDir(path)
        }
    }

    fun saveDownloadTree(uri: String, displayName: String) {
        viewModelScope.launch {
            settingsDataStore.saveDownloadTree(uri, displayName)
        }
    }

    /** Loescht ALLE Downloads — Dateien auf Disk/SAF und DB-Eintraege.
     *  Wird vom „Alle Downloads entfernen"-Button im Settings-Screen
     *  getriggert (mit vorheriger Bestaetigungs-Dialog). */
    fun deleteAllDownloads() {
        viewModelScope.launch {
            downloadRepository.deleteAllDownloads()
        }
    }

    fun clearDownloadTree() {
        viewModelScope.launch {
            settingsDataStore.saveDownloadTree("", "")
        }
    }
}
