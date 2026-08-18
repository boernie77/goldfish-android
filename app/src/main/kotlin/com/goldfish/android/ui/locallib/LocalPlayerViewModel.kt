package com.goldfish.android.ui.locallib

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.repository.LocalLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalPlayerState(
    val item: LocalItemEntity? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class LocalPlayerViewModel @Inject constructor(
    private val repository: LocalLibraryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LocalPlayerState())
    val state: StateFlow<LocalPlayerState> = _state.asStateFlow()

    fun load(itemId: Int) {
        viewModelScope.launch {
            val item = repository.getItem(itemId)
            if (item != null) {
                // "Zuletzt gespielt" lokal stempeln (epoch ms) — gezieltes
                // Spalten-Update (clobber-sicher) statt Ganzzeilen-Write.
                val ts = System.currentTimeMillis()
                repository.markLocalPlayed(itemId, ts)
                _state.update { it.copy(item = item.copy(lastPlayedAt = ts)) }
            } else {
                _state.update { it.copy(item = null) }
            }
        }
    }

    fun setPlaybackError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun saveResume(positionSec: Double, durationSec: Double) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            if (durationSec > 0 && positionSec / durationSec >= 0.9) {
                repository.updateItem(item.copy(watched = true, resumePosSec = 0.0))
            } else {
                repository.updateItem(item.copy(resumePosSec = positionSec))
            }
        }
    }
}
