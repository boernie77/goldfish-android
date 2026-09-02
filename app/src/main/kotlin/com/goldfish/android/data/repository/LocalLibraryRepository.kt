package com.goldfish.android.data.repository

import android.content.Context
import android.util.Log
import com.goldfish.android.data.local.LocalAppDatabase
import com.goldfish.android.data.local.LocalItemEntity
import com.goldfish.android.data.local.LocalLibraryEntity
import com.goldfish.android.data.local.MediaProbe
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository fuer lokale (on-device) Bibliotheken.
 *
 * - Library-CRUD: hinzufuegen/loeschen/umbenennen
 * - Scan-Resultate persistieren (LocalItem)
 * - Lese-Helfer fuer LocalLibraryViewModel
 *
 * In Etappe 1 ist hier nur das Minimum (Add/List/Delete/Update) — Scan +
 * TMDB-Enrichment kommen in Etappe 2+.
 */
@Singleton
class LocalLibraryRepository @Inject constructor(
    private val database: LocalAppDatabase
) {
    private val libraryDao = database.libraryDao()
    private val itemDao = database.itemDao()

    // Emittiert nach jeder Item-Mutation (update/upsert/delete) → das
    // LocalLibraryViewModel beobachtet das und laedt die aktuelle Ansicht still
    // neu. Wichtig fuer "Zuletzt gespielt": der Player stempelt lastPlayedAt via
    // updateItem, waehrend das Lib-VM im Backstack lebt — ohne dieses Signal
    // bliebe die Liste stale (kein DB-Flow, Idempotenz-Guard in load()).
    private val _itemMutated = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val itemMutated: SharedFlow<Unit> = _itemMutated.asSharedFlow()

    // --- Libraries ---

    /** Veraltet: liefert ALLE Libs ohne User-Filter. Nur fuer interne Recovery-
     *  Jobs (z.B. recoverMissingThumbnails) verwenden — UI muss user-gefiltert
     *  arbeiten via observeLibrariesForUser. */
    fun observeLibraries(): Flow<List<LocalLibraryEntity>> = libraryDao.observeAll()

    /** User-isolierter Flow: nur Libs deren ownerUsername passt. NULL-Libs
     *  sind hier NICHT inkludiert — sie werden ueber observeNotForUser
     *  exposed (als "Andere Bibliotheken"-Section in Settings). */
    fun observeLibrariesForUser(username: String): Flow<List<LocalLibraryEntity>> =
        libraryDao.observeForUser(username)

    /** Fremde Libs (anderer User oder noch NULL). Wird in Settings als
     *  "Andere Bibliotheken"-Block gerendert, mit einem "Mir zuordnen"-
     *  Button pro Eintrag. So kann der Admin nach Update Libs zurueck-
     *  holen, die der falsche User zuerst geclaimt hat. */
    fun observeOtherLibraries(username: String): Flow<List<LocalLibraryEntity>> =
        libraryDao.observeNotForUser(username)

    /** Ownership-Reassign — wird vom "Mir zuordnen"-Button gerufen. */
    suspend fun reassignOwner(libraryId: Int, newOwner: String?) {
        libraryDao.setOwner(libraryId, newOwner)
    }

    suspend fun getLibrary(id: Int): LocalLibraryEntity? = libraryDao.get(id)

    /** Neue lokale Library anlegen — automatisch dem aktuellen User zugeordnet.
     *  Returns the inserted row's ID. */
    suspend fun addLibrary(
        name: String,
        kind: String,
        treeUri: String,
        displayName: String,
        ownerUsername: String?
    ): Int {
        val entity = LocalLibraryEntity(
            name = name,
            kind = kind,
            treeUri = treeUri,
            displayName = displayName,
            createdAt = System.currentTimeMillis(),
            ownerUsername = ownerUsername
        )
        return libraryDao.upsert(entity).toInt()
    }

    suspend fun renameLibrary(id: Int, newName: String) {
        val existing = libraryDao.get(id) ?: return
        libraryDao.update(existing.copy(name = newName))
    }

    suspend fun changeKind(id: Int, newKind: String) {
        val existing = libraryDao.get(id) ?: return
        libraryDao.update(existing.copy(kind = newKind))
    }

    /** Library + alle ihre Items loeschen. */
    suspend fun deleteLibrary(id: Int) {
        itemDao.deleteAllForLibrary(id)
        libraryDao.deleteById(id)
    }

    // --- Items ---

    fun observeItems(libraryId: Int): Flow<List<LocalItemEntity>> =
        itemDao.observeForLibrary(libraryId)

    suspend fun listItems(libraryId: Int): List<LocalItemEntity> =
        itemDao.listForLibrary(libraryId)

    suspend fun getItem(id: Int): LocalItemEntity? = itemDao.get(id)

    suspend fun upsertItem(item: LocalItemEntity): Int = itemDao.upsert(item).toInt()

    suspend fun updateItem(item: LocalItemEntity) {
        itemDao.update(item)
        _itemMutated.tryEmit(Unit)
    }

    /** Setzt NUR den lastPlayedAt-Stempel (gezieltes Spalten-Update, damit kein
     *  paralleler Ganzzeilen-Write ihn clobbert) + signalisiert die Mutation. */
    suspend fun markLocalPlayed(itemId: Int, ts: Long) {
        itemDao.setLastPlayed(itemId, ts)
        _itemMutated.tryEmit(Unit)
    }

    suspend fun deleteItem(id: Int) = itemDao.deleteById(id)

    /**
     * Liefert die Dateinamen, die in mindestens 2 der angegebenen Bibliotheken
     * vorkommen — Backing-Daten fuer den Vergleichs-Filter im LocalLibraryScreen.
     * Bei <2 Libs trivialerweise leer.
     */
    suspend fun findDuplicateFileNames(libIds: Collection<Int>): Set<String> {
        if (libIds.size < 2) return emptySet()
        return try {
            itemDao.findDuplicateFileNames(libIds.toList()).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Recovery fuer fehlende Frame-Thumbnails. Vor dem fix in v1.2.31 lagen
     * die JPEGs in `cacheDir`, das Android unter Speicherdruck loescht —
     * Folge: Items hatten thumbnailPath gesetzt, die Datei war aber weg, und
     * Coil zeigte nichts. Wird beim Oeffnen einer Library im Background
     * aufgerufen: prueft jeden Item-Pfad, wenn die Datei fehlt, extrahiert
     * den Frame neu (jetzt in filesDir/local-thumbs). Idempotent + billig
     * wenn alles existiert.
     */
    suspend fun recoverMissingThumbnails(libraryId: Int, context: Context) {
        val items = try { itemDao.listForLibrary(libraryId) } catch (e: Exception) {
            Log.w(TAG, "recoverMissingThumbnails list failed: ${e.message}")
            return
        }
        for (item in items) {
            // Wir versuchen Extraktion in 3 Faellen:
            //  - thumbnailPath gesetzt, aber File weg (alter cacheDir-Bug)
            //  - thumbnailPath null UND kein TMDB-Poster (neu probieren — der
            //    Scan hatte vielleicht MediaMetadataRetriever-Fehler, der
            //    jetzt mit dem FFmpeg-Fallback (nextlib) klappt)
            val existingFileMissing = item.thumbnailPath?.let {
                val f = File(it); !(f.exists() && f.length() > 0)
            } ?: false
            val noPosterFallback = item.thumbnailPath.isNullOrBlank() &&
                item.posterPath.isNullOrBlank()
            if (!existingFileMissing && !noPosterFallback) continue

            val atSec = when {
                item.durationSec > 30 -> (item.durationSec * 0.10).toLong()
                item.durationSec > 5 -> 2L
                else -> 0L
            }
            val newPath = try { MediaProbe.extractFrame(context, item.documentUri, atSec) }
                          catch (e: Exception) {
                              Log.w(TAG, "re-extractFrame failed for ${item.fileName}: ${e.message}")
                              null
                          }
            try {
                // NUR thumbnailPath setzen — NICHT die ganze (evtl. veraltete)
                // Zeile zurueckschreiben, sonst wuerde ein parallel gesetztes
                // lastPlayedAt (Player-Stempel) auf den Snapshot-Wert geclobbert.
                itemDao.setThumbnailPath(item.id, newPath)
            } catch (e: Exception) {
                Log.w(TAG, "DB update after re-extract failed: ${e.message}")
            }
        }
    }

    companion object { private const val TAG = "GF-LocalLib" }

    /** Loescht alle DB-Eintraege in einer Library, deren `documentUri` NICHT
     *  in `keepUris` enthalten ist. Wird vom LocalScanner nach Phase 2
     *  aufgerufen, damit auf Disk geloeschte Files auch aus der App-DB
     *  verschwinden (Orphan-Cleanup, analog Server-Scanner). Returnt die
     *  Anzahl tatsaechlich entfernter Eintraege. */
    suspend fun deleteOrphans(libraryId: Int, keepUris: List<String>): Int {
        if (keepUris.isEmpty()) {
            // Wenn der Scan gar nichts gefunden hat: alle Items der Lib
            // entfernen. Speziell behandelt weil Room/SQLite `NOT IN ()`
            // mit leerer Liste unerwartet handhabt.
            val n = itemDao.countForLibrary(libraryId)
            itemDao.deleteAllForLibrary(libraryId)
            return n
        }
        return itemDao.deleteOrphansForLibrary(libraryId, keepUris)
    }

    /** Benennt eine lokale Datei via SAF um (gleicher Ordner, neuer Name).
     *  Die documentUri aendert sich nach dem Rename — Room-DB wird mit der
     *  neuen URI + neuem fileName + neu geparstem `parsedTitle/Year/Season/
     *  Episode` aktualisiert. TMDB-Felder (title, year, posterPath, …)
     *  bleiben unangetastet damit ein bestehender Match nicht verloren geht.
     *  Returns true bei Erfolg. */
    suspend fun renameItemFromDisk(itemId: Int, newName: String, context: android.content.Context): Boolean {
        val item = itemDao.get(itemId) ?: return false
        val sanitized = sanitizeFileName(newName).ifBlank { return false }
        if (sanitized == item.fileName) return true  // no-op
        return try {
            val oldUri = android.net.Uri.parse(item.documentUri)
            val newUri = android.provider.DocumentsContract.renameDocument(
                context.contentResolver, oldUri, sanitized
            ) ?: return false
            val newRelPath = if (item.relPath.contains('/')) {
                item.relPath.substringBeforeLast('/') + "/" + sanitized
            } else {
                sanitized
            }
            // parsedTitle/Year/Season/Episode aus dem neuen Filename neu
            // berechnen, damit die VideoCard-Anzeige sofort den neuen Stand
            // zeigt (VideoCard zeigt `title ?: parsedTitle`). Ohne dieses
            // Re-Parse bleibt parsedTitle vom alten Dateinamen haengen.
            val parsed = com.goldfish.android.data.local.NameParser.parseFile(sanitized)
            itemDao.update(item.copy(
                documentUri = newUri.toString(),
                fileName = sanitized,
                relPath = newRelPath,
                parsedTitle = parsed.title.ifBlank { sanitized.substringBeforeLast('.', sanitized) },
                parsedYear = parsed.year.takeIf { it > 0 } ?: item.parsedYear,
                parsedSeason = parsed.season.takeIf { parsed.isEpisode } ?: item.parsedSeason,
                parsedEpisode = parsed.episode.takeIf { parsed.isEpisode } ?: item.parsedEpisode
            ))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Verbotene Zeichen in Dateinamen ersetzen + Whitespace trimmen. */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.')
        return cleaned
    }

    /** Loescht ein lokales Item KOMPLETT — die echte Datei via SAF
     *  (DocumentFile.delete) und den DB-Eintrag. Best-effort: scheitert
     *  die Datei-Loeschung (z.B. SAF-Permission inzwischen widerrufen),
     *  bleibt der DB-Eintrag stehen damit der User sieht dass was nicht
     *  klappte. Returnt true bei Erfolg.
     *
     *  HINWEIS: braucht `Context` zum Aufloesen der SAF-URI — wird via
     *  Param uebergeben, weil das Repository sonst kein Context-Dep hat. */
    suspend fun deleteItemFromDisk(itemId: Int, context: android.content.Context): Boolean {
        val item = itemDao.get(itemId) ?: return false
        return try {
            val uri = android.net.Uri.parse(item.documentUri)
            val ok = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete() == true
            if (ok) {
                itemDao.deleteById(itemId)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun countItems(libraryId: Int): Int = itemDao.countForLibrary(libraryId)

    /** Existiert dieses Item schon in der Library? Wird vom Scanner verwendet
     *  um Re-Scans inkrementell zu halten (vorhandene Files behalten ihre
     *  watched/resumePosSec/favorite-Werte). */
    suspend fun findItemByUri(libraryId: Int, uri: String): LocalItemEntity? =
        itemDao.findByUri(libraryId, uri)

    /** Items, die der angegebenen Show/dem Folder zugeordnet sind.
     *  Matched auf drei Wegen, damit sowohl strukturierte (Show/Episode.mkv)
     *  als auch flache (Show.S01E01.mkv) Libraries funktionieren:
     *   1. relPath ist exakt der Folder
     *   2. relPath startet mit "<folder>/" — strukturierte Sub-Folder
     *   3. item.showKey() == folder — flache Libs (Show-Titel als Key) */
    suspend fun itemsInFolder(libraryId: Int, folder: String): List<LocalItemEntity> {
        val all = itemDao.listForLibrary(libraryId)
        if (folder.isBlank()) return all
        return all.filter {
            it.relPath == folder ||
                it.relPath.startsWith("$folder/") ||
                it.showKey() == folder
        }
    }

    /** Volltext-Suche ueber ALLE lokalen Libraries — fuer die globale
     *  Suchfunktion im HomeScreen. Matched case-insensitive auf Titel
     *  (TMDB-Title), parsedTitle und Dateinamen. Returns max. 50 Treffer. */
    suspend fun searchAllItems(query: String, username: String?): List<LocalItemEntity> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        // Search durchsucht ALLE Libs auf der DB-Ebene und filtert
        // anschliessend auf Owner — damit selbst wenn der User-Kontext
        // gerade `null` ist (z.B. wahrend des Auth-Loadings), keine
        // Server-Anomalie passiert: einfach 0 Treffer statt Crash.
        // Legacy-Libs (ownerUsername=null) sind weiterhin durchsuchbar
        // bis sie ueber Settings zugeordnet wurden.
        val allLibs = libraryDao.observeAll().firstOrNull() ?: emptyList()
        val visibleLibs = allLibs.filter {
            it.ownerUsername.isNullOrBlank() || it.ownerUsername == username
        }
        val results = mutableListOf<LocalItemEntity>()
        for (lib in visibleLibs) {
            val items = itemDao.listForLibrary(lib.id)
            for (it in items) {
                val matched = it.title?.lowercase()?.contains(q) == true ||
                    it.parsedTitle.lowercase().contains(q) ||
                    it.fileName.lowercase().contains(q)
                if (matched) results += it
                if (results.size >= 50) return results
            }
        }
        return results
    }

    /** Zufaelliges Item aus einer Library, optional auf einen Top-Folder
     *  eingeschraenkt. Null wenn die Library/der Folder leer ist. */
    suspend fun getRandomItem(libraryId: Int, folder: String? = null): LocalItemEntity? {
        val pool = if (folder.isNullOrBlank()) itemDao.listForLibrary(libraryId)
                   else itemsInFolder(libraryId, folder)
        return pool.randomOrNull()
    }
}
