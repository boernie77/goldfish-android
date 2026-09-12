package com.goldfish.android.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.player.MusicPlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
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
}
