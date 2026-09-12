package com.goldfish.android.ui.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.model.MusicAlbum
import com.goldfish.android.data.player.MusicPlayerController
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.MusicRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MUSIC_SORT_ARTIST = "artist"
const val MUSIC_SORT_ALBUM = "album"
const val MUSIC_SORT_YEAR = "year"

enum class MusicDisplayMode { GRID, LIST, ALL_TRACKS }

data class MusicLibraryState(
    val isLoading: Boolean = true,
    val library: Library? = null,
    val baseUrl: String = "",
    val albums: List<MusicAlbum> = emptyList(),
    val allTracks: List<Item> = emptyList(),
    val errorMessage: String? = null,
    val displayMode: MusicDisplayMode = MusicDisplayMode.GRID,
    val searchQuery: String = "",
    val sortMode: String = MUSIC_SORT_ARTIST,
    val sortAscending: Boolean = true,
    val recentlyPlayedFirst: Boolean = false,
    val favoritesOnly: Boolean = false,
    val availableGenres: List<String> = emptyList(),
    val selectedGenres: Set<String> = emptySet()
) {
    val filteredAlbums: List<MusicAlbum>
        get() {
            var result = albums
            if (favoritesOnly) result = result.filter { it.favorite == true }
            result = when (sortMode) {
                MUSIC_SORT_ALBUM -> result.sortedBy { it.album.lowercase() }
                MUSIC_SORT_YEAR -> result.sortedBy { it.year ?: 0 }
                else -> result.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }))
            }
            if (!sortAscending) result = result.reversed()
            return result
        }

    val filteredTracks: List<Item>
        get() {
            var result = allTracks
            if (favoritesOnly) result = result.filter { it.favorite }
            if (recentlyPlayedFirst) {
                result = result.sortedWith(compareByDescending(nullsLast()) { it.lastPlayedAt })
            }
            return result
        }
}

@HiltViewModel
class MusicLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val musicRepository: MusicRepository,
    private val settingsDataStore: SettingsDataStore,
    val playerController: MusicPlayerController
) : ViewModel() {

    private val _state = MutableStateFlow(MusicLibraryState())
    val state: StateFlow<MusicLibraryState> = _state

    private var libraryId: Int = -1

    private val prefs by lazy { context.getSharedPreferences("library_view_prefs", Context.MODE_PRIVATE) }
    private fun prefKey(key: String) = "music_${key}_$libraryId"

    fun load(libraryId: Int) {
        this.libraryId = libraryId
        val sort = prefs.getString(prefKey("sort"), MUSIC_SORT_ARTIST) ?: MUSIC_SORT_ARTIST
        val asc = prefs.getBoolean(prefKey("asc"), true)
        val gridMode = prefs.getBoolean(prefKey("grid"), true)
        _state.update {
            it.copy(
                sortMode = sort,
                sortAscending = asc,
                displayMode = if (gridMode) MusicDisplayMode.GRID else MusicDisplayMode.LIST
            )
        }
        viewModelScope.launch {
            val baseUrl = try { settingsDataStore.settings.first().serverUrl } catch (_: Exception) { "" }
            _state.update { it.copy(baseUrl = baseUrl) }
        }
        reload()
        loadGenres()
    }

    private fun persist() {
        val s = _state.value
        prefs.edit()
            .putString(prefKey("sort"), s.sortMode)
            .putBoolean(prefKey("asc"), s.sortAscending)
            .putBoolean(prefKey("grid"), s.displayMode != MusicDisplayMode.LIST)
            .apply()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            val genres = _state.value.selectedGenres.toList().ifEmpty { null }
            when (val r = musicRepository.getAlbums(libraryId, genres)) {
                is Result.Success -> _state.update { it.copy(isLoading = false, albums = r.data) }
                is Result.Error -> _state.update { it.copy(isLoading = false, errorMessage = r.message) }
            }
            if (_state.value.displayMode == MusicDisplayMode.ALL_TRACKS || _state.value.searchQuery.isNotBlank()) {
                loadAllTracks()
            }
        }
    }

    private suspend fun loadAllTracks() {
        val q = _state.value.searchQuery.trim().ifBlank { null }
        val genres = _state.value.selectedGenres.toList().ifEmpty { null }
        val favorite = if (_state.value.favoritesOnly) "yes" else null
        when (val r = itemRepository.getItems(
            libraryId = libraryId,
            sort = if (_state.value.sortMode == MUSIC_SORT_YEAR) MUSIC_SORT_ARTIST else _state.value.sortMode,
            dir = if (_state.value.sortAscending) "asc" else "desc",
            search = q,
            favorite = favorite,
            genre = genres
        )) {
            is Result.Success -> _state.update { it.copy(allTracks = r.data) }
            is Result.Error -> _state.update { it.copy(errorMessage = r.message) }
        }
    }

    private fun loadGenres() {
        viewModelScope.launch {
            when (val r = musicRepository.getGenres(libraryId)) {
                is Result.Success -> _state.update { it.copy(availableGenres = r.data) }
                is Result.Error -> {}
            }
        }
    }

    fun setDisplayMode(mode: MusicDisplayMode) {
        _state.update { it.copy(displayMode = mode) }
        persist()
        if (mode == MusicDisplayMode.ALL_TRACKS) viewModelScope.launch { loadAllTracks() }
    }

    fun setSearchQuery(q: String) {
        _state.update { it.copy(searchQuery = q) }
        viewModelScope.launch { loadAllTracks() }
    }

    fun setSort(mode: String, ascending: Boolean) {
        _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
        persist()
        viewModelScope.launch { loadAllTracks() }
    }

    fun toggleRecentlyPlayedFirst() {
        _state.update { it.copy(recentlyPlayedFirst = !it.recentlyPlayedFirst) }
    }

    fun toggleFavoritesOnly() {
        _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }
        reload()
    }

    fun setSelectedGenres(genres: Set<String>) {
        _state.update { it.copy(selectedGenres = genres) }
        reload()
    }

    fun toggleTrackFavorite(item: Item) {
        viewModelScope.launch {
            when (itemRepository.setFavorite(item.id, !item.favorite)) {
                is Result.Success -> _state.update { st ->
                    st.copy(allTracks = st.allTracks.map { if (it.id == item.id) it.copy(favorite = !item.favorite) else it })
                }
                is Result.Error -> {}
            }
        }
    }

    fun toggleAlbumFavorite(album: MusicAlbum) {
        viewModelScope.launch {
            val newFav = album.favorite != true
            when (musicRepository.setAlbumFavorite(album.id, newFav)) {
                is Result.Success -> _state.update { st ->
                    st.copy(albums = st.albums.map { if (it.id == album.id) it.copy(favorite = newFav) else it })
                }
                is Result.Error -> {}
            }
        }
    }

    fun playAlbum(album: MusicAlbum) {
        viewModelScope.launch {
            when (val r = musicRepository.getAlbumDetail(album.id)) {
                is Result.Success -> playerController.playAlbum(r.data.tracks)
                is Result.Error -> {}
            }
        }
    }

    fun playTrack(item: Item, queue: List<Item>) {
        val idx = queue.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: 0
        viewModelScope.launch { playerController.playQueue(queue, idx) }
    }

    fun shufflePlayLibrary() {
        viewModelScope.launch { playerController.shufflePlayLibrary(libraryId) }
    }
}
