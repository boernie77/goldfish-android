package com.goldfish.android.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Playlist
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsState(
    val isLoading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val errorMessage: String? = null,
    val baseUrl: String = "",
    // Detail view
    val selectedPlaylist: Playlist? = null,
    val playlistItems: List<Item> = emptyList(),
    val isLoadingItems: Boolean = false,
    val isAdmin: Boolean = false
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistsState())
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update { it.copy(baseUrl = settings.serverUrl) }
            }
        }
        loadPlaylists()
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = itemRepository.getPlaylists()) {
                is Result.Success -> _state.update { it.copy(isLoading = false, playlists = result.data) }
                is Result.Error -> _state.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _state.update { it.copy(selectedPlaylist = playlist, playlistItems = emptyList(), isLoadingItems = true) }
        viewModelScope.launch {
            when (val result = itemRepository.getPlaylistItems(playlist.id)) {
                is Result.Success -> _state.update { it.copy(isLoadingItems = false, playlistItems = result.data) }
                is Result.Error -> _state.update { it.copy(isLoadingItems = false) }
            }
        }
    }

    fun closePlaylist() {
        _state.update { it.copy(selectedPlaylist = null, playlistItems = emptyList()) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            when (val result = itemRepository.createPlaylist(name)) {
                is Result.Success -> loadPlaylists()
                is Result.Error -> {}
            }
        }
    }

    fun deletePlaylist(playlistId: Int) {
        viewModelScope.launch {
            itemRepository.deletePlaylist(playlistId)
            loadPlaylists()
        }
    }

    fun getItemImageUrl(item: Item): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return if (item.metadataId != null) "$base/api/poster/metadata/${item.metadataId}"
        else "$base/api/thumb/${item.id}"
    }
}
