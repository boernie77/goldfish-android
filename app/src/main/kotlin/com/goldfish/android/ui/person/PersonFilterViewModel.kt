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

/** Eine Serien-Sammelkachel im Person-Filter: alle Episoden EINER Show, in
 *  denen die Person vorkommt, gebuendelt zu einer Kachel (User-Wunsch
 *  2026-09-20, Pendant zum Browser renderPersonShowCard/renderPersonFilterBranch
 *  in grid.js/cards.js). Gruppierung ueber metadata.parentId, Fallback (falls
 *  eine Episode keine TMDB-Show-Zuordnung hat) ueber libraryId + erstes
 *  relPath-Segment — identisch zur Browser-Logik. */
data class PersonShowGroup(
    val libraryId: Int,
    val folder: String,
    val showParentId: Int?,
    val fallbackThumbItemId: Int,
    val episodes: List<Item>
) {
    val count: Int get() = episodes.size
}

/** Person-Filter-Ansicht: "alle Videos mit Schauspieler X" — Pendant zum
 *  Browser-openPersonView/renderPersonFilterBranch (grid.js/player.js).
 *  Zeigt eine flache, library-uebergreifende Liste; Server filtert bereits
 *  auf ACL/FSK des Kontos (GET /api/items?personId=). Erreichbar sowohl vom
 *  neuen Schauspieler-Suchresultat (SearchScreen) als auch — nachgezogen —
 *  vom bisher NICHT klickbaren Cast-Strip im Detail-Screen (DetailScreen.kt
 *  CastMemberCard hatte bis dahin keinerlei Navigation).
 *
 *  Episoden werden NICHT einzeln gezeigt (User-Zitat 2026-09-20: "die
 *  Treffer in den Serien zeigen nicht die Serie, [...] sondern wieder die
 *  einzelnen Folgen. Es soll so sein, dass nur die Serie gezeigt wird") —
 *  stattdessen zu [PersonShowGroup]s gebuendelt. Ein Klick auf eine
 *  Sammelkachel oeffnet [openShowGroup]/[closedShowGroup] eine reine
 *  In-Memory-Liste (kein neuer Server-Call, die Episoden liegen schon vor). */
data class PersonFilterState(
    val isLoading: Boolean = true,
    val personName: String = "",
    val movies: List<Item> = emptyList(),
    val showGroups: List<PersonShowGroup> = emptyList(),
    // Aktuell "aufgeklappte" Serien-Sammelkachel — wenn gesetzt, zeigt der
    // Screen NUR deren Episoden (Sub-View, kein neuer Request).
    val openShowGroup: PersonShowGroup? = null,
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
        _state.update {
            it.copy(isLoading = true, personName = name, errorMessage = null, openShowGroup = null)
        }
        viewModelScope.launch {
            val baseUrl = settingsDataStore.settings.first().serverUrl
            when (val result = itemRepository.getItems(personId = tmdbId, sort = "title", dir = "asc")) {
                is Result.Success -> {
                    val merged = groupVariants(result.data)
                    val movies = merged.filter { it.metadata?.tmdbType == "movie" }
                    val episodes = merged.filter { it.metadata?.tmdbType == "episode" }

                    // Episoden → pro Show (Parent-Show) eine Sammelkachel. Gruppen-
                    // Schluessel: metadata.parentId, Fallback libraryId+relPath[0]
                    // (analog Browser grid.js renderPersonFilterBranch).
                    val groups = LinkedHashMap<String, MutableList<Item>>()
                    val groupMeta = HashMap<String, Pair<Int, String>>() // key -> (libraryId, folder)
                    for (ep in episodes) {
                        val folder = ep.relPath?.substringBefore('/', "") ?: ""
                        val showParentId = ep.metadata?.parentId
                        val key = if (showParentId != null && showParentId > 0) "p$showParentId"
                        else "f${ep.libraryId}|$folder"
                        groups.getOrPut(key) { mutableListOf() }.add(ep)
                        groupMeta.putIfAbsent(key, ep.libraryId to folder)
                    }
                    val showGroups = groups.entries.map { (key, eps) ->
                        val (libId, folder) = groupMeta.getValue(key)
                        val showParentId = eps.firstOrNull()?.metadata?.parentId?.takeIf { it > 0 }
                        PersonShowGroup(
                            libraryId = libId,
                            folder = folder,
                            showParentId = showParentId,
                            fallbackThumbItemId = eps.first().id,
                            episodes = eps
                        )
                    }

                    _state.update {
                        it.copy(
                            isLoading = false,
                            movies = movies,
                            showGroups = showGroups,
                            baseUrl = baseUrl
                        )
                    }
                }
                is Result.Error -> {
                    _state.update { it.copy(isLoading = false, errorMessage = result.message, baseUrl = baseUrl) }
                }
            }
        }
    }

    /** Oeffnet die Sub-View einer Serien-Sammelkachel — rein clientseitig,
     *  die Episoden liegen bereits in state.showGroups. */
    fun openShowGroup(group: PersonShowGroup) {
        _state.update { it.copy(openShowGroup = group) }
    }

    /** Schliesst die Sub-View wieder (zurueck zur Filme/Serien-Uebersicht). */
    fun closeShowGroup() {
        _state.update { it.copy(openShowGroup = null) }
    }

    fun getImageUrl(item: Item): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return if (item.metadataId != null) "$base/api/poster/metadata/${item.metadataId}"
        else "$base/api/thumb/${item.id}"
    }

    fun getShowGroupImageUrl(group: PersonShowGroup): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return if (group.showParentId != null) "$base/api/poster/metadata/${group.showParentId}"
        else "$base/api/thumb/${group.fallbackThumbItemId}"
    }
}
