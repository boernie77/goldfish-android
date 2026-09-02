package com.goldfish.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goldfish.android.data.local.LocalEnricher
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.local.LocalScanner
import com.goldfish.android.data.local.ScanProgress
import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.repository.AuthRepository
import com.goldfish.android.data.repository.LocalLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel fuer die "Lokale Bibliotheken"-Sektion in den Einstellungen.
 */
@HiltViewModel
class LocalLibrariesViewModel @Inject constructor(
    private val repository: LocalLibraryRepository,
    private val scanner: LocalScanner,
    private val enricher: LocalEnricher,
    private val settingsDataStore: SettingsDataStore,
    private val authRepository: AuthRepository
) : ViewModel() {

    /** Aktuell im Vergleichs-Filter selektierte Lib-IDs (aus DataStore). */
    val compareLibraryIds: StateFlow<Set<Int>> =
        settingsDataStore.settings
            .map { it.compareLocalLibraryIds }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleCompareLibrary(libId: Int) {
        viewModelScope.launch {
            val current = compareLibraryIds.value
            val next = if (libId in current) current - libId else current + libId
            settingsDataStore.saveCompareLocalLibraryIds(next)
        }
    }

    /** "Meine" lokale Lib-Liste — strikt nach Owner gefiltert.
     *  KEIN Auto-Claim mehr (war Bug: hat Libs an den falschen User
     *  zugeordnet wenn der Familien-User zuerst eingeloggt war).
     *  Stattdessen sieht jeder seine eigenen + kann ueber otherLibraries
     *  fremde uebernehmen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val libraries: StateFlow<List<LocalLibraryEntity>> =
        authRepository.authStatus
            .flatMapLatest { status ->
                val u = status?.username?.takeIf { it.isNotBlank() && status.isAuthenticated }
                if (u == null) flowOf(emptyList()) else repository.observeLibrariesForUser(u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Andere lokale Bibliotheken — gehoeren anderen Usern oder sind noch
     *  unbeansprucht (Legacy-NULL nach DB-Migration). User kann sie hier
     *  uebernehmen falls sie eigentlich ihm gehoeren. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val otherLibraries: StateFlow<List<LocalLibraryEntity>> =
        authRepository.authStatus
            .flatMapLatest { status ->
                val u = status?.username?.takeIf { it.isNotBlank() && status.isAuthenticated }
                if (u == null) flowOf(emptyList()) else repository.observeOtherLibraries(u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Aktueller Username — UI zeigt ihn im "Andere Bibliotheken"-Header
     *  und braucht ihn fuer den Reassign-Click. */
    val currentUsername: StateFlow<String?> =
        authRepository.authStatus
            .map { it?.username?.takeIf { u -> u.isNotBlank() && it.isAuthenticated == true } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "Mir zuordnen"-Action — uebertraegt eine fremde Lib an den
     *  aktuell eingeloggten User. */
    fun claimLibrary(libraryId: Int) {
        viewModelScope.launch {
            val u = currentUsername.value ?: return@launch
            try { repository.reassignOwner(libraryId, u) } catch (_: Exception) {}
        }
    }

    val scanProgress: StateFlow<Map<Int, ScanProgress>> = scanner.progress

    /** Cache fuer Item-Counts (libraryId → count) — fuer UI-Anzeige
     *  "N Videos". Wird nach jedem Scan aktualisiert. */
    private val _counts = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, Int>>(emptyMap())
    val counts: StateFlow<Map<Int, Int>> = _counts

    init {
        // Initial-Counts laden, sobald Libraries emitten
        viewModelScope.launch {
            libraries.collect { libs ->
                val map = mutableMapOf<Int, Int>()
                for (l in libs) map[l.id] = repository.countItems(l.id)
                _counts.value = map
            }
        }
    }

    /** Wird vom SAF-Folder-Picker aufgerufen. Scan + Anreicherung
     *  laufen automatisch wenn `thenScan=true`. */
    fun addLibrary(
        name: String,
        kind: String,
        treeUri: String,
        displayName: String,
        thenScan: Boolean = true
    ) {
        viewModelScope.launch {
            // Neue Lib gehoert dem aktuellen User. Falls niemand eingeloggt:
            // wir refreshen erst noch via checkAuthStatus (kann sein dass
            // der initial-StateFlow noch null ist, der Cookie aber gueltig).
            // Erst danach abbrechen — sonst landet die Lib mit owner=null
            // und der One-shot-Claim wuerde sie nicht mehr abholen.
            var owner = authRepository.authStatus.value?.username
                ?.takeIf { it.isNotBlank() && authRepository.authStatus.value?.isAuthenticated == true }
            if (owner.isNullOrBlank()) {
                val refreshed = try { authRepository.checkAuthStatus() } catch (_: Exception) { null }
                owner = refreshed?.username?.takeIf { it.isNotBlank() && refreshed.isAuthenticated }
            }
            if (owner.isNullOrBlank()) {
                android.util.Log.w("GF-LocalLibs", "addLibrary aborted: no current user")
                return@launch
            }
            val newId = repository.addLibrary(name, kind, treeUri, displayName, owner)
            if (thenScan) {
                val lib = repository.getLibrary(newId)
                if (lib != null) runScanAndEnrich(lib)
                _counts.value = _counts.value + (newId to repository.countItems(newId))
            }
        }
    }

    fun renameLibrary(id: Int, newName: String) {
        viewModelScope.launch { repository.renameLibrary(id, newName) }
    }

    fun changeKind(id: Int, newKind: String) {
        viewModelScope.launch { repository.changeKind(id, newKind) }
    }

    fun deleteLibrary(id: Int) {
        viewModelScope.launch {
            repository.deleteLibrary(id)
            scanner.clearProgress(id)
            _counts.value = _counts.value - id
        }
    }

    fun scanLibrary(id: Int) {
        viewModelScope.launch {
            val lib = repository.getLibrary(id) ?: return@launch
            runScanAndEnrich(lib)
            _counts.value = _counts.value + (id to repository.countItems(id))
        }
    }

    /** Scan → TMDB-Anreicherung als zwei Phasen mit gemeinsamer Progress-
     *  Anzeige. Anreicherung scheitert lautlos bei Offline / fehlendem
     *  TMDB-Key — UI zeigt trotzdem das fertige Scan-Ergebnis. */
    private suspend fun runScanAndEnrich(lib: LocalLibraryEntity) {
        scanner.scan(lib)
        // Wenn Scan erfolgreich + Lib ist nicht "private" + Items vorhanden:
        // Anreicherung anstoßen, Progress separat als "enriching" anzeigen.
        val items = repository.listItems(lib.id)
        if (lib.kind != "private" && items.isNotEmpty()) {
            scanner.setProgress(lib.id, ScanProgress(
                libraryId = lib.id,
                running = true,
                phase = "enriching",
                totalFiles = items.size,
                currentFile = "Metadaten suchen…"
            ))
            try { enricher.enrich(lib) } catch (_: Exception) {}
            // Final-State setzen (UI zeigt "✓ Scan fertig")
            scanner.setProgress(lib.id, ScanProgress(
                libraryId = lib.id,
                running = false,
                phase = "done",
                totalFiles = items.size,
                processedFiles = items.size
            ))
        }
    }

    fun dismissScanResult(id: Int) {
        scanner.clearProgress(id)
    }
}
