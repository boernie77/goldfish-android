package com.goldfish.android.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import com.goldfish.android.util.groupVariants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Person-Filter-Ansicht: "alle Videos mit Schauspieler X" — Pendant zum
 *  Browser-openPersonView/renderPersonFilterBranch (grid.js/player.js).
 *  Zeigt eine flache, library-uebergreifende Liste; Server filtert bereits
 *  auf ACL/FSK des Kontos (GET /api/items?personId=). Erreichbar sowohl vom
 *  neuen Schauspieler-Suchresultat (SearchScreen) als auch — nachgezogen —
 *  vom bisher NICHT klickbaren Cast-Strip im Detail-Screen (DetailScreen.kt
 *  CastMemberCard hatte bis dahin keinerlei Navigation). */
data class PersonFilterState(
    val isLoading: Boolean = true,
    val personName: String = "",
    val items: List<Item> = emptyList(),
    val errorMessage: String? = null,
    val baseUrl: String = ""
)

@HiltViewModel
class PersonFilterViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _state = MutableStateFlow(PersonFilterState())
    val state: StateFlow<PersonFilterState> = _state.asStateFlow()

    fun load(tmdbId: Long, name: String) {
        _state.update { it.copy(isLoading = true, personName = name, errorMessage = null) }
        viewModelScope.launch {
            val baseUrl = settingsDataStore.settings.first().serverUrl
            when (val result = itemRepository.getItems(personId = tmdbId, sort = "title", dir = "asc")) {
                is Result.Success -> {
                    val items = groupVariants(result.data)
                    _state.update { it.copy(isLoading = false, items = items, baseUrl = baseUrl) }
                }
                is Result.Error -> {
                    _state.update { it.copy(isLoading = false, errorMessage = result.message, baseUrl = baseUrl) }
                }
            }
        }
    }

    fun getImageUrl(item: Item): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return if (item.metadataId != null) "$base/api/poster/metadata/${item.metadataId}"
        else "$base/api/thumb/${item.id}"
    }
}
