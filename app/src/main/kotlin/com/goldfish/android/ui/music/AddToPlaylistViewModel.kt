package com.goldfish.android.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.model.Playlist
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddToPlaylistState(
    val isLoading: Boolean = true,
    val playlists: List<Playlist> = emptyList()
)

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val itemRepository: ItemRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddToPlaylistState())
    val state: StateFlow<AddToPlaylistState> = _state

    init {
        viewModelScope.launch {
            when (val r = itemRepository.getPlaylists(kind = "music")) {
                is Result.Success -> _state.update { it.copy(isLoading = false, playlists = r.data) }
                is Result.Error -> _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addToPlaylist(playlistId: Int, itemId: Int, onResult: (added: Boolean) -> Unit) {
        viewModelScope.launch {
            when (val r = itemRepository.addPlaylistItem(playlistId, itemId)) {
                is Result.Success -> onResult(r.data)
                is Result.Error -> {}
            }
        }
    }

    fun createAndAdd(name: String, itemId: Int, onResult: () -> Unit) {
        viewModelScope.launch {
            when (val r = itemRepository.createPlaylist(name, kind = "music")) {
                is Result.Success -> {
                    itemRepository.addPlaylistItem(r.data.id, itemId)
                    onResult()
                }
                is Result.Error -> {}
            }
        }
    }
}
