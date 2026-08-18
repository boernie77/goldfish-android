package com.goldfish.android.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.MediaCollection
import com.goldfish.android.data.model.MediaCollectionPart
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionsState(
    val isLoading: Boolean = true,
    val collections: List<MediaCollection> = emptyList(),
    val errorMessage: String? = null,
    val baseUrl: String = "",
    // Detail view
    val selectedCollection: MediaCollection? = null,
    val collectionParts: List<MediaCollectionPart> = emptyList(),
    val isLoadingParts: Boolean = false
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionsState())
    val state: StateFlow<CollectionsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update { it.copy(baseUrl = settings.serverUrl) }
            }
        }
        loadCollections()
    }

    fun loadCollections() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = itemRepository.getCollections()) {
                is Result.Success -> _state.update { it.copy(isLoading = false, collections = result.data) }
                is Result.Error -> _state.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
        }
    }

    fun openCollection(collection: MediaCollection) {
        _state.update { it.copy(selectedCollection = collection, collectionParts = emptyList(), isLoadingParts = true) }
        viewModelScope.launch {
            when (val result = itemRepository.getCollectionItems(collection.id)) {
                is Result.Success -> _state.update { it.copy(isLoadingParts = false, collectionParts = result.data) }
                is Result.Error -> _state.update { it.copy(isLoadingParts = false) }
            }
        }
    }

    fun closeCollection() {
        _state.update { it.copy(selectedCollection = null, collectionParts = emptyList()) }
    }

    fun getCollectionPosterUrl(collection: MediaCollection): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return "$base/api/poster/collection/${collection.id}"
    }

    fun getItemPosterUrl(itemId: Int): String {
        val base = _state.value.baseUrl.trimEnd('/')
        return "$base/api/thumb/$itemId"
    }
}
