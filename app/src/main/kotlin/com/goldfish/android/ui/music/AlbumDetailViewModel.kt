package com.goldfish.android.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.model.AlbumDetail
import com.goldfish.android.data.player.MusicPlayerController
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.MusicRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailState(
    val isLoading: Boolean = true,
    val detail: AlbumDetail? = null,
    val baseUrl: String = "",
    val errorMessage: String? = null,
    val isAdmin: Boolean = false
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val itemRepository: ItemRepository,
    private val settingsDataStore: SettingsDataStore,
    private val authRepository: AuthRepository,
    val playerController: MusicPlayerController
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailState())
    val state: StateFlow<AlbumDetailState> = _state

    private var albumId: Int = -1

    fun load(albumId: Int) {
        this.albumId = albumId
        viewModelScope.launch {
            val baseUrl = try { settingsDataStore.settings.first().serverUrl } catch (_: Exception) { "" }
            val admin = authRepository.getCurrentStatus()?.isAdmin ?: false
            _state.update { it.copy(baseUrl = baseUrl, isLoading = true, errorMessage = null, isAdmin = admin) }
            when (val r = musicRepository.getAlbumDetail(albumId)) {
                is Result.Success -> _state.update { it.copy(isLoading = false, detail = r.data) }
                is Result.Error -> _state.update { it.copy(isLoading = false, errorMessage = r.message) }
            }
        }
    }

    fun playAll() {
        val tracks = _state.value.detail?.tracks ?: return
        viewModelScope.launch { playerController.playAlbum(tracks, 0) }
    }

    fun playTrack(index: Int) {
        val tracks = _state.value.detail?.tracks ?: return
        viewModelScope.launch { playerController.playAlbum(tracks, index) }
    }

    fun shuffleAlbum() {
        val tracks = _state.value.detail?.tracks ?: return
        viewModelScope.launch { playerController.playQueue(tracks.shuffled(), 0, shuffle = true) }
    }

    fun toggleAlbumFavorite() {
        val album = _state.value.detail?.album ?: return
        viewModelScope.launch {
            val newFav = album.favorite != true
            when (musicRepository.setAlbumFavorite(album.id, newFav)) {
                is Result.Success -> _state.update { st ->
                    st.copy(detail = st.detail?.copy(album = st.detail.album.copy(favorite = newFav)))
                }
                is Result.Error -> {}
            }
        }
    }

    fun toggleTrackFavorite(itemId: Int, currentFavorite: Boolean) {
        viewModelScope.launch {
            when (itemRepository.setFavorite(itemId, !currentFavorite)) {
                is Result.Success -> _state.update { st ->
                    st.copy(detail = st.detail?.copy(
                        tracks = st.detail.tracks.map { if (it.id == itemId) it.copy(favorite = !currentFavorite) else it }
                    ))
                }
                is Result.Error -> {}
            }
        }
    }

    fun saveAlbumMetadata(artist: String?, album: String?, genre: String?, year: Int?) {
        val id = _state.value.detail?.album?.id ?: return
        viewModelScope.launch {
            when (musicRepository.updateAlbumMetadata(id, artist, album, genre, year)) {
                is Result.Success -> load(id)
                is Result.Error -> {}
            }
        }
    }

    fun saveTrackMetadata(itemId: Int, title: String?, artist: String?, album: String?, trackNo: Int?, genre: String?) {
        viewModelScope.launch {
            when (musicRepository.updateMusicItemMetadata(itemId, title, artist, album, trackNo, genre)) {
                is Result.Success -> load(albumId)
                is Result.Error -> {}
            }
        }
    }
}
