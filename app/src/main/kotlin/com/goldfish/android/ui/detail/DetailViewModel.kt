package com.goldfish.android.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.CastMember
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.repository.DownloadRepository
import com.goldfish.android.data.repository.DownloadResult
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DetailState(
    val isLoading: Boolean = true,
    val item: Item? = null,
    val errorMessage: String? = null,
    val baseUrl: String = "",
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,
    val toastMessage: String? = null,
    val cast: List<CastMember> = emptyList(),
    // Geschwister-Files mit gleicher metadata_id (z.B. 1080p + 4K). Kommt vom
    // /api/items/{id}/variants-Endpoint, auch das aktuelle Item ist enthalten.
    // > 1 Eintrag → Varianten-Auswahl wird im Detail-Screen angezeigt.
    val variants: List<Item> = emptyList()
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val downloadRepository: DownloadRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val offlineRepository: com.goldfish.android.data.repository.OfflineRepository,
    private val imageCache: com.goldfish.android.data.cache.ImageCache
) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var downloadDir: File = downloadRepository.getDefaultDownloadDir()
    private var downloadTreeUri: android.net.Uri? = null

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update { it.copy(baseUrl = settings.serverUrl) }
                downloadDir = if (settings.downloadDir.isNotBlank()) {
                    File(settings.downloadDir)
                } else {
                    downloadRepository.getDefaultDownloadDir()
                }
                downloadTreeUri = settings.downloadTreeUri.takeIf { it.isNotBlank() }
                    ?.let { android.net.Uri.parse(it) }
            }
        }
        // Active-Downloads-Stream beobachten — bleibt synchron, auch wenn Download
        // außerhalb des ViewModels läuft (App-Scope-Job)
        viewModelScope.launch {
            downloadRepository.activeDownloads.collect { active ->
                val itemId = _state.value.item?.id ?: return@collect
                val pct = active[itemId]
                if (pct != null) {
                    _state.update { it.copy(isDownloading = true, downloadProgress = if (pct.isNaN()) 0f else pct) }
                } else if (_state.value.isDownloading) {
                    _state.update { it.copy(isDownloading = false) }
                }
            }
        }
        viewModelScope.launch {
            downloadRepository.lastResult.collect { result ->
                if (result == null) return@collect
                val (id, r) = result
                if (id != _state.value.item?.id) return@collect
                when (r) {
                    is DownloadResult.Success -> _state.update {
                        it.copy(isDownloaded = true, downloadProgress = 1f, toastMessage = "Download abgeschlossen")
                    }
                    is DownloadResult.Error -> _state.update {
                        it.copy(downloadError = r.message, toastMessage = "Download fehlgeschlagen: ${r.message}")
                    }
                    else -> {}
                }
                downloadRepository.consumeLastResult()
            }
        }
        // Item-Update-Signale beobachten — z.B. nach Player-Close mit neuer
        // Resume-Position. Reload nur wenn das aktuelle Item betroffen ist.
        viewModelScope.launch {
            itemRepository.itemUpdated.collect { id ->
                if (id == _state.value.item?.id) {
                    load(id)
                }
            }
        }
    }

    fun load(itemId: Int) {
        viewModelScope.launch {
            // Wichtig: Cache umgehen — der Player könnte gerade async eine neue
            // Resume-Position gespeichert haben, deren cache.invalidate() noch nicht
            // durchgegangen ist. Sonst zeigt der Resume-Dialog die alte Position.
            itemRepository.invalidateItem(itemId)

            _state.update { it.copy(isLoading = true, errorMessage = null) }

            // Check if already downloaded
            val downloadEntry = downloadRepository.getDownload(itemId)
            _state.update { it.copy(isDownloaded = downloadEntry != null) }

            // Item-Quelle: Server zuerst (try/catch im Repo faengt Netz-
            // Fehler ab), sonst Room-Cache. Damit funktioniert Detail mit
            // UND ohne Netz, ohne Hard-Skip basierend auf offlineOnly
            // (verhindert die "keine Inhalte"-Falle wenn der Toggle
            // persistent an ist).
            val offline = try {
                settingsDataStore.settings.first().offlineOnly
            } catch (_: Exception) { false }
            val serverItem = (itemRepository.getItem(itemId) as? Result.Success)?.data
            val finalItem = if (serverItem != null) {
                val resumePos = try { itemRepository.getResumePosition(itemId) } catch (_: Exception) { 0.0 }
                if (resumePos > 0) serverItem.copy(resumePosSec = resumePos) else serverItem
            } else offlineRepository.item(itemId)
            if (finalItem != null) {
                run {
                    val originalItem = finalItem
                    val item = finalItem
                    android.util.Log.d("GF-Detail",
                        "loaded item id=${item.id} metadataId=${item.metadataId} " +
                        "tmdbType=${item.metadata?.tmdbType} resumePosSec=${item.resumePosSec}")
                    // Variants-Liste behalten, wenn das neue Item Geschwister
                    // der bisherigen Liste ist (Browser-Pattern: Klick auf
                    // Variant im Dropdown wechselt das Item, der Dropdown
                    // bleibt sichtbar — kein Flash).
                    val keepVariants = _state.value.variants.any { it.id == item.id }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            item = item,
                            cast = emptyList(),
                            variants = if (keepVariants) it.variants else emptyList()
                        )
                    }
                    // Cast + Varianten parallel — beides ist Online-only,
                    // im Offline-Mode skippen (sonst DNS-Errors).
                    val metaId = item.metadataId
                    if (!offline && metaId != null && metaId > 0) {
                        launch {
                            val cast = itemRepository.getMetadataCast(metaId)
                            android.util.Log.d("GF-Detail",
                                "cast loaded metaId=$metaId size=${cast.size}")
                            _state.update { it.copy(cast = cast) }
                        }
                        // Varianten-Geschwister parallel holen (Browser openDetail
                        // tut dasselbe via /api/items/{id}/variants). Skip beim
                        // Dropdown-Variant-Switch (variants-Liste schon da).
                        if (!keepVariants) {
                            launch {
                                val varRes = itemRepository.getItemVariants(item.id)
                                if (varRes is Result.Success && varRes.data.size > 1) {
                                    _state.update { it.copy(variants = varRes.data) }
                                }
                            }
                        }
                    } else {
                        android.util.Log.d("GF-Detail",
                            "no cast load — metadataId=${item.metadataId}")
                    }
                }
            } else {
                _state.update { it.copy(isLoading = false, errorMessage = "Item nicht gefunden") }
            }
        }
    }

    fun getPersonProfileUrl(tmdbId: Int): String =
        itemRepository.getPersonProfileUrl(tmdbId, _state.value.baseUrl)

    /** Wechsel auf eine andere Datei-Variante (selber TMDB-Eintrag, andere Datei).
     *  Lädt das Item neu, damit Resume-Position/Watched/Favorit der gewählten
     *  Datei korrekt angezeigt werden. */
    fun selectVariant(variantId: Int) {
        if (variantId == _state.value.item?.id) return
        load(variantId)
    }

    fun toggleWatched() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val newWatched = !item.watched
            when (itemRepository.setWatched(item.id, newWatched)) {
                is Result.Success -> {
                    _state.update { it.copy(item = item.copy(watched = newWatched)) }
                }
                is Result.Error -> {}
            }
        }
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val newFav = !item.favorite
            when (itemRepository.setFavorite(item.id, newFav)) {
                is Result.Success -> {
                    _state.update { it.copy(item = item.copy(favorite = newFav)) }
                }
                is Result.Error -> {}
            }
        }
    }

    fun startDownload() {
        val item = _state.value.item ?: return
        if (_state.value.isDownloading) {
            // Re-Tap waehrend Download laeuft: kein Abbruch (DownloadRepo
            // ignoriert Duplikate ohnehin still), aber dem User wenigstens
            // signalisieren, dass der Tap kein Versehen war.
            _state.update { it.copy(toastMessage = "Wird bereits geladen…") }
            return
        }

        // Direkt sofortiges UI-Feedback + Toast
        _state.update {
            it.copy(
                isDownloading = true,
                downloadProgress = 0f,
                downloadError = null,
                toastMessage = "Download gestartet…"
            )
        }
        // Download im App-Scope, läuft auch nach Back-Navigation weiter.
        // Bevorzugt SAF-Ordner (Tree-URI), sonst File-Pfad.
        downloadRepository.startInBackground(item, downloadDir, downloadTreeUri)
    }

    /** Resume-Position auf 0 setzen (sowohl serverseitig als auch im lokalen Item-State),
     *  dann den Play-Callback ausführen. */
    fun resetResumeAndPlay(onAfterReset: () -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            itemRepository.setResume(item.id, 0.0)
            _state.update { it.copy(item = item.copy(resumePosSec = 0.0)) }
            onAfterReset()
        }
    }

    fun deleteDownload() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            downloadRepository.deleteDownload(item.id)
            _state.update { it.copy(isDownloaded = false, toastMessage = "Download gelöscht") }
        }
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun getPosterUrl(): String {
        val item = _state.value.item ?: return ""
        val base = _state.value.baseUrl.trimEnd('/')
        val url = if (item.metadataId != null) {
            "$base/api/poster/metadata/${item.metadataId}"
        } else if (item.hasThumb) {
            "$base/api/thumb/${item.id}"
        } else {
            ""
        }
        return if (url.isEmpty()) url else imageCache.preferLocal(url)
    }
}
