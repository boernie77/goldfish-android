package com.goldfish.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.player.MusicPlayerController
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.LocalLibraryRepository
import com.goldfish.android.data.repository.OfflineRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-State der globalen Suche. Sections sind unabhaengig — eine kann
 *  laden, eine fertig, eine leer sein. */
data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val serverResults: List<Item> = emptyList(),
    val offlineResults: List<Item> = emptyList(),
    val localResults: List<LocalItemEntity> = emptyList(),
    val offlineOnly: Boolean = false,
    val baseUrl: String = "",
    // Anzahl zusaetzlicher Treffer, die searchMode=fuzzy liefern wuerde
    // (X-Fuzzy-Extra-Count-Header) — Grundlage fuer den "N weitere
    // Treffer"-Button. 0/null nach dem Nachladen (Button verschwindet).
    val fuzzyExtraCount: Int = 0,
    val isLoadingFuzzy: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val offlineRepository: OfflineRepository,
    private val localLibraryRepository: LocalLibraryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val authRepository: com.goldfish.android.data.repository.AuthRepository,
    val musicPlayerController: MusicPlayerController
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Musik-Suchtreffer werden IMMER als einzelner Titel abgespielt (flache
     *  Treffer-Liste, nie zu Alben gebuendelt — exakt das Bug-Muster, das
     *  gleichzeitig im Browser/iOS gefixt wurde, siehe Server-CLAUDE.md). */
    fun playMusicSearchResult(item: Item) {
        viewModelScope.launch { musicPlayerController.playQueue(listOf(item), 0) }
    }

    // Debounce: nur die letzte 250ms-Eingabe wird tatsaechlich gesucht
    private var searchJob: Job? = null

    // Query, zu der das aktuelle fuzzyExtraCount-Angebot gehoert (wie
    // state.fuzzySearchParams im Browser) — der "N weitere Treffer"-Button
    // laedt IMMER diese Query nach, nicht state.query (kann sich zwischen
    // Suchergebnis und Klick durch weitere Eingabe schon geaendert haben).
    private var fuzzySearchQuery: String? = null

    init {
        viewModelScope.launch {
            val s = settingsDataStore.settings.first()
            _state.update { it.copy(baseUrl = s.serverUrl, offlineOnly = s.offlineOnly) }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            executeSearch(q.trim())
        }
    }

    private suspend fun executeSearch(q: String) {
        fuzzySearchQuery = null
        if (q.isBlank()) {
            _state.update {
                it.copy(
                    isSearching = false,
                    serverResults = emptyList(),
                    offlineResults = emptyList(),
                    localResults = emptyList(),
                    fuzzyExtraCount = 0
                )
            }
            return
        }
        _state.update { it.copy(isSearching = true, fuzzyExtraCount = 0) }
        val offlineOnly = _state.value.offlineOnly

        // Server-Suche nur wenn online-Modus
        var fuzzyExtraCount = 0
        val server = if (offlineOnly) emptyList() else {
            when (val r = itemRepository.searchAcrossLibraries(q)) {
                is Result.Success -> {
                    fuzzyExtraCount = r.data.fuzzyExtraCount
                    r.data.items
                }
                is Result.Error -> emptyList()
            }
        }
        if (fuzzyExtraCount > 0) fuzzySearchQuery = q
        val offline = try { offlineRepository.searchDownloads(q) } catch (_: Exception) { emptyList() }
        val currentUser = authRepository.authStatus.value?.username
            ?.takeIf { it.isNotBlank() && authRepository.authStatus.value?.isAuthenticated == true }
        val local = try { localLibraryRepository.searchAllItems(q, currentUser) } catch (_: Exception) { emptyList() }

        _state.update {
            it.copy(
                isSearching = false,
                serverResults = server,
                offlineResults = offline,
                localResults = local,
                fuzzyExtraCount = fuzzyExtraCount
            )
        }
    }

    /** "🔍 N weitere Treffer"-Button: laedt searchMode=fuzzy fuer dieselbe
     *  Query nach und haengt nur neue (noch nicht angezeigte) Treffer ans
     *  Ende der Server-Liste an — wie loadMoreFuzzySearchResults() im
     *  Browser (grid.js). Button verschwindet danach (fuzzyExtraCount=0). */
    fun loadMoreFuzzyResults() {
        val q = fuzzySearchQuery ?: return
        if (_state.value.isLoadingFuzzy) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingFuzzy = true) }
            val known = _state.value.serverResults.map { it.id }.toSet()
            val extra = when (val r = itemRepository.searchAcrossLibrariesFuzzy(q)) {
                is Result.Success -> r.data.filter { it.id !in known }
                is Result.Error -> emptyList()
            }
            fuzzySearchQuery = null
            _state.update {
                it.copy(
                    isLoadingFuzzy = false,
                    serverResults = it.serverResults + extra,
                    fuzzyExtraCount = 0
                )
            }
        }
    }
}
