package com.goldfish.android.data.repository

import com.goldfish.android.data.SettingsDataStore
import com.goldfish.android.data.api.ApiCache
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.*
import kotlinx.coroutines.flow.first
import com.goldfish.android.data.model.CastMember
import com.goldfish.android.data.trickplay.TrickplayFrame
import com.goldfish.android.data.trickplay.TrickplayParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}

@Singleton
class ItemRepository @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val cache: ApiCache,
    private val offlineRepository: OfflineRepository,
    private val settingsDataStore: SettingsDataStore,
    private val downloadRepository: DownloadRepository,
    private val imageCache: com.goldfish.android.data.cache.ImageCache
) {
    /**
     * Wird emitted nachdem eine Mutation auf einem Item stattgefunden hat
     * (Watched, Favorite, Resume). DetailViewModel observt das, um seinen
     * State frisch zu halten — wichtig für den Resume-Dialog nach Player-Close.
     */
    private val _itemUpdated = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val itemUpdated: SharedFlow<Int> = _itemUpdated.asSharedFlow()

    private suspend inline fun <T : Any> cached(
        key: String,
        ttlMs: Long = 30_000L,
        crossinline fetch: suspend () -> Result<T>
    ): Result<T> {
        cache.get<T>(key)?.let { return Result.Success(it) }
        val r = fetch()
        if (r is Result.Success) cache.put(key, r.data, ttlMs)
        return r
    }

    suspend fun getHome(): Result<HomeResponse> = cached("home") {
        try {
            val response = apiClientProvider.api.getHome()
            if (response.isSuccessful) Result.Success(response.body() ?: HomeResponse())
            else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getLibraries(): Result<List<Library>> {
        // Backfill bei jedem Call anstossen (nicht nur bei Cache-Miss), damit
        // bestehende Legacy-Downloads zuverlaessig nachgezogen werden, sobald
        // der ApiClient erreichbar ist. triggerBackfill() ist re-entrancy-
        // sicher (skippt wenn schon laeuft).
        try { downloadRepository.triggerBackfill() } catch (_: Exception) {}
        return cached("libraries", ttlMs = 5 * 60_000L) {
            try {
                val response = apiClientProvider.api.getLibraries()
                if (response.isSuccessful) {
                    val libs = response.body() ?: emptyList()
                    try { offlineRepository.cacheLibraries(libs) } catch (_: Exception) {}
                    Result.Success(libs)
                }
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }
    }

    suspend fun getRandomItem(
        libraryId: Int? = null,
        folder: String? = null,
        watched: String? = null,
        libraryIds: List<Int>? = null   // mehrere Libraries (virtuelle Zusammenlegung)
    ): Item? {
        // Offline-Mode: rein aus DB picken (kein Server-Call, kein DNS-Lookup).
        val offline = try { settingsDataStore.settings.first().offlineOnly } catch (_: Exception) { false }
        if (offline) {
            // Mehrere Libs offline: zufällig aus einem der Pools wählen
            if (!libraryIds.isNullOrEmpty()) {
                val allItems = libraryIds.flatMap { id ->
                    try { offlineRepository.items(id) } catch (_: Exception) { emptyList() }
                }
                return allItems.randomOrNull()
            }
            return offlineRepository.randomItem(libraryId = libraryId, folder = folder)
        }
        val ids = when {
            !libraryIds.isNullOrEmpty() -> libraryIds
            libraryId != null -> listOf(libraryId)
            else -> null
        }
        return try {
            val response = apiClientProvider.api.getRandomItem(ids, folder, watched)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) { null }
    }

    /** Globale Suche ueber ALLE Server-Libraries — wird von der App-weiten
     *  Suchfunktion verwendet. Ohne libraryId-Filter durchsucht der Server
     *  alle Libs, auf die der eingeloggte User Zugriff hat. */
    suspend fun searchAcrossLibraries(query: String): Result<List<Item>> {
        if (query.isBlank()) return Result.Success(emptyList())
        return try {
            val response = apiClientProvider.api.getItems(search = query)
            if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
            else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getItems(
        libraryId: Int? = null,
        folder: String? = null,
        sort: String? = null,
        dir: String? = null,
        search: String? = null,
        watched: String? = null,
        favorite: String? = null,
        bucket: List<String>? = null,
        libraryIds: List<Int>? = null   // mehrere Libraries (virtuelle Zusammenlegung)
    ): Result<List<Item>> {
        val effectiveIds = when {
            !libraryIds.isNullOrEmpty() -> libraryIds
            libraryId != null -> listOf(libraryId)
            else -> null
        }
        // Bei aktiver Suche kein Cache
        val key = if (!search.isNullOrBlank()) null
        else "items:${effectiveIds?.joinToString(",")}:$folder:$sort:$dir:$watched:$favorite:${bucket?.joinToString(",")}"

        if (key != null) cache.get<List<Item>>(key)?.let { return Result.Success(it) }
        return try {
            val response = apiClientProvider.api.getItems(effectiveIds, folder, sort, dir, search, watched, favorite, bucket)
            if (response.isSuccessful) {
                val data = response.body() ?: emptyList()
                if (key != null) cache.put(key, data)
                Result.Success(data)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    /** Markiert ein Item server-seitig als "zuletzt gespielt"
     *  (POST /api/items/{id}/played → user_item_state.last_played_at = now).
     *  Wie der Browser beim Player-Open. Invalidiert die Items-/Home-Caches,
     *  damit die Online-Sortierung "Zuletzt gespielt" das Video sofort zeigt.
     *  Fire-and-forget — Fehler (z.B. offline) werden geschluckt. */
    suspend fun markPlayed(id: Int) {
        try {
            val response = apiClientProvider.api.markPlayed(id)
            if (response.isSuccessful) {
                cache.invalidate("items:")
                cache.invalidate("home")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* offline o.ä. — egal */ }
    }

    /** Item (inkl. Datei) auf dem Server löschen. Admin-only serverseitig. */
    suspend fun deleteItem(id: Int, deleteFile: Boolean = true): Result<Unit> {
        return try {
            val response = apiClientProvider.api.deleteItem(id, deleteFile)
            if (response.isSuccessful) {
                cache.invalidate("items:")
                cache.invalidate("item:$id")
                cache.invalidate("home")
                _itemUpdated.tryEmit(id)
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    /** Cache für ein einzelnes Item invalidieren — zwingt nächsten getItem-Call zum Refetch. */
    fun invalidateItem(itemId: Int) {
        cache.invalidate("item:$itemId")
    }

    suspend fun getItem(id: Int): Result<Item> = cached("item:$id") {
        try {
            val response = apiClientProvider.api.getItem(id)
            if (response.isSuccessful) {
                response.body()?.let { Result.Success(it) } ?: Result.Error("Item nicht gefunden")
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    // Playback NICHT cachen — Server-Sessions, immer frische Info
    suspend fun getPlayback(id: Int, mode: String? = null, profile: String? = null): Result<PlaybackInfo> {
        return try {
            val response = apiClientProvider.api.getPlayback(id, mode, profile)
            if (response.isSuccessful) {
                response.body()?.let { Result.Success(it) } ?: Result.Error("Keine Playback-Info")
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun setWatched(id: Int, watched: Boolean): Result<Unit> {
        return try {
            val response = apiClientProvider.api.setWatched(id, WatchedRequest(watched))
            if (response.isSuccessful) {
                cache.invalidate("items:")
                cache.invalidate("item:$id")
                cache.invalidate("home")
                cache.invalidate("seasons:")
                _itemUpdated.tryEmit(id)
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun setFavorite(id: Int, favorite: Boolean): Result<Unit> {
        return try {
            val response = apiClientProvider.api.setFavorite(id, FavoriteRequest(favorite))
            if (response.isSuccessful) {
                cache.invalidate("items:")
                cache.invalidate("item:$id")
                _itemUpdated.tryEmit(id)
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    /** Holt die Resume-Position über den separaten /resume-Endpoint
     *  (Item-JSON enthält das Feld nicht). */
    suspend fun getResumePosition(id: Int): Double {
        return try {
            val response = apiClientProvider.api.getResume(id)
            if (response.isSuccessful) response.body()?.positionSec ?: 0.0
            else 0.0
        } catch (e: Exception) { 0.0 }
    }

    suspend fun setResume(id: Int, positionSec: Double): Result<Unit> {
        return try {
            val response = apiClientProvider.api.setResume(id, ResumeRequest(positionSec))
            if (response.isSuccessful) {
                cache.invalidate("item:$id")
                cache.invalidate("home")
                _itemUpdated.tryEmit(id)
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getFolders(libraryId: Int, parent: String? = null): Result<List<FolderItem>> {
        val cacheKey = if (parent.isNullOrBlank()) "folders:$libraryId" else "folders:$libraryId:$parent"
        return cached(cacheKey, ttlMs = 60_000L) {
            try {
                val response = apiClientProvider.api.getFolders(libraryId, parent)
                if (response.isSuccessful) {
                    val folders = response.body() ?: emptyList()
                    // Folder-Cache (Room) nur fuer Top-Level — Subfolders sind
                    // nicht offline-relevant (gibt's nur fuer Server-Lib-Drilldown).
                    if (parent.isNullOrBlank()) {
                        try { offlineRepository.cacheFolders(libraryId, folders) } catch (_: Exception) {}
                    }
                    // Show-Poster mit-cachen, damit sie im Offline-Mode auf
                    // den Folder-Kacheln erscheinen (statt Episoden-Thumb).
                    try {
                        val base = settingsDataStore.settings.first().serverUrl.trimEnd('/')
                        val urls = folders.mapNotNull { f ->
                            f.metadataId?.takeIf { it > 0 }?.let { "$base/api/poster/metadata/$it" }
                        }
                        imageCache.cacheAll(urls)
                    } catch (_: Exception) {}
                    Result.Success(folders)
                }
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }
    }

    suspend fun setFolderDrilldown(libraryId: Int, folder: String, drilldown: Boolean): Result<Unit> {
        return try {
            val response = apiClientProvider.api.setFolderDrilldown(
                libraryId,
                com.goldfish.android.data.model.FolderDrilldownRequest(folder = folder, drilldown = drilldown)
            )
            if (response.isSuccessful) {
                // Folder-Cache invalidieren (Prefix-Match deckt sowohl
                // "folders:$libraryId" als auch "folders:$libraryId:<parent>" ab),
                // sonst kommt der alte drilldown-Wert zurueck.
                cache.invalidate("folders:$libraryId")
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Result.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    suspend fun getItemVariants(itemId: Int): Result<List<Item>> {
        return try {
            val response = apiClientProvider.api.getItemVariants(itemId)
            if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
            else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getLibraryStats(libraryId: Int, folder: String? = null): Result<LibraryStats> =
        cached("libstats:$libraryId:${folder.orEmpty()}", ttlMs = 60_000L) {
            try {
                val response = apiClientProvider.api.getLibraryStats(libraryId, folder)
                if (response.isSuccessful) Result.Success(response.body() ?: LibraryStats())
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    suspend fun getSeasons(libraryId: Int, folder: String, forceRefresh: Boolean = false): Result<SeasonResponse> {
        if (forceRefresh) {
            // In-Memory + Server-TMDB-Cache invalidieren. folder-Cache (60s)
            // wird auch geleert, weil ein manueller Match im Browser auch die
            // Folder-Poster-Zuordnung aendert.
            cache.invalidate("seasons:$libraryId:$folder")
            cache.invalidate("folders:$libraryId")
        }
        return cached("seasons:$libraryId:$folder", ttlMs = 5 * 60_000L) {
            try {
                val response = apiClientProvider.api.getSeasons(libraryId, folder, refresh = if (forceRefresh) true else null)
                if (response.isSuccessful) {
                    val body = response.body() ?: SeasonResponse()
                    // Persistent ablegen + Bild-URLs vorladen. Ohne diesen
                    // Cache zeigt der Offline-Mode in der Staffel-Ansicht
                    // keine Bilder (TMDB-URLs gehen direkt nach
                    // image.tmdb.org, nicht ueber Server-Proxy).
                    try { offlineRepository.cacheSeasons(libraryId, folder, body) } catch (_: Exception) {}
                    try {
                        val urls = mutableListOf<String>()
                        body.show?.posterPath?.let { urls += "https://image.tmdb.org/t/p/w342$it" }
                        body.show?.backdropPath?.let { urls += "https://image.tmdb.org/t/p/w780$it" }
                        body.seasons.forEach { s ->
                            s.posterPath?.let { urls += "https://image.tmdb.org/t/p/w342$it" }
                            s.episodes.forEach { ep ->
                                ep.stillPath?.let { urls += "https://image.tmdb.org/t/p/w300$it" }
                            }
                        }
                        if (urls.isNotEmpty()) imageCache.cacheAll(urls)
                    } catch (_: Exception) {}
                    Result.Success(body)
                }
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }
    }

    suspend fun getCollections(): Result<List<MediaCollection>> = cached("collections", ttlMs = 60_000L) {
        try {
            val response = apiClientProvider.api.getCollections()
            if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
            else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getCollectionItems(collectionId: Int): Result<List<MediaCollectionPart>> =
        cached("collection:$collectionId", ttlMs = 60_000L) {
            try {
                val response = apiClientProvider.api.getCollectionItems(collectionId)
                if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    suspend fun getPlaylists(): Result<List<Playlist>> = cached("playlists", ttlMs = 30_000L) {
        try {
            val response = apiClientProvider.api.getPlaylists()
            if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
            else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getPlaylistItems(playlistId: Int): Result<List<Item>> =
        cached("playlist:$playlistId", ttlMs = 30_000L) {
            try {
                val response = apiClientProvider.api.getPlaylistItems(playlistId)
                if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    suspend fun createPlaylist(name: String): Result<Playlist> {
        return try {
            val response = apiClientProvider.api.createPlaylist(CreatePlaylistRequest(name))
            if (response.isSuccessful) {
                response.body()?.let {
                    cache.invalidate("playlists")
                    Result.Success(it)
                } ?: Result.Error("Keine Antwort")
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun deletePlaylist(playlistId: Int): Result<Unit> {
        return try {
            val response = apiClientProvider.api.deletePlaylist(playlistId)
            if (response.isSuccessful) {
                cache.invalidate("playlists")
                cache.invalidate("playlist:$playlistId")
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun getMetadataCast(metadataId: Int): List<CastMember> {
        val key = "cast:$metadataId"
        cache.get<List<CastMember>>(key)?.let { return it }
        return try {
            val response = apiClientProvider.api.getMetadataCast(metadataId)
            if (!response.isSuccessful) return emptyList()
            val cast = response.body() ?: emptyList()
            cache.put(key, cast, ttlMs = 60 * 60 * 1000L)
            cast
        } catch (e: Exception) { emptyList() }
    }

    fun getPersonProfileUrl(tmdbId: Int, baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}/api/person/$tmdbId/profile"
    }

    suspend fun getTrickplayFrames(itemId: Int): List<TrickplayFrame> {
        val key = "trickplay:$itemId"
        cache.get<List<TrickplayFrame>>(key)?.let { return it }
        return try {
            val response = apiClientProvider.api.getTrickplayVtt(itemId)
            if (!response.isSuccessful) return emptyList()
            val vtt = response.body()?.string() ?: return emptyList()
            val frames = TrickplayParser.parse(vtt)
            // Lange TTL: VTT ändert sich praktisch nicht mehr, sobald einmal generiert
            cache.put(key, frames, ttlMs = 60 * 60 * 1000L)
            frames
        } catch (e: Exception) { emptyList() }
    }

    fun getTrickplaySpriteUrl(itemId: Int, sprite: String, baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}/api/trickplay/$itemId/$sprite"
    }

    fun getThumbnailUrl(itemId: Int, baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}api/thumb/$itemId"
    }

    fun getPosterUrl(metadataId: Int, baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}api/poster/metadata/$metadataId"
    }
}
