package com.goldfish.android.ui.locallib

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.cache.ImageCache
import com.goldfish.android.data.local.LocalEnricher
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.model.TmdbSearchResult
import com.goldfish.android.data.repository.LocalLibraryRepository
import com.goldfish.android.util.naturalCompare
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-Repraesentation eines lokalen Items oder einer Show-Folder-Kachel —
 * generisch genug fuer das Grid, das beides nebeneinander rendern kann.
 */
sealed class LocalCard {
    /** Folder-Kachel — kann eine Show, einen Privat-Kanal, oder eine
     *  virtuelle Staffel sein. `seasonNumber != null` markiert eine
     *  Staffel-Kachel (Klick navigiert nicht zu Sub-Folder, sondern
     *  setzt `currentSeason` im ViewModel). */
    data class Folder(
        val name: String,
        val itemCount: Int,
        val posterUrl: String? = null,
        val showTitle: String? = null,
        val seasonNumber: Int? = null
    ) : LocalCard()

    data class Video(val item: LocalItemEntity, val posterUrl: String?) : LocalCard()
}

// Sortier-Modi fuer lokale Libs — analog zum Server-Library aber abgespeckt.
const val LOCAL_SORT_TITLE = "title"
const val LOCAL_SORT_RELEASED = "released"   // releasedAtMs ?: modifiedTime
const val LOCAL_SORT_ADDED = "added"         // createdAt (Scan-Zeitpunkt)
const val LOCAL_SORT_DURATION = "duration"
const val LOCAL_SORT_EPISODE = "episode"     // parsedSeason, parsedEpisode
const val LOCAL_SORT_PLAYED = "played"       // lastPlayedAt (lokal gestempelt)
const val LOCAL_WATCHED_ALL = "all"
const val LOCAL_WATCHED_UNWATCHED = "unwatched"
const val LOCAL_WATCHED_WATCHED = "watched"

data class LocalLibraryState(
    val isLoading: Boolean = true,
    val library: LocalLibraryEntity? = null,
    val folders: List<LocalCard.Folder> = emptyList(),
    val items: List<LocalCard.Video> = emptyList(),
    val currentFolder: String? = null,
    // Nur in TV-Libs + Show-Folder relevant: wenn != null, zeigt der Screen
    // die Episoden DIESER Staffel; sonst die Staffel-Liste.
    val currentSeason: Int? = null,
    // Sortier-/Filter-State (gleicher Default-Charakter wie LibraryViewModel).
    val sortMode: String = LOCAL_SORT_TITLE,
    // True sobald der User aktiv aus dem Dropdown ausgewaehlt hat —
    // dann gilt seine Wahl absolut. Sonst kann der View-spezifische
    // defaultSort (z.B. Released fuer YT-Channels) ueberlagern.
    val userPickedSort: Boolean = false,
    val sortAscending: Boolean = true,
    val watchedFilter: String = LOCAL_WATCHED_ALL,
    // Aufloesungs-Filter — Multi-Select-Buckets wie Server-Lib.
    // Leeres Set = kein Filter (alle Aufloesungen).
    val selectedBuckets: Set<String> = emptySet(),
    // Vergleichs-Filter: wenn aktiv, werden Items deren `fileName` in
    // mind. 2 ausgewaehlten Libs (Settings → "Lokale Bibliotheken" →
    // Vergleichs-Filter) vorkommt mit rotem Rahmen markiert.
    val compareMode: Boolean = false,
    // Dateinamen, die als Duplikate gelten — wird neu berechnet wenn
    // compareMode oder die Auswahl der zu vergleichenden Libs sich aendert.
    val duplicateFileNames: Set<String> = emptySet(),
    // Mehrfachauswahl-Modus: wenn aktiv, oeffnet ein Klick auf eine Card
    // nicht den Player, sondern toggelt die Selektion. Sticky Bottom-Bar
    // zeigt Bulk-Aktionen (Alle/Keine/Loeschen).
    val selectionMode: Boolean = false,
    val selectedItemIds: Set<Int> = emptySet(),
    // Alle Items aus allen (ggf. gemergten) Libraries flat — wird fuer
    // die Volltextsuche genutzt, damit auch Items INNERHALB von Channel-
    // Ordnern gefunden werden (state.items enthaelt nur Root-Level-Items).
    val allItemsFlat: List<LocalCard.Video> = emptyList()
)

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val repository: LocalLibraryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val imageCache: ImageCache,
    private val enricher: LocalEnricher,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("local_lib_prefs", android.content.Context.MODE_PRIVATE)

    // Initial State aus SharedPreferences hydrieren — sodass User-Auswahl
    // (Sort, Filter, Buckets) ueber Library-Wechsel + App-Restart hinweg
    // erhalten bleibt. Bei einem neuen ViewModel (z.B. nach Lib-Verlassen
    // und erneutem Eintritt) hat man sonst Default-State und der context-
    // default ueberlagert die Auswahl wieder.
    private val _state = MutableStateFlow(
        LocalLibraryState(
            sortMode = prefs.getString("sortMode", LOCAL_SORT_TITLE) ?: LOCAL_SORT_TITLE,
            userPickedSort = prefs.getBoolean("userPickedSort", false),
            sortAscending = prefs.getBoolean("sortAscending", true),
            watchedFilter = prefs.getString("watchedFilter", LOCAL_WATCHED_ALL) ?: LOCAL_WATCHED_ALL,
            selectedBuckets = prefs.getStringSet("selectedBuckets", emptySet())?.toSet() ?: emptySet()
        )
    )
    val state: StateFlow<LocalLibraryState> = _state.asStateFlow()

    init {
        // Auf Item-Mutationen reagieren (z.B. der lokale Player stempelt
        // lastPlayedAt beim Abspielen). Das VM lebt im Backstack weiter waehrend
        // der Player offen ist → die aktuelle Ansicht wird still neu geladen,
        // damit "Zuletzt gespielt" (und watched/resume) frisch ist, sobald der
        // User zurueckkehrt. Lifecycle-unabhaengig.
        viewModelScope.launch {
            repository.itemMutated.collect { refreshCurrent() }
        }
    }

    /** Anzahl der lokalen Libs die fuer den Vergleichs-Filter ausgewaehlt
     *  sind — UI nutzt das, um den Topbar-Button nur sichtbar zu machen
     *  wenn mind. 2 Libs ausgewaehlt sind. */
    val compareLibraryCount: StateFlow<Int> =
        settingsDataStore.settings
            .map { it.compareLocalLibraryIds.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Schaltet den Vergleichs-Filter um und laedt bei "an" die Duplikat-
     *  Filenames aus der DB. Bei "aus" wird die Menge geleert. */
    fun toggleCompareMode() {
        val newOn = !_state.value.compareMode
        if (newOn) {
            viewModelScope.launch {
                val ids = try {
                    settingsDataStore.settings.first().compareLocalLibraryIds
                } catch (_: Exception) { emptySet<Int>() }
                val dupes = repository.findDuplicateFileNames(ids)
                _state.update { it.copy(compareMode = true, duplicateFileNames = dupes) }
            }
        } else {
            _state.update { it.copy(compareMode = false, duplicateFileNames = emptySet()) }
        }
    }

    private var currentLibraryId: Int = -1
    private var currentFolder: String? = null
    private var mergedLibraryIds: List<Int> = emptyList()

    /** Lädt zwei zusammengelegte lokale Bibliotheken als eine. Kombiniert Items aus beiden
     *  Libraries und zeigt sie flach (keine Ordner-Trennung zwischen den Libs). */
    fun loadMerged(ids: List<Int>) {
        if (ids.isEmpty()) return
        mergedLibraryIds = ids
        load(ids.first(), folder = null)
    }

    /** Sort/Filter-State in SharedPreferences spiegeln — wird nach jeder
     *  setX-Methode aufgerufen. */
    private fun persistFilterState() {
        val s = _state.value
        prefs.edit()
            .putString("sortMode", s.sortMode)
            .putBoolean("userPickedSort", s.userPickedSort)
            .putBoolean("sortAscending", s.sortAscending)
            .putString("watchedFilter", s.watchedFilter)
            .putStringSet("selectedBuckets", s.selectedBuckets)
            .apply()
    }

    /** Stilles Neuladen der aktuellen Ansicht OHNE Loading-Spinner — z. B. nach
     *  Rueckkehr aus dem Player, damit frisch gestempelte Werte (lastPlayedAt,
     *  watched, resume) sichtbar werden. Der Idempotenz-Guard in load() wuerde
     *  bei gleicher Lib/Folder sonst early-returnen und die stale Liste behalten.
     *  No-op solange die Lib noch nie geladen wurde (initiales load uebernimmt). */
    fun refreshCurrent() {
        if (_state.value.library == null || currentLibraryId <= 0) return
        load(currentLibraryId, currentFolder, force = true, silent = true)
    }

    fun load(libraryId: Int, folder: String?, force: Boolean = false, silent: Boolean = false) {
        // Idempotenz: wenn die gleiche Lib + Folder bereits geladen sind UND
        // der Aufruf nicht erzwungen, alles stehen lassen. Schuetzt vor
        // Scroll-Position-Reset wenn LaunchedEffect(libraryId, folder) beim
        // Re-Compose nach Navigation-Pop (Player → zurueck) erneut feuert.
        // Sort/Filter/Rename/Delete-Aufrufer setzen `force=true`.
        if (!force) {
            val st = _state.value
            val same = st.library?.id == libraryId &&
                st.currentFolder == folder &&
                !st.isLoading
            if (same) return
        }
        // Recovery fuer fehlende Frame-Thumbnails: vor v1.2.31 lagen sie in
        // cacheDir und wurden vom System aufgeraeumt. Auf einem Background-
        // Job, der das Lib-Loading nicht blockt — der observeItems-Flow
        // emittiert automatisch frisch nach jedem DB-Update.
        if (folder == null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try { repository.recoverMissingThumbnails(libraryId, appContext) } catch (_: Exception) {}
            }
        }
        // Bei Show-Wechsel (neuer Folder) currentSeason resetten — sonst
        // bleibt das ViewModel beim Wechsel zwischen Shows in einer Staffel-
        // Auswahl haengen.
        if (folder != currentFolder) _state.update { it.copy(currentSeason = null) }
        currentLibraryId = libraryId
        currentFolder = folder
        viewModelScope.launch {
            // Bei silent (Refresh nach Player-Rueckkehr) KEIN Spinner — Liste
            // wird in-place aktualisiert, Scroll-Position bleibt erhalten.
            _state.update { it.copy(isLoading = if (silent) it.isLoading else true, currentFolder = folder) }
            val lib = repository.getLibrary(libraryId)
            if (lib == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            // Owner-Check fuer Library-Navigation kommt absichtlich NICHT
            // hier hin — der Filter in der Settings-UI sorgt fuer Privacy,
            // hier blockieren wir nichts (sonst kommt es zu Admin-sperrt-
            // sich-aus-Edge-Cases). Echte Lib-Hide vs. Player-Open ist
            // unkritisch weil nur eigene Items im UI auftauchen koennen.
            // Zusammengelegte lokale Bibliotheken: Items aus ALLEN IDs kombinieren.
            val items = if (mergedLibraryIds.size > 1) {
                mergedLibraryIds.flatMap { id ->
                    try { repository.listItems(id) } catch (_: Exception) { emptyList() }
                }
            } else {
                repository.listItems(libraryId)
            }
            val baseUrl = try { settingsDataStore.settings.first().serverUrl.trimEnd('/') }
                         catch (_: Exception) { "" }
            // Flat-Pool fuer Suche: alle Items aus allen Libs, unabhaengig
            // von Folder-Struktur — damit die Suche auch Items innerhalb
            // von Channel-Ordnern findet.
            val allItemsFlat = items.map { item ->
                val posterUrl = item.posterPath?.let {
                    imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                } ?: item.thumbnailPath?.let { "file://$it" }
                LocalCard.Video(item = item, posterUrl = posterUrl)
            }

            when {
                // "Zuletzt gespielt": flache, library-weite Liste der zuletzt
                // lokal abgespielten Items — ignoriert Show-/Channel-/Staffel-
                // Struktur (Pendant zum Server-Flat-Sort). applyFilterAndSort
                // filtert auf lastPlayedAt>0 + sortiert nach Abspielzeit.
                _state.value.userPickedSort && _state.value.sortMode == LOCAL_SORT_PLAYED -> {
                    val sorted = applyFilterAndSort(items)
                    val videos = sorted.map { item ->
                        val posterUrl = item.posterPath?.let {
                            imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                        } ?: item.thumbnailPath?.let { "file://$it" }
                        LocalCard.Video(item = item, posterUrl = posterUrl)
                    }
                    _state.update {
                        it.copy(isLoading = false, library = lib, folders = emptyList(), items = videos, allItemsFlat = allItemsFlat)
                    }
                }
                // TV-Lib: Root zeigt Show-Folder (gruppiert nach showKey,
                // funktioniert mit strukturierten + flachen Libraries).
                lib.kind == "tv" && folder == null -> {
                    val folderCards = items
                        .groupBy { it.showKey() }
                        .filterKeys { it.isNotBlank() }
                        .map { (key, group) ->
                            val showTitle = group.firstOrNull { it.title != null }?.title
                            val posterPath = group.firstOrNull { it.posterPath != null }?.posterPath
                            // Frame-Thumbnail als Fallback wenn kein TMDB-Poster
                            val firstThumb = group.firstOrNull { !it.thumbnailPath.isNullOrBlank() }?.thumbnailPath
                            LocalCard.Folder(
                                name = key,
                                itemCount = group.size,
                                showTitle = showTitle,
                                posterUrl = posterPath?.let {
                                    imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                                } ?: firstThumb?.let { "file://$it" }
                            )
                        }
                        .sortedWith { a, b ->
                            naturalCompare(a.showTitle ?: a.name, b.showTitle ?: b.name)
                        }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            library = lib,
                            folders = folderCards,
                            items = emptyList(),
                            allItemsFlat = allItemsFlat
                        )
                    }
                }
                // TV-Lib + Show-Folder: Staffel-Liste oder Episoden einer
                // ausgewaehlten Staffel — gleiche Struktur wie der Browser
                // (Show → Staffel → Folge). Items ohne erkannte Staffel
                // landen unter „Ohne Staffel".
                lib.kind == "tv" && folder != null -> {
                    val scoped = items.filter {
                        it.relPath == folder ||
                            it.relPath.startsWith("$folder/") ||
                            it.showKey() == folder
                    }
                    val selectedSeason = _state.value.currentSeason
                    if (selectedSeason == null) {
                        // Staffel-Liste: gruppieren nach parsedSeason
                        val grouped = scoped.groupBy { it.parsedSeason }
                        val seasonNumbers = grouped.keys.filterNotNull().sorted()
                        val seasonCards = seasonNumbers.map { num ->
                            val eps = grouped[num].orEmpty()
                            val poster = eps.firstOrNull { it.posterPath != null }?.posterPath
                            val thumb = eps.firstOrNull { !it.thumbnailPath.isNullOrBlank() }?.thumbnailPath
                            LocalCard.Folder(
                                name = "Staffel $num",
                                itemCount = eps.size,
                                seasonNumber = num,
                                showTitle = eps.firstOrNull { it.title != null }?.title,
                                posterUrl = poster?.let {
                                    imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                                } ?: thumb?.let { "file://$it" }
                            )
                        }
                        // Items ohne erkannte Staffel als „Ohne Staffel"-Bucket
                        val orphans = grouped[null].orEmpty()
                        val orphanCard = if (orphans.isNotEmpty()) listOf(
                            LocalCard.Folder(
                                name = "Ohne Staffel",
                                itemCount = orphans.size,
                                seasonNumber = -1, // -1 = orphan-bucket
                                posterUrl = orphans.firstOrNull { !it.thumbnailPath.isNullOrBlank() }?.thumbnailPath?.let { "file://$it" }
                            )
                        ) else emptyList()
                        _state.update {
                            it.copy(
                                isLoading = false,
                                library = lib,
                                folders = seasonCards + orphanCard,
                                items = emptyList(),
                                allItemsFlat = allItemsFlat
                            )
                        }
                    } else {
                        // Episoden der ausgewaehlten Staffel — orphans bei -1
                        val episodes = if (selectedSeason == -1) {
                            scoped.filter { it.parsedSeason == null }
                        } else {
                            scoped.filter { it.parsedSeason == selectedSeason }
                        }
                        val sortedEps = applyFilterAndSort(episodes, defaultSort = LOCAL_SORT_EPISODE)
                        val videos = sortedEps.map { item ->
                            val posterUrl = item.posterPath?.let {
                                imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                            } ?: item.thumbnailPath?.let { "file://$it" }
                            LocalCard.Video(item = item, posterUrl = posterUrl)
                        }
                        _state.update {
                            it.copy(
                                isLoading = false,
                                library = lib,
                                folders = emptyList(),
                                items = videos,
                                allItemsFlat = allItemsFlat
                            )
                        }
                    }
                }
                // Privat-Lib (YouTube, Urlaubsvideos): Root zeigt
                // Kanal-Folder. Items mit Sub-Pfad werden pro Channel-Folder
                // gruppiert; flache Items (kein '/' im relPath) landen direkt
                // im Grid unten, sortiert nach Datum (neueste zuerst).
                lib.kind == "private" && folder == null -> {
                    val (nested, flat) = items.partition { it.relPath.contains('/') }
                    val folderCards = nested
                        .groupBy { it.relPath.substringBefore('/').takeIf { s -> s.isNotBlank() } ?: "" }
                        .filterKeys { it.isNotBlank() }
                        .map { (name, group) ->
                            val firstThumb = group.firstOrNull { !it.thumbnailPath.isNullOrBlank() }?.thumbnailPath
                            LocalCard.Folder(
                                name = name,
                                itemCount = group.size,
                                posterUrl = firstThumb?.let { "file://$it" }
                            )
                        }
                        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    // Flache YouTube-/Urlaubs-Files: direkt als Video-Cards
                    // mit Frame-Thumbnail; Default-Sort RELEASED, User-Sort
                    // ueberschreibt (Filter wirkt immer).
                    val sortedFlat = applyFilterAndSort(flat, defaultSort = LOCAL_SORT_RELEASED)
                    val flatVideos = sortedFlat.map { item ->
                        val posterUrl = item.thumbnailPath?.let { "file://$it" }
                        LocalCard.Video(item = item, posterUrl = posterUrl)
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            library = lib,
                            folders = folderCards,
                            items = flatVideos,
                            allItemsFlat = allItemsFlat
                        )
                    }
                }
                // Privat-Lib mit Channel-Folder: alle Items des Channels
                // sortiert nach Datum (neueste zuerst — typisches YouTube-
                // Verhalten "letzte Uploads oben").
                lib.kind == "private" && folder != null -> {
                    val scoped = items.filter {
                        it.relPath == folder || it.relPath.startsWith("$folder/")
                    }
                    val sortedScoped = applyFilterAndSort(scoped, defaultSort = LOCAL_SORT_RELEASED)
                    val videos = sortedScoped.map { item ->
                        val posterUrl = item.thumbnailPath?.let { "file://$it" }
                        LocalCard.Video(item = item, posterUrl = posterUrl)
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            library = lib,
                            folders = emptyList(),
                            items = videos,
                            allItemsFlat = allItemsFlat
                        )
                    }
                }
                // Movies-Lib oder Sub-Folder einer TV/Privat-Lib → Items flach
                else -> {
                    val scoped = if (folder != null) {
                        items.filter { it.relPath == folder || it.relPath.startsWith("$folder/") }
                    } else items
                    val sortedScoped = applyFilterAndSort(scoped)
                    val videos = sortedScoped.map { item ->
                        // TMDB-Poster hat Vorrang; ansonsten lokales Frame-
                        // Thumbnail (file://...) als Fallback — vor allem fuer
                        // Privat-Libs ohne TMDB-Match.
                        val posterUrl = item.posterPath?.let {
                            imageCache.preferLocal("https://image.tmdb.org/t/p/w342$it")
                        } ?: item.thumbnailPath?.let { "file://$it" }
                        LocalCard.Video(item = item, posterUrl = posterUrl)
                    }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            library = lib,
                            folders = emptyList(),
                            items = videos,
                            allItemsFlat = allItemsFlat
                        )
                    }
                }
            }
        }
    }

    /** Watched-Status togglen — funktioniert auch offline (rein lokal). */
    fun toggleWatched(itemId: Int) {
        viewModelScope.launch {
            val item = repository.getItem(itemId) ?: return@launch
            repository.updateItem(item.copy(watched = !item.watched))
            load(currentLibraryId, currentFolder, force = true)
        }
    }

    /** Resume-Position speichern — wird vom Player nach Seek/Pause aufgerufen. */
    fun saveResume(itemId: Int, positionSec: Double, durationSec: Double) {
        viewModelScope.launch {
            val item = repository.getItem(itemId) ?: return@launch
            // Bei >90% als watched markieren statt Resume-Position
            if (durationSec > 0 && positionSec / durationSec >= 0.9) {
                repository.updateItem(item.copy(watched = true, resumePosSec = 0.0))
            } else {
                repository.updateItem(item.copy(resumePosSec = positionSec))
            }
        }
    }

    /** Zufaelliges Item aus der aktuell geladenen Library (oder dem Folder,
     *  falls einer aktiv ist). Returnt das Item — die UI navigiert dann zum
     *  LocalPlayerRandom-Screen. */
    suspend fun randomItem(): LocalItemEntity? {
        if (mergedLibraryIds.size > 1) {
            val all = mergedLibraryIds.flatMap { id ->
                try { repository.listItems(id) } catch (_: Exception) { emptyList() }
            }
            return all.randomOrNull()
        }
        return repository.getRandomItem(currentLibraryId, currentFolder)
    }

    /** Loescht ein lokales Item (Datei auf Disk via SAF + DB-Eintrag).
     *  UI ruft das nach Bestaetigungs-Dialog auf. */
    fun deleteItem(itemId: Int, context: android.content.Context, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.deleteItemFromDisk(itemId, context)
            if (ok) load(currentLibraryId, currentFolder, force = true)
            onComplete(ok)
        }
    }

    // --- Mehrfachauswahl / Bulk-Aktionen ---

    fun toggleSelectionMode() {
        val on = !_state.value.selectionMode
        _state.update { it.copy(selectionMode = on, selectedItemIds = if (on) it.selectedItemIds else emptySet()) }
    }

    fun toggleItemSelection(itemId: Int) {
        val cur = _state.value.selectedItemIds
        _state.update {
            it.copy(selectedItemIds = if (itemId in cur) cur - itemId else cur + itemId)
        }
    }

    fun selectAllVisible() {
        val ids = _state.value.items.map { it.item.id }.toSet()
        _state.update { it.copy(selectedItemIds = ids) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedItemIds = emptySet()) }
    }

    /** Loescht alle aktuell selektierten Items von Disk + DB.
     *  onDone laeuft im Main-Dispatcher mit (geloescht, fehlgeschlagen)-Counts. */
    fun bulkDeleteSelected(context: android.content.Context, onDone: (Int, Int) -> Unit = { _, _ -> }) {
        val ids = _state.value.selectedItemIds.toList()
        if (ids.isEmpty()) { onDone(0, 0); return }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            for (id in ids) {
                if (repository.deleteItemFromDisk(id, context)) ok++ else fail++
            }
            _state.update { it.copy(selectionMode = false, selectedItemIds = emptySet()) }
            load(currentLibraryId, currentFolder, force = true)
            onDone(ok, fail)
        }
    }

    /** Benennt eine lokale Datei via SAF um + aktualisiert DB. */
    fun renameItem(itemId: Int, newName: String, context: android.content.Context, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.renameItemFromDisk(itemId, newName, context)
            if (ok) load(currentLibraryId, currentFolder, force = true)
            onComplete(ok)
        }
    }

    /** Sortier-Modus setzen + reload. Markiert die Wahl als explizit
     *  (`userPickedSort = true`), sodass sie auch dann gilt, wenn sie
     *  dem App-Default entspricht — sonst wuerde `defaultSort` in
     *  Privat-/Staffel-Views weiter ueberlagern. */
    fun setSortMode(mode: String) {
        val st = _state.value
        if (st.sortMode == mode && st.userPickedSort) return
        _state.update { it.copy(sortMode = mode, userPickedSort = true) }
        persistFilterState()
        load(currentLibraryId, currentFolder, force = true)
    }

    /** Sortier-Richtung toggeln + reload. */
    fun toggleSortDirection() {
        _state.update { it.copy(sortAscending = !it.sortAscending) }
        persistFilterState()
        load(currentLibraryId, currentFolder, force = true)
    }

    /** Watched-Filter setzen + reload. */
    fun setWatchedFilter(filter: String) {
        if (_state.value.watchedFilter == filter) return
        _state.update { it.copy(watchedFilter = filter) }
        persistFilterState()
        load(currentLibraryId, currentFolder, force = true)
    }

    /** Aufloesungs-Bucket toggeln (4K/2K/1080p/720p/576p/480p/360p). */
    fun toggleBucket(bucket: String) {
        val current = _state.value.selectedBuckets
        _state.update {
            it.copy(selectedBuckets =
                if (bucket in current) current - bucket else current + bucket
            )
        }
        persistFilterState()
        load(currentLibraryId, currentFolder, force = true)
    }

    /** Mapping von Width/Height auf Bucket-Namen (analog Server-resLabel). */
    private fun resolutionBucket(width: Int, height: Int): String {
        val effective = maxOf(height, (width * 9.0 / 16.0).toInt())
        return when {
            effective >= 2160 -> "4K"
            effective >= 1440 -> "2K"
            effective >= 1080 -> "1080p"
            effective >= 720 -> "720p"
            effective >= 576 -> "576p"
            effective >= 480 -> "480p"
            effective > 0 -> "360p"
            else -> ""
        }
    }

    /** Sortier-/Filter-Helfer fuer Item-Listen. `defaultSort` ist nur Fallback
     *  wenn der User noch den App-Default (`LOCAL_SORT_TITLE`) ausgewählt hat —
     *  sobald der User explizit eine andere Sortierung wählt, gilt seine
     *  Wahl auch in Sonderpfaden wie Staffel-Episoden oder Channel-Inhalt.
     *  Der Watched-Filter wirkt IMMER. */
    private fun applyFilterAndSort(
        items: List<LocalItemEntity>,
        defaultSort: String? = null
    ): List<LocalItemEntity> {
        val st = _state.value
        val watchedFiltered = when (st.watchedFilter) {
            LOCAL_WATCHED_UNWATCHED -> items.filter { !it.watched }
            LOCAL_WATCHED_WATCHED -> items.filter { it.watched }
            else -> items
        }
        // Aufloesungs-Filter — Items nur behalten wenn ihr Bucket in der
        // User-Auswahl ist (oder Auswahl leer = kein Filter).
        val filtered = if (st.selectedBuckets.isEmpty()) watchedFiltered
            else watchedFiltered.filter {
                resolutionBucket(it.width, it.height) in st.selectedBuckets
            }
        // User-Wahl gewinnt absolut, sobald er einmal aus dem Dropdown
        // ausgewaehlt hat. defaultSort wirkt nur initial (kein User-Pick).
        val sortKey = if (st.userPickedSort) st.sortMode
                      else (defaultSort ?: st.sortMode)
        val sorted = when (sortKey) {
            LOCAL_SORT_RELEASED -> filtered.sortedBy { it.releasedAtMs ?: it.modifiedTime }
            LOCAL_SORT_ADDED -> filtered.sortedBy { it.createdAt }
            LOCAL_SORT_DURATION -> filtered.sortedBy { it.durationSec }
            // Nur tatsaechlich (lokal) abgespielte Items, nach Abspielzeit.
            LOCAL_SORT_PLAYED -> filtered.filter { it.lastPlayedAt > 0 }.sortedBy { it.lastPlayedAt }
            LOCAL_SORT_EPISODE -> filtered.sortedWith(
                compareBy({ it.parsedSeason ?: Int.MAX_VALUE }, { it.parsedEpisode ?: Int.MAX_VALUE })
            )
            else -> filtered.sortedWith { a, b ->
                // Sortierung nach Dateiname (analog Android-Dateimanager),
                // NICHT nach TMDB-Titel/parsedTitle — damit z.B.
                // „01_intro.mp4 / 02_main.mp4 / 10_outro.mp4" oder
                // „Folge_1.mp4 / Folge_2.mp4 / Folge_10.mp4" in der
                // erwarteten natuerlichen Reihenfolge stehen. naturalCompare
                // ist case-/akzent-insensitiv via Collator.PRIMARY.
                naturalCompare(a.fileName, b.fileName)
            }
        }
        val desc = sortKey == LOCAL_SORT_RELEASED || sortKey == LOCAL_SORT_ADDED ||
                   sortKey == LOCAL_SORT_DURATION || sortKey == LOCAL_SORT_PLAYED
        val ascForKey = if (desc) !st.sortAscending else st.sortAscending
        return if (ascForKey) sorted else sorted.reversed()
    }

    /** Innerhalb einer TV-Show: Staffel auswaehlen (oder null = zurueck zur
     *  Staffel-Liste). Reload triggert die neue Render-Verzweigung. */
    fun selectSeason(season: Int?) {
        if (_state.value.currentSeason == season) return
        _state.update { it.copy(currentSeason = season) }
        load(currentLibraryId, currentFolder, force = true)
    }

    // ---------------------------------------------------------------------
    //  Manuelle Show-Korrektur (Re-Match)
    // ---------------------------------------------------------------------

    /** TMDB-Suche via Server-Proxy fuer den Re-Match-Dialog. */
    suspend fun searchShows(query: String, type: String = "tv"): List<TmdbSearchResult> =
        enricher.searchShows(query, type)

    /** Vom User gewaehlte Show auf den aktuellen Folder anwenden. Ueberschreibt
     *  bestehende Zuordnungen aller Episoden im Folder. Reloaded danach den
     *  aktuellen Screen, damit die UI die neuen Poster/Titel zeigt. */
    fun reMatchShow(hit: TmdbSearchResult, onComplete: (Int) -> Unit = {}) {
        val folder = currentFolder ?: return
        viewModelScope.launch {
            val count = enricher.reMatchShow(currentLibraryId, folder, hit)
            load(currentLibraryId, currentFolder, force = true)
            onComplete(count)
        }
    }
}
