package com.goldfish.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.HomeResponse
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.DownloadRepository
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.LocalLibraryRepository
import com.goldfish.android.data.repository.OfflineRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-Wrapper fuer eine Library — egal ob vom Server oder lokal. Wird im
 *  HomeScreen verwendet, um beide Quellen einheitlich zu rendern. Die
 *  isLocal-Flag entscheidet ueber Routing (server-Library-Screen vs.
 *  LocalLibraryScreen) und das Badge auf der Kachel. */
data class LibraryDisplay(
    val id: Int,
    val name: String,
    val kind: String,
    val isLocal: Boolean
)

data class HomeState(
    val isLoading: Boolean = true,
    val homeData: HomeResponse? = null,
    val libraries: List<Library> = emptyList(),
    val errorMessage: String? = null,
    val username: String? = null,
    val baseUrl: String = "",
    // Offline-Filter (gespiegelt aus DataStore): wenn an, Home-Strips
    // ausgeblendet und Library-Liste auf Libs mit Downloads gefiltert.
    val offlineOnly: Boolean = false,
    val libraryIdsWithDownloads: Set<Int> = emptySet(),
    // Library-Liste aus dem Offline-Repository (Room-Cache + Downloads-Filter).
    // Wird nur im offlineOnly-Mode gerendert.
    val offlineLibraries: List<Library> = emptyList(),
    // Lokale Bibliotheken (on-device via SAF). Werden im HomeScreen
    // zusammen mit den Server-Libs als LibraryDisplay angezeigt.
    val localLibraries: List<com.goldfish.android.data.local.LocalLibraryEntity> = emptyList(),
    // Zusammengelegte Server-Bibliotheken (aus DataStore)
    val mergedServerLibraryIds: Set<Int> = emptySet(),
    val mergedLocalLibraryIds: Set<Int> = emptySet()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val downloadRepository: DownloadRepository,
    private val offlineRepository: OfflineRepository,
    private val imageCache: com.goldfish.android.data.cache.ImageCache,
    private val localLibraryRepository: LocalLibraryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update {
                    it.copy(
                        baseUrl = settings.serverUrl,
                        offlineOnly = settings.offlineOnly,
                        mergedServerLibraryIds = settings.mergedServerLibraryIds,
                        mergedLocalLibraryIds = settings.mergedLocalLibraryIds
                    )
                }
            }
        }

        viewModelScope.launch {
            authRepository.authStatus.collect { status ->
                _state.update { it.copy(username = status?.username) }
            }
        }

        viewModelScope.launch {
            downloadRepository.getLibraryIdsWithDownloads().collect { ids ->
                _state.update { it.copy(libraryIdsWithDownloads = ids.toSet()) }
            }
        }

        // Im Offline-Mode bedient sich die UI nicht aus state.libraries
        // (das ist die Server-Liste), sondern aus offlineRepository.libraries().
        // Wir spiegeln den Offline-Stream parallel in state, damit HomeScreen
        // im offlineOnly-Zweig einfach state.offlineLibraries lesen kann.
        viewModelScope.launch {
            offlineRepository.libraries().collect { libs ->
                _state.update { it.copy(offlineLibraries = libs) }
            }
        }

        // Lokale (on-device) Libraries observen. User-Filter passiert
        // CLIENT-SEITIG beim Rendering (HomeScreen liest authStatus und
        // filtert state.localLibraries auf ownerUsername=NULL|currentUser).
        // Der vorherige flatMapLatest-Filter hat den Admin in einigen
        // Edge-Cases ausgesperrt (authStatus emit-Timing) — daher hier
        // schlicht alle holen, Filter weiter unten.
        viewModelScope.launch {
            localLibraryRepository.observeLibraries().collect { libs ->
                _state.update { it.copy(localLibraries = libs) }
            }
        }

        // Auf Item-Mutationen (watched/favorite/resume) reagieren — die Home-
        // Strips „Fortsetzen" + „Als naechstes" muessen sonst stale Items
        // weiter zeigen, obwohl der User sie schon gesehen markiert hat.
        // Debounce 300ms damit Bulk-Aktionen nur einen Reload ausloesen.
        viewModelScope.launch {
            itemRepository.itemUpdated
                .debounce(300)
                .collect { loadData() }
        }

        loadData()
    }

    /** Server-Libs (oder offline-Cache) + lokale Libs in einer einheitlichen
     *  Liste. HomeScreen rendert daraus die Library-Chips. */
    fun displayLibraries(): List<LibraryDisplay> {
        val st = _state.value
        val server = if (st.offlineOnly) st.offlineLibraries else st.libraries
        val serverDisplay = server.map { LibraryDisplay(it.id, it.name, it.kind, isLocal = false) }
        // Privacy-Filter: lokale Libs NUR anzeigen, wenn sie dem aktuellen User
        // WIRKLICH gehoeren (strict). Bug 2026-09-02: die vorherige Version hat
        // ownerUsername=NULL fuer JEDEN User als "gehoert mir" durchgelassen —
        // gedacht als kurzes Uebergangsfenster bis zum Claim, aber der Claim
        // wurde nirgends ausgeloest (siehe authStatus-Collector oben), NULL-Libs
        // blieben also fuer immer fuer ALLE User sichtbar. Ein unclaimed Library
        // ist jetzt fuer NIEMANDEN sichtbar, bis sie geclaimt ist (siehe oben) —
        // sicherer Default als "sichtbar fuer alle".
        val currentUser = authRepository.authStatus.value
            ?.takeIf { it.isAuthenticated }?.username
        val localDisplay = st.localLibraries
            .filter { it.ownerUsername != null && it.ownerUsername == currentUser }
            .map { LibraryDisplay(it.id, it.name, it.kind, isLocal = true) }
        return serverDisplay + localDisplay
    }

    /** Zusammengelegte Server-Lib-IDs als sortierte Liste (für Route/Anzeige). */
    fun mergedLibraryIds(): List<Int> = _state.value.mergedServerLibraryIds.sorted()

    /** Zusammengelegte lokale Lib-IDs als sortierte Liste. */
    fun mergedLocalLibraryIds(): List<Int> = _state.value.mergedLocalLibraryIds.sorted()

    /** Toggle persistieren — Flow oben pickt es auf und aktualisiert state.offlineOnly. */
    fun setOfflineOnly(value: Boolean) {
        viewModelScope.launch {
            settingsDataStore.saveOfflineOnly(value)
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }

            // Wir versuchen IMMER zu laden — auch bei aktivem offlineOnly.
            // Grund: nur durch erfolgreiche Server-Calls bekommen Library-
            // Cache + Backfill ihre Daten. Try/catch im Repository fangen
            // UnknownHostException ohne propagieren ab. Bei offlineOnly
            // rendert die UI eh nur state.offlineLibraries — die Server-
            // Antwort wird also nicht direkt angezeigt, dient nur dazu,
            // den Room-Cache aktuell zu halten.

            val authStatus = authRepository.checkAuthStatus()
            _state.update { it.copy(username = authStatus.username) }

            when (val result = itemRepository.getHome()) {
                is Result.Success -> {
                    _state.update { it.copy(isLoading = false, homeData = result.data) }
                }
                is Result.Error -> {
                    _state.update { it.copy(isLoading = false, errorMessage = result.message) }
                }
            }

            when (val result = itemRepository.getLibraries()) {
                is Result.Success -> {
                    _state.update { it.copy(libraries = result.data) }
                }
                is Result.Error -> {}
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun getImageUrl(itemId: Int, metadataId: Int?, libraryKind: String): String {
        val base = _state.value.baseUrl.trimEnd('/')
        val url = if (metadataId != null && (libraryKind == "movies" || libraryKind == "tv")) {
            "$base/api/poster/metadata/$metadataId"
        } else {
            "$base/api/thumb/$itemId"
        }
        return imageCache.preferLocal(url)
    }
}
