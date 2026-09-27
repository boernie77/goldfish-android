package com.goldfish.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.model.OtherUser
import com.goldfish.android.data.model.WatchLink
import com.goldfish.android.data.repository.Result
import com.goldfish.android.data.repository.WatchLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WatchLinkState(
    val links: List<WatchLink> = emptyList(),
    /** Konten, mit denen noch KEINE Verknüpfung/Anfrage besteht. */
    val availableUsers: List<OtherUser> = emptyList(),
    val loaded: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
)

/** Gesehen-Sync-Seite — Aufbau und Texte wie `WatchLinkSettingsView.swift`
 *  (Apple-App) und der Gesehen-Sync-Dialog im Browser. */
@HiltViewModel
class WatchLinkViewModel @Inject constructor(
    private val repository: WatchLinkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WatchLinkState())
    val state: StateFlow<WatchLinkState> = _state.asStateFlow()

    init { viewModelScope.launch { reload() } }

    private suspend fun reload() {
        val linksDeferred = viewModelScope.async { repository.getWatchLinks() }
        val usersDeferred = viewModelScope.async { repository.getOtherUsers() }
        val linksResult = linksDeferred.await()
        val usersResult = usersDeferred.await()
        val links = (linksResult as? Result.Success)?.data ?: emptyList()
        val users = (usersResult as? Result.Success)?.data ?: emptyList()
        // Wie im Browser: Konten mit bestehender Verknüpfung oder offener
        // Anfrage nicht erneut anbieten.
        val linkedIds = links.map { it.partnerId }.toSet()
        val loadError = (linksResult as? Result.Error)?.message
            ?: (usersResult as? Result.Error)?.message
        _state.update {
            it.copy(
                links = links,
                availableUsers = users.filter { u -> u.id !in linkedIds },
                loaded = true,
                errorMessage = loadError ?: it.errorMessage
            )
        }
    }

    fun sendRequest(username: String) = runAction { repository.request(username) }

    fun confirm(link: WatchLink) = runAction { repository.confirm(link.partnerId) }

    /** Trennen (aktiv/ausgehend) bzw. Ablehnen (eingehende Anfrage). */
    fun unlink(link: WatchLink) = runAction { repository.unlink(link.partnerId) }

    /** Aktion ausführen, Server-Fehler anzeigen, danach immer neu laden. */
    private fun runAction(action: suspend () -> Result<Unit>) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            val result = action()
            if (result is Result.Error) {
                _state.update { it.copy(errorMessage = result.message) }
            }
            reload()
            _state.update { it.copy(isBusy = false) }
        }
    }
}
