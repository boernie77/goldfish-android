package com.goldfish.android.data.local

import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.cache.ImageCache
import com.goldfish.android.data.model.TmdbSearchResult
import com.goldfish.android.data.repository.LocalLibraryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reichert lokale Items mit TMDB-Metadaten an — ueber den Goldfish-
 * Server-Proxy /api/metadata/search. Wenn die App offline ist oder der
 * Server keinen TMDB-Key hat, scheitert das lautlos und Items behalten
 * nur den geparsten Titel.
 *
 * - Movies-Lib: pro Item → SearchMovie(parsedTitle, parsedYear)
 * - TV-Lib: gruppiert nach Top-Folder → SearchTV(folderName) → alle
 *   Episoden des Folders bekommen die Show-Metadata zugeordnet
 *   (Episoden-spezifische Stills brauchen separate API-Calls — Etappe 5+)
 * - Private-Lib: kein TMDB
 *
 * Pre-cached die Poster-URLs via ImageCache, damit sie offline angezeigt
 * werden koennen (TMDB-CDN-URLs https://image.tmdb.org/t/p/w342{path}).
 */
@Singleton
class LocalEnricher @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val repository: LocalLibraryRepository,
    private val imageCache: ImageCache
) {
    /** Reichert alle Items einer Library an. Best-effort: scheitert lautlos
     *  bei Netz-/Auth-Fehlern. Bestehende Anreicherungen werden NICHT
     *  ueberschrieben (idempotent) — der User kann manuell "neu anreichern"
     *  ausloesen, indem er die Library loescht + neu hinzufuegt. */
    suspend fun enrich(library: LocalLibraryEntity) {
        if (library.kind == "private") return  // Privat-Libs: keine TMDB-Suche
        val items = repository.listItems(library.id)
        if (items.isEmpty()) return

        when (library.kind) {
            "movies" -> enrichMovies(items)
            "tv" -> enrichTv(items)
        }
    }

    private suspend fun enrichMovies(items: List<LocalItemEntity>) {
        // Pro Item: search + apply
        for (item in items) {
            if (item.metadataId != null) continue  // schon angereichert
            val title = item.parsedTitle.takeIf { it.isNotBlank() } ?: continue
            val hit = searchFirst(title, type = "movie", year = item.parsedYear) ?: continue
            applyHit(item, hit)
        }
    }

    private suspend fun enrichTv(items: List<LocalItemEntity>) {
        // Gruppieren nach Top-Folder (= Show). Folder-Name als Such-Query.
        // Items ohne Top-Folder werden uebersprungen (TV-Episode ohne Show-
        // Ordner ist im aktuellen Schema un-typisch).
        val groups = items.groupBy { it.relPath.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .filterKeys { it != null } as Map<String, List<LocalItemEntity>>

        for ((folderName, group) in groups) {
            // Wenn schon mindestens ein Item der Gruppe Metadata hat, alle
            // andere Items der Gruppe diese erben lassen (verhindert
            // doppelte API-Calls bei Re-Enrich).
            val existingHit = group.firstOrNull { it.metadataId != null }
            val hit = if (existingHit != null) {
                // Vorhandene Daten verwenden, kein API-Call mehr
                TmdbSearchResult(
                    tmdbType = "tv",
                    id = (existingHit.metadataId ?: 0).toLong(),
                    title = existingHit.title ?: folderName,
                    year = existingHit.year ?: 0,
                    overview = existingHit.overview ?: "",
                    rating = existingHit.rating ?: 0.0,
                    posterPath = existingHit.posterPath
                )
            } else {
                // Folder-Name als Query — NameParser kann auch Show-Namen
                // aus "Game.of.Thrones.S01-S08.Complete.German" extrahieren.
                val showName = NameParser.parseFolder(folderName).title.takeIf { it.isNotBlank() }
                    ?: folderName
                searchFirst(showName, type = "tv", year = null) ?: continue
            }

            // Allen Episoden im Folder die Show-Metadata zuweisen
            for (item in group) {
                if (item.metadataId != null && item.metadataId.toLong() == hit.id) continue
                applyHit(item, hit)
            }
            // Show-Poster pre-cachen (einmal pro Folder, nicht pro Episode)
            prefetchPoster(hit.posterPath)
        }
    }

    private suspend fun searchFirst(title: String, type: String, year: Int?): TmdbSearchResult? {
        return try {
            val resp = apiClientProvider.api.searchMetadata(title, type, year)
            if (!resp.isSuccessful) return null
            resp.body()?.firstOrNull()
        } catch (_: Exception) { null }
    }

    private suspend fun applyHit(item: LocalItemEntity, hit: TmdbSearchResult) {
        // Frisch re-lesen, BEVOR wir die ganze Zeile zurueckschreiben — der
        // `item`-Snapshot aus dem Enrichment-Lauf koennte veraltet sein und
        // parallel gesetzte Live-Felder (lastPlayedAt vom Player-Stempel,
        // watched, resumePosSec) auf alte Werte clobbern.
        val fresh = repository.getItem(item.id) ?: item
        repository.updateItem(fresh.copy(
            metadataId = hit.id.toInt(),
            title = hit.title.takeIf { it.isNotBlank() },
            year = hit.year.takeIf { it > 0 },
            overview = hit.overview.takeIf { it.isNotBlank() },
            rating = hit.rating.takeIf { it > 0.0 },
            posterPath = hit.posterPath
        ))
        prefetchPoster(hit.posterPath)
    }

    private suspend fun prefetchPoster(posterPath: String?) {
        if (posterPath.isNullOrBlank()) return
        try { imageCache.cache("https://image.tmdb.org/t/p/w342$posterPath") } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------
    //  Manuelle Re-Zuordnung (vom User initiiert)
    // ---------------------------------------------------------------------

    /** Server-Search-Proxy direkt durchreichen — fuer den Re-Match-Dialog
     *  zeigt die UI eine Trefferliste, der User waehlt eine Show aus. */
    suspend fun searchShows(query: String, type: String = "tv"): List<TmdbSearchResult> {
        if (query.isBlank()) return emptyList()
        return try {
            val resp = apiClientProvider.api.searchMetadata(query, type, null)
            if (!resp.isSuccessful) emptyList() else resp.body().orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    /** Wendet einen vom User gewaehlten TmdbSearchResult auf ALLE Items in
     *  einem Show-Folder an — auch auf bereits angereicherte. Im Gegensatz
     *  zu `enrichTv()` ueberschreibt diese Methode bestehende Zuordnungen
     *  (das ist der ganze Zweck der Korrektur). */
    suspend fun reMatchShow(
        libraryId: Int,
        folder: String,
        hit: TmdbSearchResult
    ): Int {
        val items = repository.itemsInFolder(libraryId, folder)
        if (items.isEmpty()) return 0
        for (item in items) {
            applyHit(item, hit)
        }
        return items.size
    }
}
