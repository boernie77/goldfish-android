package com.goldfish.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val isCheckingAuth: Boolean = true,
    val isAuthenticated: Boolean = false,
    // Wenn keine valide Server-URL gesetzt ist, wollen wir den User direkt
    // zum Settings-Screen schicken — sonst läuft jeder API-Call gegen "/"
    // und der Login-Knopf wirft "Expected URL scheme …".
    val needsServerUrl: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)

            if (!apiClientProvider.isConfigured()) {
                // Server-URL noch nicht eingetragen → direkt zu Settings
                _state.value = MainState(
                    isCheckingAuth = false,
                    isAuthenticated = false,
                    needsServerUrl = true
                )
                return@launch
            }

            if (authRepository.hasSavedSession()) {
                val status = authRepository.checkAuthStatus()
                _state.value = MainState(isCheckingAuth = false, isAuthenticated = status.isAuthenticated)
            } else {
                _state.value = MainState(isCheckingAuth = false, isAuthenticated = false)
            }
        }
    }
}
