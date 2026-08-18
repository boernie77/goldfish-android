package com.goldfish.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.model.Item
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
    val baseUrl: String = ""
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val offlineRepository: OfflineRepository,
    private val localLibraryRepository: LocalLibraryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val authRepository: com.goldfish.android.data.repository.AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // Debounce: nur die letzte 250ms-Eingabe wird tatsaechlich gesucht
    private var searchJob: Job? = null

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
        if (q.isBlank()) {
            _state.update {
                it.copy(
                    isSearching = false,
                    serverResults = emptyList(),
                    offlineResults = emptyList(),
                    localResults = emptyList()
                )
            }
            return
        }
        _state.update { it.copy(isSearching = true) }
        val offlineOnly = _state.value.offlineOnly

        // Server-Suche nur wenn online-Modus
        val server = if (offlineOnly) emptyList() else {
            when (val r = itemRepository.searchAcrossLibraries(q)) {
                is Result.Success -> r.data
                is Result.Error -> emptyList()
            }
        }
        val offline = try { offlineRepository.searchDownloads(q) } catch (_: Exception) { emptyList() }
        val currentUser = authRepository.authStatus.value?.username
            ?.takeIf { it.isNotBlank() && authRepository.authStatus.value?.isAuthenticated == true }
        val local = try { localLibraryRepository.searchAllItems(q, currentUser) } catch (_: Exception) { emptyList() }

        _state.update {
            it.copy(
                isSearching = false,
                serverResults = server,
                offlineResults = offline,
                localResults = local
            )
        }
    }
}
