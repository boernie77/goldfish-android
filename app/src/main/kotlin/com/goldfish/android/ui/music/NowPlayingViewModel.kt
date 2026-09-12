package com.goldfish.android.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.player.MusicPlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Duenner Wrapper um MusicPlayerController fuer Now-Playing/Mini-Player —
 *  reine Delegation, kein eigener State (das Singleton IST der State). */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    val playerController: MusicPlayerController,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: String get() = _baseUrl.value

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { _baseUrl.value = it.serverUrl }
        }
    }

    fun togglePlayPause() { viewModelScope.launch { playerController.togglePlayPause() } }
    fun skipNext() { viewModelScope.launch { playerController.skipNext() } }
    fun skipPrevious() { viewModelScope.launch { playerController.skipPrevious() } }
    fun toggleShuffle() { viewModelScope.launch { playerController.toggleShuffle() } }
    fun toggleRepeatMode() { viewModelScope.launch { playerController.toggleRepeatMode() } }
    fun seekTo(posMs: Long) { viewModelScope.launch { playerController.seekTo(posMs) } }
    fun skipToQueueIndex(index: Int) { viewModelScope.launch { playerController.skipToQueueIndex(index) } }
}
