package com.goldfish.android.data.repository

import com.goldfish.android.data.local.AppDatabase
import com.goldfish.android.data.local.LibraryCacheEntity
import com.goldfish.android.data.local.LibraryFolderCacheEntity
import com.goldfish.android.data.local.LibrarySeasonsCacheEntity
import com.goldfish.android.data.model.EpisodeInfo
import com.goldfish.android.data.model.FolderItem
import com.goldfish.android.data.model.Item
import com.goldfish.android.data.model.Library
import com.goldfish.android.data.model.Metadata
import com.goldfish.android.data.model.SeasonInfo
import com.goldfish.android.data.model.SeasonResponse
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-First-Repository: bedient alle Listen-/Detail-Aufrufe rein aus der
 * lokalen Room-DB (`downloads` + `library_cache`), ohne den Server zu
 * kontaktieren. Wird vom HomeViewModel + LibraryViewModel verwendet, wenn
 * `AppSettings.offlineOnly = true` (oder nach Netz-Fehler als Fallback).
 *
 * Anforderungen an den DB-Stand:
 *  - `DownloadEntity.itemJson` enthaelt das Moshi-serialisierte `Item`-Objekt.
 *    Wird beim Download geschrieben + per Backfill nachgezogen. Bei leerem
 *    JSON liefern wir ein Minimal-Item (nur was die DB-Spalten hergeben).
 *  - `LibraryCacheEntity` wird bei jedem erfolgreichen Server-Library-Fetch
 *    geschrieben. Im Offline-Mode lesen wir nur daraus.
 */
@Singleton
class OfflineRepository @Inject constructor(
    private val database: AppDatabase,
    moshi: Moshi
) {
    private val downloadDao = database.downloadDao()
    private val libraryCacheDao = database.libraryCacheDao()
    private val libraryFolderCacheDao = database.libraryFolderCacheDao()
    private val librarySeasonsCacheDao = database.librarySeasonsCacheDao()
    private val itemAdapter = moshi.adapter(Item::class.java)
    private val metadataAdapter = moshi.adapter(Metadata::class.java)
    private val seasonResponseAdapter = moshi.adapter(SeasonResponse::class.java)

    // -----------------------------------------------------------------------
    //  Libraries
    // -----------------------------------------------------------------------

    /** Library-Liste rein aus Cache, gefiltert auf Libs mit >=1 Download. */
    fun libraries(): Flow<List<Library>> = combine(
        libraryCacheDao.getAllCached(),
        downloadDao.getLibraryIdsWithDownloads()
    ) { cached, libIds ->
        val set = libIds.toSet()
        cached.filter { it.id in set }.map { it.toLibrary() }
    }

    /** Synchroner Cache-Read fuer load(libraryId, folder) — der combine-Flow
     *  ueber libraries() emittiert in der Startphase ggf. [] bevor beide
     *  Quell-Flows ihren ersten Wert geliefert haben, was die Library-
     *  Kind-Erkennung im LibraryViewModel zerschoss. Diese Variante geht
     *  direkt ueber den Library-Cache, ohne Filter-Combine. */
    suspend fun librariesCached(): List<Library> =
        try { libraryCacheDao.getAllCached().firstOrNull() } catch (_: Exception) { null }
            ?.map { it.toLibrary() } ?: emptyList()

    /** Cache aktualisieren — wird vom ItemRepository nach jedem erfolgreichen
     *  /api/libraries-Fetch aufgerufen. Verwaiste Libs werden geloescht. */
    suspend fun cacheLibraries(libraries: List<Library>) {
        val entities = libraries.map {
            LibraryCacheEntity(
                id = it.id,
                name = it.name,
                kind = it.kind,
                sortOrder = it.sortOrder,
                channelLabelOnTop = it.channelLabelOnTop
            )
        }
        libraryCacheDao.upsertAll(entities)
        libraryCacheDao.deleteNotIn(libraries.map { it.id })
    }

    // -----------------------------------------------------------------------
    //  Folders + Items
    // -----------------------------------------------------------------------

    /** Folder-Liste einer Library aus den Downloads-Pfaden. Ein Folder pro
     *  unterschiedlichem rel_path-Top-Segment.
     *
     *  Wenn `library_folder_cache` einen passenden Eintrag hat (vorheriger
     *  Online-Call von `/api/libraries/{id}/folders`), bekommt der FolderItem
     *  die Show-Metadata (Title, Poster) zugeordnet — sonst Fallback auf
     *  Folder-Name + Video-Thumb des ersten Items. */
    suspend fun folders(libraryId: Int): List<FolderItem> {
        val downloads = downloadDao.getDownloadsForLibraryNow(libraryId)
        val grouped = downloads.groupBy { it.relPath.split('/').firstOrNull { s -> s.isNotBlank() } ?: "" }
            .filterKeys { it.isNotBlank() }
        // Folder-Cache laden — index by folder name fuer schnelle Joins
        val cached = try {
            libraryFolderCacheDao.getForLibrary(libraryId).associateBy { it.folder }
        } catch (_: Exception) { emptyMap() }
        return grouped.map { (folderName, list) ->
            val first = list.firstOrNull()
            val cacheEntry = cached[folderName]
            FolderItem(
                folder = folderName,
                count = list.size,
                thumbItemId = first?.itemId,
                metadataId = cacheEntry?.metadataId,
                metadata = cacheEntry?.metadataJson?.let { deserializeMetadata(it) },
                drilldown = false
            )
        }.sortedBy { it.folder.lowercase() }
    }

    /** Server-Folder-Liste in den Folder-Cache schreiben. Wird aufgerufen
     *  von ItemRepository.getFolders nach erfolgreichem Server-Fetch. */
    suspend fun cacheFolders(libraryId: Int, folders: List<FolderItem>) {
        val entities = folders.map { f ->
            LibraryFolderCacheEntity(
                libraryId = libraryId,
                folder = f.folder,
                count = f.count,
                thumbItemId = f.thumbItemId,
                metadataId = f.metadataId,
                metadataJson = f.metadata?.let { metadataAdapter.toJson(it) } ?: ""
            )
        }
        libraryFolderCacheDao.upsertAll(entities)
        libraryFolderCacheDao.deleteNotIn(libraryId, folders.map { it.folder })
    }

    private fun deserializeMetadata(json: String): Metadata? {
        if (json.isBlank()) return null
        return try { metadataAdapter.fromJson(json) } catch (_: Exception) { null }
    }

    /** Items in einer Library, optional auf einen Folder eingeschraenkt
     *  (rel_path beginnt mit "<folder>/"). Items werden aus itemJson
     *  deserialisiert; fehlende JSONs werden mit Minimal-Defaults aus den
     *  DB-Spalten erzeugt (Title + libraryId + relPath, sonst Defaults). */
    suspend fun items(libraryId: Int, folder: String? = null): List<Item> {
        val downloads = downloadDao.getDownloadsForLibraryNow(libraryId)
        val scoped = if (folder != null) {
            downloads.filter {
                it.relPath == folder || it.relPath.startsWith("$folder/")
            }
        } else downloads
        return scoped.mapNotNull { d ->
            deserialize(d.itemJson) ?: minimalItem(d, libraryId)
        }
    }

    /** Flache, library-weite Liste der Downloads, sortiert fuer die Flat-Sort-
     *  Modi (played/added/duration) — Pendant zum Online-Verhalten, aber aus
     *  Room. "played" nutzt das lokale lastPlayedAt (nur tatsaechlich offline
     *  abgespielte Items), "added" das Item-addedAt, "duration" die Laufzeit.
     *  `ascending` steuert die Richtung (Default: played/added desc, duration asc).
     *  `folder` != null → nur dieser Ordner (rekursiv), sonst library-weit
     *  (Flat-Sort flacht nur nach unten). */
    suspend fun itemsSortedFlat(libraryId: Int, sort: String, ascending: Boolean, folder: String? = null): List<Item> {
        val all = downloadDao.getDownloadsForLibraryNow(libraryId)
        val downloads = if (folder != null) {
            all.filter { it.relPath == folder || it.relPath.startsWith("$folder/") }
        } else all
        val paired = downloads.mapNotNull { d ->
            val item = deserialize(d.itemJson) ?: minimalItem(d, libraryId)
            if (item != null) item to d else null
        }
        val selected = when (sort) {
            "played"   -> paired.filter { it.second.lastPlayedAt > 0 }
                                .sortedBy { it.second.lastPlayedAt }
            "added"    -> paired.sortedBy { it.first.addedAt ?: "" }
            "duration" -> paired.sortedBy { it.first.durationSec }
            else       -> paired
        }
        val ordered = if (ascending) selected else selected.reversed()
        return ordered.map { it.first }
    }

    /** SeasonResponse fuer einen Show-Folder. Wenn ein Cache-Eintrag aus einem
     *  vorherigen Server-Call vorliegt (`library_seasons_cache`), wird dieser
     *  zurueckgegeben — komplett mit TMDB-Daten (show-Info, stillPath,
     *  posterPath). Episoden werden auf den lokalen Download-Stand
     *  GEFILTERT (Offline-Mode = nur was wirklich abspielbar ist), und leere
     *  Staffeln werden weggeworfen. Owned/itemId-Flags werden frisch aus
     *  dem Download-Stand gesetzt.
     *
     *  Ohne Cache fallen wir auf die Synthese aus den lokalen Items zurueck —
     *  ohne Bilder, aber wenigstens mit Episoden-Liste. */
    suspend fun seasons(libraryId: Int, showFolder: String): SeasonResponse {
        // 1) Cache-Lookup
        val cached = try { librarySeasonsCacheDao.get(libraryId, showFolder) } catch (_: Exception) { null }
        if (cached != null && cached.seasonResponseJson.isNotBlank()) {
            val response = try { seasonResponseAdapter.fromJson(cached.seasonResponseJson) }
                catch (_: Exception) { null }
            if (response != null) {
                val items = items(libraryId, showFolder)
                val itemBySE = mutableMapOf<Pair<Int, Int>, Item>()
                for (it in items) {
                    val s = it.metadata?.season ?: continue
                    val e = it.metadata?.episode ?: continue
                    itemBySE[s to e] = it
                }
                // Offline-Mode: nur Staffeln + Episoden behalten, fuer die ein
                // lokaler Download existiert. Sonst koennte der User in
                // Staffeln/Episoden klicken, die er gar nicht abspielen kann.
                val filteredSeasons = response.seasons.mapNotNull { season ->
                    val keepEps = season.episodes.mapNotNull { ep ->
                        val item = itemBySE[ep.season to ep.episode]
                        if (item != null) ep.copy(owned = true, itemId = item.id)
                        else null
                    }
                    if (keepEps.isEmpty()) null
                    else season.copy(
                        episodes = keepEps,
                        ownedCount = keepEps.size,
                        total = keepEps.size
                    )
                }
                return response.copy(seasons = filteredSeasons)
            }
        }

        // 2) Fallback: Synthese aus lokalen Items (keine Bilder, kein Show-Header)
        val items = items(libraryId, showFolder)
        val bySeason = mutableMapOf<Int, MutableList<EpisodeInfo>>()
        for (it in items) {
            val s = it.metadata?.season ?: continue
            val e = it.metadata?.episode ?: continue
            val ep = EpisodeInfo(
                season = s,
                episode = e,
                title = it.metadata.title ?: it.title,
                overview = null,
                airDate = null,
                stillPath = null,
                owned = true,
                itemId = it.id
            )
            bySeason.getOrPut(s) { mutableListOf() }.add(ep)
        }
        val seasons = bySeason.entries.sortedBy { it.key }.map { (num, eps) ->
            val sorted = eps.sortedBy { it.episode }
            SeasonInfo(
                seasonNumber = num,
                name = "Staffel $num",
                ownedCount = sorted.size,
                total = sorted.size,
                episodes = sorted
            )
        }
        return SeasonResponse(seasons = seasons, show = null, showTmdbId = null)
    }

    /** Volltext-Suche ueber alle heruntergeladenen Items (Titel oder
     *  rel_path matched case-insensitive). Wird von der globalen Suche
     *  im HomeScreen verwendet — liefert die offline-verfuegbaren Treffer
     *  separat zu Server- + Lokal-Treffern. Max. 50 Ergebnisse. */
    suspend fun searchDownloads(query: String): List<Item> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        val all = downloadDao.getAllDownloadsNow()
        val results = mutableListOf<Item>()
        for (d in all) {
            val item = deserialize(d.itemJson) ?: minimalItem(d, d.libraryId)
            val title = item.title?.lowercase().orEmpty()
            val rel = item.relPath?.lowercase().orEmpty()
            if (title.contains(q) || rel.contains(q)) {
                results += item
                if (results.size >= 50) break
            }
        }
        return results
    }

    /** Server-SeasonResponse persistent ablegen. Wird von ItemRepository.getSeasons
     *  nach erfolgreichem Server-Call aufgerufen. */
    suspend fun cacheSeasons(libraryId: Int, folder: String, response: SeasonResponse) {
        val json = try { seasonResponseAdapter.toJson(response) } catch (_: Exception) { return }
        librarySeasonsCacheDao.upsert(
            LibrarySeasonsCacheEntity(
                libraryId = libraryId,
                folder = folder,
                seasonResponseJson = json,
                cachedAt = System.currentTimeMillis()
            )
        )
    }

    /** Einzelnes Item per ID — Offline-Variante von ItemRepository.getItem. */
    suspend fun item(itemId: Int): Item? {
        val d = downloadDao.getDownload(itemId) ?: return null
        return deserialize(d.itemJson) ?: minimalItem(d, d.libraryId)
    }

    /** Zufaelliges Item aus den Downloads, optional Lib-/Folder-gescoped. */
    suspend fun randomItem(libraryId: Int? = null, folder: String? = null): Item? {
        val pool: List<com.goldfish.android.data.local.DownloadEntity> = when {
            libraryId != null && folder != null -> downloadDao.getDownloadsForLibraryNow(libraryId)
                .filter { it.relPath == folder || it.relPath.startsWith("$folder/") }
            libraryId != null -> downloadDao.getDownloadsForLibraryNow(libraryId)
            else -> downloadDao.getDownloadsForLibraryNow(0).let { _ ->
                // Cross-library: aktuell nicht benutzt, aber als Default OK
                emptyList()
            }
        }
        val pick = pool.randomOrNull() ?: return null
        return deserialize(pick.itemJson) ?: minimalItem(pick, pick.libraryId)
    }

    // -----------------------------------------------------------------------
    //  Helfer
    // -----------------------------------------------------------------------

    private fun deserialize(json: String): Item? {
        if (json.isBlank()) return null
        return try { itemAdapter.fromJson(json) } catch (_: Exception) { null }
    }

    fun serialize(item: Item): String = itemAdapter.toJson(item)

    private fun minimalItem(d: com.goldfish.android.data.local.DownloadEntity, libraryId: Int): Item {
        // Sehr restriktiv: wir kennen nur das, was die alten DB-Spalten
        // hergeben. Reicht NICHT fuer Detail/Player-Resume, aber zeigt
        // wenigstens eine Kachel mit Titel. Backfill fuellt itemJson nach.
        return Item(
            id = d.itemId,
            title = d.title,
            libraryId = libraryId,
            relPath = d.relPath,
            sizeBytes = d.fileSize
        )
    }
}

private fun LibraryCacheEntity.toLibrary(): Library = Library(
    id = id,
    name = name,
    kind = kind,
    sortOrder = sortOrder,
    channelLabelOnTop = channelLabelOnTop
)
