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
    val localLibraries: List<LocalLibraryEntity> = emptyList()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val downloadRepository: DownloadRepository,
    private val database: AppDatabase,
    private val itemRepository: ItemRepository,
    private val localLibraryRepository: LocalLibraryRepository,
    private val authRepository: AuthRepository
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
