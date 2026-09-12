package com.goldfish.android.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Playlist
import com.goldfish.android.data.player.MusicPlayerController
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicPlaylistsState(
    val isLoading: Boolean = true,
    val playlists: List<Playlist> = emptyList(),
    val errorMessage: String? = null,
    val selectedPlaylist: Playlist? = null,
    val playlistItems: List<Item> = emptyList(),
    val isLoadingItems: Boolean = false
)

/** Separat von PlaylistsViewModel (Video-Playlists) — eigener kleiner
 *  Master/Detail-Screen, damit der bestehende funktionierende Video-Pfad
 *  nicht angefasst werden muss. kind="music" wird bei jedem Call explizit
 *  mitgeschickt. */
@HiltViewModel
class MusicPlaylistsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    val playerController: MusicPlayerController
) : ViewModel() {

    private val _state = MutableStateFlow(MusicPlaylistsState())
    val state: StateFlow<MusicPlaylistsState> = _state

    init { loadPlaylists() }

    fun loadPlaylists() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = itemRepository.getPlaylists(kind = "music")) {
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
            when (itemRepository.createPlaylist(name, kind = "music")) {
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

    fun playTrack(index: Int) {
        val tracks = _state.value.playlistItems
        viewModelScope.launch { playerController.playQueue(tracks, index) }
    }

    fun toggleFavorite(item: Item) {
        viewModelScope.launch {
            when (itemRepository.setFavorite(item.id, !item.favorite)) {
                is Result.Success -> _state.update { st ->
                    st.copy(playlistItems = st.playlistItems.map { if (it.id == item.id) it.copy(favorite = !item.favorite) else it })
                }
                is Result.Error -> {}
            }
        }
    }
}
