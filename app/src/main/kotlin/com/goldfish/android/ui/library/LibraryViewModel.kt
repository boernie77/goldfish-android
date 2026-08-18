package com.goldfish.android.ui.library

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.FolderItem
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.model.LibraryStats
import com.goldfish.android.data.model.SeasonInfo
import com.goldfish.android.data.model.SeasonResponse
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.DownloadRepository
import com.goldfish.android.data.repository.ItemRepository
import com.goldfish.android.data.repository.OfflineRepository
import com.goldfish.android.data.repository.Result
import com.goldfish.android.util.groupVariants
import com.goldfish.android.util.naturalCompare
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Sort modes
const val SORT_TITLE = "title"
const val SORT_RELEASED = "released"
const val SORT_ADDED = "added"
const val SORT_DURATION = "duration"
const val SORT_PLAYED = "played"

// Sort-Modi, die eine flache, library-weite Liste zeigen (Ordner-/Staffel-
// Struktur ignorieren) — Pendant zum Browser. "Letzte/laengste Videos der
// ganzen Bibliothek" unabhaengig vom aktuellen Ordner.
fun isFlatSortMode(sort: String): Boolean =
    sort == SORT_PLAYED || sort == SORT_ADDED || sort == SORT_DURATION

// Resolution buckets (must match server values)
val ALL_BUCKETS = listOf("4K","2K","1080p","720p","576p","480p","≤360p")

// Watched filter modes
const val WATCHED_ALL = "all"
const val WATCHED_UNWATCHED = "unwatched"
const val WATCHED_WATCHED = "watched"

data class LibraryState(
    val isLoading: Boolean = true,
    val library: Library? = null,
    val items: List<Item> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val errorMessage: String? = null,
    val baseUrl: String = "",
    val downloadedItemIds: Set<Int> = emptySet(),
    val activeDownloads: Map<Int, Float> = emptyMap(),  // itemId → 0..1
    // Offline-Filter (von SettingsDataStore gespiegelt) — wenn an, Items
    // und Folders werden auf Offline-Inhalte gefiltert.
    val offlineOnly: Boolean = false,
    // Top-Folder-Segmente, fuer die mindestens ein Item lokal verfuegbar ist.
    // Wird aus DownloadRepository.getDownloadsForLibrary abgeleitet und im
    // Offline-Mode genutzt, um state.folders zu filtern.
    val offlineFolderSet: Set<String> = emptySet(),
    // Filter state
    val searchQuery: String = "",
    val sortMode: String = SORT_TITLE,
    val sortAscending: Boolean = true,
    val watchedFilter: String = WATCHED_ALL,
    val favoritesOnly: Boolean = false,
    val flatView: Boolean = false,
    val seasonView: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedItemIds: Set<Int> = emptySet(),
    // Extra filters
    val selectedBuckets: Set<String> = emptySet(),   // Auflösungs-Filter
    val minRating: Float = 0f,                        // Mindest-Bewertung (0 = kein Filter)
    // Season view data
    val seasonData: SeasonResponse? = null,
    val selectedSeason: SeasonInfo? = null,   // null = Staffel-Übersicht, gesetzt = Episoden-Grid
    // Episoden-Items für die aktuelle Show — Map (season,episode) → Item, für resLabel etc.
    val episodeItems: Map<Pair<Int, Int>, Item> = emptyMap(),
    // Buchstaben-Filter aus der Sidebar — null = alle. Filtert client-seitig
    // sowohl Folders (Serien-Show-Ordner) als auch Items (Filme).
    val alphaFilter: String? = null,
    // Aggregierte Counts fuer Topbar-Subtitle (analog Browser-loadCount):
    // totalItems = Anzahl Files in Library/Folder, folderCount = Top-Level-
    // Folder im Library-Root (bei TV = Serien-Anzahl).
    val stats: LibraryStats? = null,
    // Admin-Flag aus AuthRepository — bestimmt Sichtbarkeit des Drilldown-Toggles.
    val isAdmin: Boolean = false,
    // Aktiver Drilldown-Modus fuer den aktuellen Folder:
    // wenn true und currentFolder!=null, werden Subfolders + nur direkte
    // Items angezeigt (statt rekursiv-flach). Wird per Route-Param gesetzt.
    val currentFolderDrilldown: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemRepository: ItemRepository,
    private val downloadRepository: DownloadRepository,
    private val settingsDataStore: SettingsDataStore,
    private val apiClientProvider: ApiClientProvider,
    private val offlineRepository: OfflineRepository,
    private val imageCache: com.goldfish.android.data.cache.ImageCache,
    private val authRepository: AuthRepository
) : ViewModel() {

    // SharedPreferences für persistente View-Einstellungen pro Library
    private val prefs by lazy {
        context.getSharedPreferences("library_view_prefs", Context.MODE_PRIVATE)
    }

    private fun prefKey(key: String) = "${key}_${currentLibraryId}"

    private fun loadPersistedState(kind: String): Triple<String, Boolean, Boolean> {
        val defaultSort = if (kind == "private") SORT_RELEASED else SORT_TITLE
        val defaultAsc = true
        val defaultSeason = kind == "tv"
        val sort = prefs.getString(prefKey("sort"), defaultSort) ?: defaultSort
        val asc = prefs.getBoolean(prefKey("asc"), defaultAsc)
        val flat = prefs.getBoolean(prefKey("flat"), false)
        val season = prefs.getBoolean(prefKey("season"), defaultSeason)
        return Triple(sort, asc, flat)
    }

    private fun persistState() {
        val s = _state.value
        prefs.edit()
            .putString(prefKey("sort"), s.sortMode)
            .putBoolean(prefKey("asc"), s.sortAscending)
            .putBoolean(prefKey("flat"), s.flatView)
            .putBoolean(prefKey("season"), s.seasonView)
            .apply()
    }

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var currentLibraryId: Int = -1
    private var currentFolder: String? = null
    private var currentFolderDrilldown: Boolean = false
    // Bei zusammengelegten Bibliotheken: Liste aller IDs. Leer = einzelne Lib.
    private var mergedLibraryIds: List<Int> = emptyList()

    // Reload-Generation: jedes reload() inkrementiert. doReload prueft an
    // jedem state-Update ob die eigene Generation noch aktuell ist —
    // sonst wird stale-Result verworfen (kein Job-Cancel, weil dadurch
    // OkHttp-Calls abgebrochen wuerden und Caches nicht befuellt).
    private var reloadGeneration: Int = 0
    @Suppress("unused")  // Migration-Stub fuer alten reloadJob-Code
    private var reloadJob: kotlinx.coroutines.Job? = null

    // Wird vom "↻ TMDB neu laden"-Button gesetzt — naechster getSeasons-Call
    // im doReload-Pfad benutzt forceRefresh=true (invalidiert in-memory +
    // server-side TMDB-Cache, plus folder-cache).
    private var pendingSeasonRefresh: Boolean = false

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                apiClientProvider.configure(settings.serverUrl, settings.cacheSizeBytes)
                _state.update {
                    it.copy(baseUrl = settings.serverUrl, offlineOnly = settings.offlineOnly)
                }
                // Bei Toggle-Aenderung Items neu laden, weil Folder-Listen
                // im Offline-Mode anders gefiltert werden (rekursiv aus DB
                // statt aus dem Server-Folder-Endpoint).
                if (currentLibraryId > 0) reload()
            }
        }

        viewModelScope.launch {
            downloadRepository.getDownloadedItemIds().collect { ids ->
                _state.update { it.copy(downloadedItemIds = ids.toSet()) }
            }
        }

        viewModelScope.launch {
            downloadRepository.activeDownloads.collect { active ->
                _state.update { it.copy(activeDownloads = active) }
            }
        }
    }

    /** Liefert die Set der Top-Folder-Segmente in einer Library, fuer die
     *  mindestens ein Download existiert. Wird im Offline-Mode genutzt, um
     *  state.folders zu filtern. */
    private suspend fun computeOfflineFolders(libraryId: Int): Set<String> {
        val downloads = try {
            downloadRepository.getDownloadsForLibrary(libraryId).first()
        } catch (_: Exception) { return emptySet() }
        return downloads.mapNotNull { d ->
            d.relPath.split('/').firstOrNull { it.isNotBlank() }
        }.toSet()
    }

    /**
     * Offline-Fast-Path fuer doReload — bedient TV-/Privat-Root mit Folders,
     * TV-Show-Folder mit SeasonResponse, Movies + sonst mit Items, alles
     * aus dem OfflineRepository (Room). Keine Server-Calls.
     */
    private suspend fun doReloadOffline(kind: String, isPrivate: Boolean, isTv: Boolean, myGen: Int) {
        val cf = currentFolder

        // Flache, library-weite Sort-Modi (played/added/duration) auch offline:
        // aus Room sortiert, ohne Ordner/Staffel-Struktur. "Zuletzt abgespielt"
        // nutzt das lokale lastPlayedAt (Server-last_played_at ist offline nicht da).
        val st0 = _state.value
        if (isFlatSortMode(st0.sortMode)) {
            val items = offlineRepository.itemsSortedFlat(currentLibraryId, st0.sortMode, st0.sortAscending, currentFolder)
            if (!isStillCurrent(myGen)) return
            _state.update {
                it.copy(isLoading = false, folders = emptyList(), seasonData = null, items = items)
            }
            return
        }
        // TV-Show-Folder → Staffel-Ansicht. In Offline-Mode IMMER Season-View
        // (auch wenn der User die SeasonView-Pref aus hat) — sonst sieht er
        // Episoden flach statt Staffeln.
        if (isTv && cf != null) {
            val seasonData = offlineRepository.seasons(currentLibraryId, cf)
            val episodeItems = offlineRepository.items(currentLibraryId, cf)
                .mapNotNull { item ->
                    val s = item.metadata?.season
                    val e = item.metadata?.episode
                    if (s != null && e != null) (s to e) to item else null
                }.toMap()
            if (!isStillCurrent(myGen)) return
            _state.update {
                it.copy(
                    isLoading = false,
                    seasonData = seasonData,
                    episodeItems = episodeItems,
                    items = emptyList(),
                    folders = emptyList()
                )
            }
            return
        }

        // TV-/Privat-Lib-Root → Show-/Kanal-Liste aus DB. KEINE Items zeigen.
        // flatView ignorieren — in Offline-Mode immer Struktur zeigen.
        val usesFolders = (isTv || isPrivate)
        if (cf == null && usesFolders) {
            val folders = offlineRepository.folders(currentLibraryId)
            if (!isStillCurrent(myGen)) return
            _state.update {
                it.copy(
                    isLoading = false,
                    folders = folders,
                    items = emptyList(),
                    seasonData = null
                )
            }
            return
        }

        // Movies-Lib oder Folder-Inhalt flach → Items aus DB.
        // Client-seitig sortieren wie der Online-Pfad — Room liefert die rohe
        // Insert-Reihenfolge, sonst ist Privat-Lib in "nur Offline" durcheinander.
        val items = sortItemsForDisplay(offlineRepository.items(currentLibraryId, cf))
        if (!isStillCurrent(myGen)) return
        _state.update {
            it.copy(
                isLoading = false,
                folders = emptyList(),
                seasonData = null,
                items = items
            )
        }
    }

    /** Sort-Modus aus Prefs mit Kind-abhaengigem Default (Privat → released,
     *  sonst Titel). Wird synchron in load() genutzt. */
    private fun defaultSortFor(kind: String): String {
        val def = if (kind == "private") SORT_RELEASED else SORT_TITLE
        return prefs.getString(prefKey("sort"), def) ?: def
    }

    /** Season-View aus Prefs; im TV-Show-Folder eigener Key, sonst Lib-Default. */
    private fun defaultSeasonFor(kind: String, folder: String?): Boolean {
        return if (folder != null && kind == "tv")
            prefs.getBoolean("folder_season_${currentLibraryId}", true)
        else prefs.getBoolean(prefKey("season"), kind == "tv")
    }

    /**
     * Client-seitige Sortierung passend zum aktuellen sortMode/sortAscending.
     * Wird im Online-Pfad (loadItems) UND im Offline-Pfad (doReloadOffline)
     * genutzt, damit die Reihenfolge in beiden Modi identisch ist. Der
     * Offline-Pfad bekam frueher die rohe Room-Reihenfolge (id/Insert) → in
     * "nur Offline" war Privat-Lib durcheinander. Title → Natural-Sort,
     * Released (nur Privat) → nach metadata.year bzw. releasedAt. Andere
     * Sort-Modi: Reihenfolge unveraendert lassen (Server/Room hat bereits
     * sortiert bzw. der Client kann sie nicht praezise reproduzieren).
     */
    private fun sortItemsForDisplay(items: List<Item>): List<Item> {
        val st = _state.value
        val sign = if (st.sortAscending) 1 else -1
        return when {
            st.sortMode == SORT_TITLE -> items.sortedWith { a, b ->
                sign * naturalCompare(a.displayTitle, b.displayTitle)
            }
            st.sortMode == SORT_RELEASED && st.library?.kind == "private" -> {
                fun relKey(it: Item): String {
                    val y = it.metadata?.year ?: 0
                    if (y > 0) return "%04d-01-01".format(y)
                    return it.releasedAt ?: ""
                }
                items.sortedWith(
                    // Items ohne Datum ans Ende (in beiden Richtungen).
                    compareBy<Item> { relKey(it).isBlank() }
                        .thenComparator { a, b -> sign * relKey(a).compareTo(relKey(b)) }
                )
            }
            else -> items
        }
    }

    /** Lädt eine zusammengelegte Bibliothek (mehrere IDs). Setzt mergedLibraryIds,
     *  lädt Metadaten von der primären Library (ids.first()) und zeigt Items
     *  von ALLEN angegebenen Libraries. */
    fun loadMerged(ids: List<Int>, folder: String?, drilldownActive: Boolean = false) {
        if (ids.isEmpty()) return
        mergedLibraryIds = ids
        load(ids.first(), folder, drilldownActive)
    }

    fun load(libraryId: Int, folder: String?, drilldownActive: Boolean = false) {
        if (libraryId != currentLibraryId) mergedLibraryIds = emptyList() // Reset wenn andere einzelne Lib
        val isNewLibrary = currentLibraryId != libraryId
        val isNewFolder = currentFolder != folder
        currentLibraryId = libraryId
        currentFolder = folder
        currentFolderDrilldown = drilldownActive
        // Sofort invalidieren, damit evtl. noch laufende aeltere Reloads keinen
        // stale State mehr schreiben. Die finale Generation fuer dieses load()
        // wird im Coroutine-Block (nach dem Sort-Setzen) frisch gezogen.
        reloadGeneration++
        // SOFORT (vor dem suspend-getLibraries) Navigation-State leeren, sonst
        // sieht der User noch fuer ~100ms-1s die stale items/seasons/staffel-
        // Wahl der vorherigen Library/Folder. Loading-Spinner statt stale
        // Episoden-Grid.
        //
        // WICHTIG: Bei neuer Library Sort/Richtung/Season SYNCHRON setzen —
        // NICHT erst im suspend-Block nach getLibraries(). Sonst steht bis dahin
        // der Data-Class-Default SORT_TITLE im State; ein in diesem Fenster
        // laufendes loadItems (dessen Ergebnis per Generation-Guard gewinnt)
        // rendert title-/unsortiert, waehrend sortMode danach auf "released"
        // springt → Topbar zeigt "Veroeffentlicht", die Liste ist aber nicht
        // nach Datum sortiert; erst ein Toggle korrigiert. Kind kommt aus dem
        // prefs-Cache (kind_<libId>, beim letzten Besuch gespeichert); fuer den
        // allerersten Besuch korrigiert der suspend-Block unten nach.
        if (isNewLibrary || isNewFolder) {
            _state.update { st ->
                var s = st.copy(
                    isLoading = true,
                    items = emptyList(),
                    folders = emptyList(),
                    seasonData = null,
                    selectedSeason = null,
                    episodeItems = emptyMap()
                )
                if (isNewLibrary) {
                    val cachedKind = prefs.getString(prefKey("kind"), null) ?: ""
                    s = s.copy(
                        sortMode = defaultSortFor(cachedKind),
                        sortAscending = prefs.getBoolean(prefKey("asc"), true),
                        flatView = prefs.getBoolean(prefKey("flat"), false),
                        seasonView = defaultSeasonFor(cachedKind, folder),
                        searchQuery = "",
                        watchedFilter = WATCHED_ALL,
                        favoritesOnly = false,
                        selectionMode = false,
                        selectedItemIds = emptySet()
                    )
                }
                s
            }
        }
        viewModelScope.launch {
            // Bibliothek laden um kind zu kennen. Reihenfolge: Server (mit
            // ApiCache) → bei Fehler Room-Cache. librariesCached() ist die
            // synchrone (nicht-combine'd) Variante, damit die Kind-Erkennung
            // nicht durch eine kurze []-Phase aus dem combine-Flow leidet.
            val serverLibs = (itemRepository.getLibraries() as? Result.Success)?.data
            val libraries = serverLibs ?: offlineRepository.librariesCached()
            val library = libraries.find { it.id == libraryId }
            val kind = library?.kind ?: ""
            // Kind fuer den naechsten (synchronen) load() cachen, damit
            // Sort/Season schon beim Eintritt korrekt stehen.
            if (kind.isNotEmpty()) {
                prefs.edit().putString(prefKey("kind"), kind).apply()
            }

            // isAdmin + drilldown-Flag aus den Routen-Args/Auth-Repo immer
            // mit-aktualisieren — auch wenn kein neues Lib/Folder.
            val admin = authRepository.getCurrentStatus()?.isAdmin ?: false
            _state.update { it.copy(isAdmin = admin, currentFolderDrilldown = currentFolderDrilldown, library = library) }

            // Sort/Richtung/Season wurden bereits SYNCHRON oben gesetzt (aus dem
            // kind-prefs-Cache). Beim ALLERERSTEN Besuch dieser Lib war der
            // Cache leer → kind="" → ggf. falscher Default (Titel statt
            // released, kein Season bei TV). Jetzt mit dem echten kind
            // nachkorrigieren, aber nur wo der User keinen eigenen Pref hat.
            if (isNewLibrary && kind.isNotEmpty()) {
                _state.update { st ->
                    var s = st
                    if (prefs.getString(prefKey("sort"), null) == null) {
                        s = s.copy(sortMode = defaultSortFor(kind))
                    }
                    if (!prefs.contains(prefKey("season")) && !(folder != null && kind == "tv")) {
                        s = s.copy(seasonView = kind == "tv")
                    }
                    s
                }
            }
            // Frische Generation ziehen, damit dieses (mit korrektem Sort
            // laufende) doReload jede parallele reload()-Generation schlaegt,
            // die evtl. noch mit dem stale Default lief.
            reloadGeneration++
            doReload(reloadGeneration)
        }
    }

    fun reload() {
        // Nicht canceln! Sonst wird auch der OkHttp-Request gecancelt, was
        // dazu fuehrt dass Library-Cache + Backfill NICHT mit Daten gefuettert
        // werden (und der User dann beim naechsten Klick "Item nicht gefunden"
        // sieht). Stattdessen: Generation-Check in doReload — alte Reloads
        // duerfen ihre Server-Antworten zu Ende verarbeiten und Caches
        // populieren, schreiben aber NUR dann state, wenn sie noch aktuell
        // sind. Damit kein Flicker durch stale Writes.
        reloadGeneration++
        viewModelScope.launch { doReload(reloadGeneration) }
    }

    /** Hilfsfunktion: ist die aktuelle Reload-Generation noch unsere? Wenn
     *  nein, soll der Caller NICHT mehr in den State schreiben — sonst
     *  ueberschreibt ein veraltetes Result die Daten eines neueren Reloads. */
    private fun isStillCurrent(myGen: Int): Boolean = myGen == reloadGeneration

    private suspend fun doReload(myGen: Int = reloadGeneration) {
        if (!isStillCurrent(myGen)) return
        // WICHTIG: selectedSeason + items + episodeItems hier NICHT zuruecksetzen!
        // doReload laeuft auch bei Back-Stack-Pop (LaunchedEffect re-feuert mit
        // gleichen libraryId/folder) — wenn wir hier resetten, geht die User-
        // Staffel-Auswahl beim Back von einer Episode verloren. Navigation-
        // State-Reset gehoert ausschliesslich in load() bei isNewLibrary/Folder.
        _state.update { it.copy(
            isLoading = true,
            errorMessage = null,
            folders = emptyList(),
            seasonData = null,
            stats = null
        ) }

        // Library-Kind MUSS bekannt sein, BEVOR wir entscheiden ob Folders
        // (TV/Privat) oder flache Items (Movies) geladen werden. Ist
        // state.library hier noch null — etwa weil reload() aus dem Settings-
        // Collector lief bevor load() die Lib aufgeloest hat, oder weil
        // getLibraries() beim ersten load() langsam war — dann waere kind=""
        // → usesFolders=false → loadItems() laedt ALLE Items der Lib flach.
        // Der User sieht dann kurz alle Folgen einer Privat-/TV-Lib aufblitzen,
        // die wieder verschwinden sobald die Lib als private/tv erkannt wird
        // ("kein Inhalt gefunden"-Flash). Darum hier synchron nachladen.
        if (_state.value.library == null && currentLibraryId > 0) {
            val libs = (itemRepository.getLibraries() as? Result.Success)?.data
                ?: offlineRepository.librariesCached()
            libs.find { it.id == currentLibraryId }?.let { lib ->
                if (!isStillCurrent(myGen)) return
                _state.update { it.copy(library = lib) }
            }
        }

        val st = _state.value
        val kind = st.library?.kind ?: ""
        val isPrivate = kind == "private"
        val isTv = kind == "tv"

        // OFFLINE-FAST-PATH — wenn Filter an: alles aus Room, kein Server.
        if (st.offlineOnly) {
            doReloadOffline(kind, isPrivate, isTv, myGen)
            return
        }

        // Flache, library-weite Sort-Modi: "Zuletzt abgespielt", "Zuletzt
        // hinzugefuegt", "Laufzeit". Wie im Browser ignorieren diese die Ordner-
        // /Staffel-Struktur und zeigen die Top-Videos der GANZEN Library. Wir
        // umgehen daher Season-/Folder-/Drilldown-Branches komplett und holen
        // direkt flach (loadItems setzt folderParam=null bei diesen Modi).
        if (isFlatSortMode(st.sortMode)) {
            if (!isStillCurrent(myGen)) return
            _state.update { it.copy(folders = emptyList(), seasonData = null) }
            loadItems(myGen)
            return
        }

        // Library-/Folder-Stats fuer den Topbar-Subtitle parallel laden — der
        // Browser ruft GET /api/libraries/{id}/stats[?folder=…] und zeigt
        // "(N Videos)" bzw. "(N Folgen · M Serien)" im Breadcrumb.
        viewModelScope.launch {
            val statsRes = itemRepository.getLibraryStats(currentLibraryId, currentFolder)
            if (statsRes is Result.Success) {
                _state.update { it.copy(stats = statsRes.data) }
            }
        }

        // Innerhalb eines TV Show-Ordners: Staffeln laden wenn seasonView aktiv ist.
        // seasonView für Folder wird separat von Root gespeichert ("folder_season_X").
        val folderSeasonKey = "folder_season_${currentLibraryId}"
        val folderSeasonView = if (currentFolder != null) {
            prefs.getBoolean(folderSeasonKey, true)
        } else false

        Log.d("GF-Season", "doReload: isTv=$isTv folder=$currentFolder folderSeasonView=$folderSeasonView libraryKind=$kind libraryId=$currentLibraryId")

        if (isTv && currentFolder != null && folderSeasonView) {
            val force = pendingSeasonRefresh
            pendingSeasonRefresh = false
            Log.d("GF-Season", "Calling getSeasons($currentLibraryId, $currentFolder) forceRefresh=$force")
            when (val result = itemRepository.getSeasons(currentLibraryId, currentFolder!!, forceRefresh = force)) {
                is Result.Success -> {
                    Log.d("GF-Season", "Seasons loaded: ${result.data.seasons.size} seasons")
                    // Offline-Filter auf Show-Ebene: nur Staffeln zeigen, in
                    // denen mindestens eine Episode lokal verfuegbar ist; in
                    // jeder Staffel auch die Episoden selbst auf Offline-
                    // Vorhandene filtern. Wenn nichts uebrig bleibt, fallen
                    // wir auf den Empty-State zurueck.
                    val seasonData = if (st.offlineOnly) {
                        val downloaded = st.downloadedItemIds
                        val filteredSeasons = result.data.seasons.mapNotNull { season ->
                            val keep = season.episodes.filter {
                                it.itemId != null && it.itemId in downloaded
                            }
                            if (keep.isEmpty()) null
                            else season.copy(
                                episodes = keep,
                                ownedCount = keep.size,
                                total = keep.size
                            )
                        }
                        result.data.copy(seasons = filteredSeasons)
                    } else result.data
                    if (!isStillCurrent(myGen)) return
                    _state.update { it.copy(isLoading = false, seasonData = seasonData, items = emptyList()) }
                    // Episoden-Items für diesen Show-Folder parallel laden, um auf den
                    // Episoden-Kacheln Auflösung/Dauer/etc. zeigen zu können.
                    viewModelScope.launch {
                        val itemsResult = itemRepository.getItems(
                            libraryId = currentLibraryId,
                            folder = currentFolder
                        )
                        if (itemsResult is Result.Success) {
                            val items = if (st.offlineOnly) {
                                itemsResult.data.filter { it.id in st.downloadedItemIds }
                            } else itemsResult.data
                            val map = items.mapNotNull { item ->
                                val s = item.metadata?.season
                                val e = item.metadata?.episode
                                if (s != null && e != null) (s to e) to item else null
                            }.toMap()
                            _state.update { it.copy(episodeItems = map) }
                        }
                    }
                    return
                }
                is Result.Error -> {
                    Log.e("GF-Season", "getSeasons error: ${result.message}")
                }
            }
        }

        // Ordner anzeigen: nur für TV (Show-Ordner) und Private (Kanal-Ordner)
        // Movies zeigt Items direkt flach (wie im Browser) — keine Ordner-Ebene
        val usesFolders = (isTv || isPrivate) && !st.flatView
        // Offline-Filter: Top-Folder-Set mit lokalem Inhalt vorberechnen,
        // damit wir die Server-Folder-Liste danach filtern koennen.
        val offlineFolders = if (st.offlineOnly) computeOfflineFolders(currentLibraryId)
            else emptySet()
        _state.update { it.copy(offlineFolderSet = offlineFolders) }
        // Drilldown-Branch: User ist in einem Folder, der vom Admin als
        // Navigations-Zwischenebene markiert wurde — lade direkte Subfolders.
        // Items werden weiter unten via loadItems() geholt; wir filtern dort
        // serverseitig auf folder=currentFolder. Da der Server bei folder=...
        // rekursiv ALLE Items zurueckgibt, filtern wir client-seitig in
        // loadItems auf "direkt im currentFolder liegende" Items.
        if (currentFolder != null && currentFolderDrilldown && !st.flatView) {
            when (val subRes = itemRepository.getFolders(currentLibraryId, parent = currentFolder)) {
                is Result.Success -> {
                    val subs = subRes.data.sortedWith { a, b ->
                        val ta = a.metadata?.title?.takeIf { it.isNotBlank() } ?: a.folder
                        val tb = b.metadata?.title?.takeIf { it.isNotBlank() } ?: b.folder
                        naturalCompare(ta, tb)
                    }
                    if (!isStillCurrent(myGen)) return
                    _state.update { it.copy(folders = subs) }
                }
                is Result.Error -> {
                    if (!isStillCurrent(myGen)) return
                    _state.update { it.copy(folders = emptyList()) }
                }
            }
            loadItems(myGen)
            return
        }

        if (currentFolder == null && usesFolders) {
            when (val foldersResult = itemRepository.getFolders(currentLibraryId)) {
                is Result.Success -> {
                    val rawFolders = foldersResult.data
                    val filtered = if (st.offlineOnly) {
                        rawFolders.filter { it.folder in offlineFolders }
                    } else rawFolders
                    // Natural-Sort der Ordner (Browser tut das gleiche in grid.js
                    // ueber Intl.Collator numeric:true). "Staffel 2" < "Staffel 10".
                    val folders = filtered.sortedWith { a, b ->
                        val ta = a.metadata?.title?.takeIf { it.isNotBlank() } ?: a.folder
                        val tb = b.metadata?.title?.takeIf { it.isNotBlank() } ?: b.folder
                        naturalCompare(ta, tb)
                    }
                    if (!isStillCurrent(myGen)) return
                    _state.update { it.copy(folders = folders) }
                }
                is Result.Error -> {}
            }
            // Bei aktiven Filtern (Search / Favoriten / Watched-Filter /
            // Auflösungs-Buckets / Min-Rating): zusaetzlich Items rekursiv
            // laden, damit der User die Treffer quer ueber alle Show-/
            // Channel-Folder hinweg sehen kann (analog Browser).
            val filterActive = !st.searchQuery.isBlank() ||
                st.favoritesOnly ||
                st.watchedFilter != WATCHED_ALL ||
                st.selectedBuckets.isNotEmpty() ||
                st.minRating > 0f
            if (filterActive) {
                loadItems(myGen)
                return
            }
            // Bei TV- + Privat-Libs im Lib-Root OHNE aktiven Filter: NIE
            // einzelne Items flach zeigen. Sonst sieht der User pluetzlich
            // alle Episoden einer TV-Lib als flache Karten. Browser-
            // Verhalten: TV/Privat-Root = ausschliesslich Show-/Channel-
            // Ordner.
            if (!isStillCurrent(myGen)) return
            _state.update { it.copy(isLoading = false, items = emptyList()) }
            return
        }

        loadItems(myGen)
    }

    private suspend fun loadItems(myGen: Int = reloadGeneration) {
        val st = _state.value
        val search = st.searchQuery.takeIf { it.isNotBlank() }
        val sort = st.sortMode
        val watchedParam = when (st.watchedFilter) {
            WATCHED_UNWATCHED -> "unwatched"
            WATCHED_WATCHED -> "watched"
            else -> null
        }
        val favoriteParam = if (st.favoritesOnly) "yes" else null
        // Flat-Sort-Modi (played/added/duration) flachen nur NACH UNTEN: im
        // Library-Root (currentFolder=null) library-weit, in einem Unterordner
        // nur dessen Inhalt (rekursiv). Daher hier currentFolder NICHT auf null
        // zwingen — nur der manuelle Flat-Toggle macht das Lib-weit.
        val folderParam = if (st.flatView) null else currentFolder
        val buckets = st.selectedBuckets.takeIf { it.isNotEmpty() }?.toList()

        // dir explizit mitsenden — Server-Default für leeres dir ist je nach
        // sort-Feld unterschiedlich (released wird DESC, title ASC). Mit
        // explizitem dir kontrollieren wir das Verhalten verlässlich, kein
        // client-side reverse mehr nötig (würde bei großen Listen auch viel
        // Memory bewegen).
        val dirParam = if (st.sortAscending) "asc" else "desc"

        // Zusammengelegte Bibliothek: alle IDs mitsenden, sonst nur die aktuelle.
        val libIds = mergedLibraryIds.takeIf { it.size > 1 }
        when (val result = itemRepository.getItems(
            libraryId = if (libIds == null) currentLibraryId else null,
            folder = folderParam,
            sort = sort,
            dir = dirParam,
            search = search,
            watched = watchedParam,
            favorite = favoriteParam,
            bucket = buckets,
            libraryIds = libIds
        )) {
            is Result.Success -> {
                var items = result.data
                // Client-side rating filter (server kennt das Feld nicht)
                if (st.minRating > 0f) {
                    items = items.filter { (it.metadata?.rating ?: 0.0) >= st.minRating }
                }
                // Variant-Merge: Items mit gleicher metadataId (selber Film
                // in 1080p + 4K) zu einer Kachel zusammenfassen — Pendant zu
                // Browser-groupVariants in app.js.
                items = groupVariants(items)
                // Client-seitige Sortierung (Title-Natural / Privat-Released).
                // Der Server sortiert zwar korrekt, aber dieser deterministische
                // Sort macht die Anzeige unabhaengig von Reload-Races und ist
                // identisch zum Offline-Pfad (doReloadOffline nutzt denselben
                // Helper). Siehe sortItemsForDisplay.
                items = sortItemsForDisplay(items)
                // Offline-Filter: nur Items, fuer die ein lokaler Download
                // existiert. Greift NACH groupVariants — wir behalten den
                // Repraesentanten der Variant-Gruppe wenn ER lokal liegt
                // (alternativ koennten wir auf den Download-Vertreter
                // wechseln, aber das verkompliziert die Logik unnoetig).
                if (st.offlineOnly) {
                    items = items.filter { it.id in st.downloadedItemIds }
                }
                // Drilldown-Modus: Server gibt ALLE Items unter currentFolder
                // rekursiv zurueck — wir wollen nur Items, die DIREKT in
                // currentFolder liegen (Pendant zum Browser-Filter in grid.js).
                val drilldownFolder = currentFolder
                if (currentFolderDrilldown && drilldownFolder != null && !st.flatView && !isFlatSortMode(sort)) {
                    val prefix = "$drilldownFolder/"
                    items = items.filter {
                        val rp = it.relPath ?: return@filter false
                        rp.startsWith(prefix) && rp.substring(prefix.length).indexOf('/') == -1
                    }
                }
                if (!isStillCurrent(myGen)) return
                _state.update { it.copy(isLoading = false, items = items) }
            }
            is Result.Error -> {
                if (!isStillCurrent(myGen)) return
                _state.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
        }
    }

    fun onSearchChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        viewModelScope.launch { loadItems() }
    }

    fun onSortChange(sort: String) {
        // Default-Richtung je Sort-Feld. Title/Duration → asc. Added/Played →
        // desc (neueste zuerst). Released → in Privat-Libs asc (aelteste zuerst,
        // wie der Browser + User-Wunsch), sonst desc (neueste Filme/Folgen oben).
        val isPrivate = _state.value.library?.kind == "private"
        val defaultAsc = when (sort) {
            SORT_TITLE, SORT_DURATION -> true
            SORT_RELEASED -> isPrivate
            SORT_ADDED, SORT_PLAYED -> false
            else -> true
        }
        _state.update { it.copy(sortMode = sort, sortAscending = defaultAsc) }
        persistState()
        // Voller Reload statt nur loadItems(): Beim Wechsel ZWISCHEN Ordner-
        // Ansicht und flacher library-weiter Ansicht (played/added/duration)
        // muessen Folders/SeasonData geleert + der Flat-Branch geroutet werden.
        // doReload macht beides; fuer normale Sorts ist es nur minimal teurer.
        reload()
    }

    fun toggleSortDirection() {
        _state.update { it.copy(sortAscending = !it.sortAscending) }
        persistState()
        viewModelScope.launch { loadItems() }
    }

    fun onWatchedFilterChange(filter: String) {
        _state.update { it.copy(watchedFilter = filter) }
        viewModelScope.launch { loadItems() }
    }

    fun toggleFavoritesOnly() {
        _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }
        viewModelScope.launch { loadItems() }
    }

    fun toggleFlatView() {
        _state.update { it.copy(flatView = !it.flatView) }
        persistState()
        reload()
    }

    /** Buchstaben-Sidebar: setzt/entfernt den Anfangsbuchstaben-Filter.
     *  Tap auf den gleichen Buchstaben ein zweites Mal → Filter aus. */
    fun toggleAlphaFilter(letter: String) {
        _state.update {
            it.copy(alphaFilter = if (it.alphaFilter == letter) null else letter)
        }
    }

    fun selectSeason(season: SeasonInfo?) {
        _state.update { it.copy(selectedSeason = season) }
    }

    /** Vom "↻ TMDB neu laden"-Button getriggert. Invalidiert in-memory +
     *  server-TMDB-Cache + folder-cache, holt frische SeasonResponse +
     *  ueberschreibt den Room-Cache (library_seasons_cache). Verwendung nach
     *  manueller Re-Zuordnung im Browser, damit die App die korrigierte
     *  Show + Episoden-Stills sieht. */
    fun refreshSeasonData() {
        pendingSeasonRefresh = true
        reload()
    }

    fun toggleSeasonView() {
        val newVal = !_state.value.seasonView
        _state.update { it.copy(seasonView = newVal) }
        if (currentFolder != null) {
            // Im Show-Folder: Staffel/Flat-Ansicht separat speichern
            prefs.edit().putBoolean("folder_season_${currentLibraryId}", newVal).apply()
        } else {
            persistState()
        }
        reload()
    }

    fun toggleSelectionMode() {
        _state.update { it.copy(selectionMode = !it.selectionMode, selectedItemIds = emptySet()) }
    }

    // Zufälliges Item — im Offline-Mode rein aus Room (kein Netz), sonst Server.
    suspend fun randomItem(): Item? {
        val st = _state.value
        val libIds = mergedLibraryIds.takeIf { it.size > 1 }
        if (st.offlineOnly) {
            if (libIds != null) {
                // Mehrere Libs: aus jeder zufällig picken
                val allItems = libIds.flatMap { id ->
                    try { offlineRepository.items(id) } catch (_: Exception) { emptyList() }
                }
                return allItems.randomOrNull()
            }
            return offlineRepository.randomItem(
                libraryId = currentLibraryId,
                folder = if (st.flatView) null else currentFolder
            )
        }
        val watchedParam = when (st.watchedFilter) {
            WATCHED_UNWATCHED -> "unwatched"
            WATCHED_WATCHED -> "watched"
            else -> null
        }
        val folderParam = if (st.flatView) null else currentFolder
        return itemRepository.getRandomItem(
            libraryId = if (libIds == null) currentLibraryId else null,
            folder = folderParam,
            watched = watchedParam,
            libraryIds = libIds
        )
    }

    fun toggleBucket(bucket: String) {
        val current = _state.value.selectedBuckets
        _state.update {
            it.copy(selectedBuckets = if (bucket in current) current - bucket else current + bucket)
        }
        viewModelScope.launch { loadItems() }
    }

    fun setMinRating(rating: Float) {
        _state.update { it.copy(minRating = rating) }
        viewModelScope.launch { loadItems() }
    }

    /**
     * Admin-Toggle: setzt fuer einen Folder die Drilldown-Eigenschaft
     * (Unterordner als Navigations-Ebene anzeigen). Browser-Pendant ist
     * das Hover-⚙-Icon auf FolderCard, das PUT /folders/drilldown ruft.
     * Nach Erfolg wird der Folder-Cache invalidiert und die Folder-Liste
     * frisch geladen, damit der neue drilldown-Wert in der UI ankommt.
     */
    fun setFolderDrilldown(folder: String, enabled: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = itemRepository.setFolderDrilldown(currentLibraryId, folder, enabled)
            val ok = r is Result.Success
            if (ok) reload()
            onDone(ok)
        }
    }

    fun toggleItemSelection(itemId: Int) {
        val current = _state.value.selectedItemIds
        _state.update {
            it.copy(
                selectedItemIds = if (itemId in current) current - itemId else current + itemId
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedItemIds = emptySet()) }
    }

    fun selectAll() {
        // In Episoden-Ansicht ist state.items leer, die Items liegen unter
        // state.episodeItems. Beide Quellen vereinen, damit „Alle" auch in
        // der Staffel-Ansicht funktioniert.
        val st = _state.value
        val allIds = (st.items.map { it.id } + st.episodeItems.values.map { it.id }).toSet()
        _state.update { it.copy(selectedItemIds = allIds) }
    }

    fun bulkSetWatched(watched: Boolean) {
        val ids = _state.value.selectedItemIds.toList()
        viewModelScope.launch {
            ids.forEach { id -> itemRepository.setWatched(id, watched) }
            _state.update { it.copy(selectionMode = false, selectedItemIds = emptySet()) }
            doReload()
        }
    }

    fun bulkSetFavorite(favorite: Boolean) {
        val ids = _state.value.selectedItemIds.toList()
        viewModelScope.launch {
            ids.forEach { id -> itemRepository.setFavorite(id, favorite) }
            _state.update { it.copy(selectionMode = false, selectedItemIds = emptySet()) }
            doReload()
        }
    }

    /** Einzel-Loeschung eines heruntergeladenen Items — wird vom Long-Press
     *  auf einer VideoCard mit isDownloaded=true ausgeloest. Loescht die
     *  Datei (SAF oder Disk) + den DB-Eintrag. State refresht sich
     *  automatisch ueber den downloadRepository.getDownloadedItemIds()-Flow. */
    fun deleteDownloadForItem(itemId: Int) {
        viewModelScope.launch {
            downloadRepository.deleteDownload(itemId)
        }
    }

    fun bulkDownload(items: List<Item>) {
        val selectedIds = _state.value.selectedItemIds
        val selectedItems = items.filter { it.id in selectedIds }
        viewModelScope.launch {
            selectedItems.forEach { item ->
                downloadRepository.startBackgroundDownload(item)
            }
            _state.update { it.copy(selectionMode = false, selectedItemIds = emptySet()) }
        }
    }

    fun getImageUrl(item: Item): String {
        val base = _state.value.baseUrl.trimEnd('/')
        val library = _state.value.library
        val isPoster = library?.kind == "movies" || library?.kind == "tv"
        val url = if (isPoster && item.metadataId != null) {
            "$base/api/poster/metadata/${item.metadataId}"
        } else {
            "$base/api/thumb/${item.id}"
        }
        // Lokal vorhanden? Dann file:// zurueck — funktioniert online + offline.
        return imageCache.preferLocal(url)
    }

    fun getFolderPosterUrl(folder: FolderItem): String {
        val base = _state.value.baseUrl.trimEnd('/')
        val url = when {
            folder.metadataId != null -> "$base/api/poster/metadata/${folder.metadataId}"
            folder.thumbItemId != null -> "$base/api/thumb/${folder.thumbItemId}"
            else -> ""
        }
        return if (url.isEmpty()) url else imageCache.preferLocal(url)
    }

    fun getSeasonPosterUrl(season: com.goldfish.android.data.model.SeasonInfo): String {
        val base = _state.value.baseUrl.trimEnd('/')
        val url = if (season.posterPath != null) {
            "https://image.tmdb.org/t/p/w342${season.posterPath}"
        } else {
            ""
        }
        return if (url.isEmpty()) url else imageCache.preferLocal(url)
    }

    /** Episoden-Still-URL mit Lokal-Cache-Bevorzugung. Wenn die Episode keinen
     *  stillPath hat, wird der Season-Poster (oder leerer String) als Fallback
     *  zurueckgegeben — beide gehen durch preferLocal, damit der Offline-Mode
     *  zumindest das gecachte Season-Poster zeigt statt einen toten
     *  image.tmdb.org-Link. */
    fun getEpisodeStillUrl(
        episode: com.goldfish.android.data.model.EpisodeInfo,
        season: com.goldfish.android.data.model.SeasonInfo
    ): String {
        val stillUrl = episode.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
        if (stillUrl != null) return imageCache.preferLocal(stillUrl)
        return getSeasonPosterUrl(season)
    }
}
