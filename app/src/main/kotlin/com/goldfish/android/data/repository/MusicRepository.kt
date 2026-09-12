package com.goldfish.android.data.repository

import com.goldfish.android.data.api.ApiCache
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.model.AlbumDetail
import com.goldfish.android.data.model.MusicAlbum
import com.goldfish.android.data.model.SetAlbumFavoriteRequest
import com.goldfish.android.data.model.UpdateMusicAlbumMetadataRequest
import com.goldfish.android.data.model.UpdateMusicItemMetadataRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Musik-spezifische Endpoints (Alben/Genres/Metadaten-Edit) — eigenes Repository
 * statt in ItemRepository gebolzt, damit dieses nicht weiter waechst; teilt
 * sich aber ItemRepository's Cache/Invalidierungs-/`_itemUpdated`-Bus, weil
 * Track-Metadaten-Edits Felder aendern, die auch ueber `getItems` ("Alle Titel")
 * sichtbar sind.
 */
@Singleton
class MusicRepository @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val cache: ApiCache,
    private val itemRepository: ItemRepository
) {
    private suspend inline fun <T : Any> cached(
        key: String,
        ttlMs: Long = 60_000L,
        crossinline fetch: suspend () -> Result<T>
    ): Result<T> {
        cache.get<T>(key)?.let { return Result.Success(it) }
        val r = fetch()
        if (r is Result.Success) cache.put(key, r.data, ttlMs)
        return r
    }

    suspend fun getAlbums(libraryId: Int, genre: List<String>? = null): Result<List<MusicAlbum>> =
        cached("albums:$libraryId:${genre?.joinToString(",")}") {
            try {
                val response = apiClientProvider.api.getAlbums(libraryId, genre)
                if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    suspend fun getAlbumDetail(albumId: Int): Result<AlbumDetail> =
        cached("albumDetail:$albumId", ttlMs = 30_000L) {
            try {
                val response = apiClientProvider.api.getAlbumDetail(albumId)
                if (response.isSuccessful) {
                    response.body()?.let { Result.Success(it) } ?: Result.Error("Album nicht gefunden")
                } else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    suspend fun getGenres(libraryId: Int): Result<List<String>> =
        cached("genres:$libraryId", ttlMs = 5 * 60_000L) {
            try {
                val response = apiClientProvider.api.getGenres(libraryId)
                if (response.isSuccessful) Result.Success(response.body() ?: emptyList())
                else Result.Error("HTTP ${response.code()}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
        }

    /** Album-Favorit (user_music_album_favorites) — GETRENNT vom Track-Favorit
     *  (ItemRepository.setFavorite/user_item_state). Niemals beide auf denselben
     *  Button/Zustand mappen, das sind zwei unabhaengige Server-Konzepte. */
    suspend fun setAlbumFavorite(albumId: Int, favorite: Boolean): Result<Unit> {
        return try {
            val response = apiClientProvider.api.setAlbumFavorite(albumId, SetAlbumFavoriteRequest(favorite))
            if (response.isSuccessful) {
                cache.invalidate("albums:")
                cache.invalidate("albumDetail:$albumId")
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun updateAlbumMetadata(
        albumId: Int,
        artist: String?,
        album: String?,
        genre: String?,
        year: Int?
    ): Result<Unit> {
        return try {
            val response = apiClientProvider.api.updateAlbumMetadata(
                albumId,
                UpdateMusicAlbumMetadataRequest(artist, album, genre, year)
            )
            if (response.isSuccessful) {
                cache.invalidate("albums:")
                cache.invalidate("albumDetail:$albumId")
                cache.invalidate("items:")
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }

    suspend fun updateMusicItemMetadata(
        itemId: Int,
        title: String?,
        artist: String?,
        album: String?,
        trackNo: Int?,
        genre: String?
    ): Result<Unit> {
        return try {
            val response = apiClientProvider.api.updateMusicItemMetadata(
                itemId,
                UpdateMusicItemMetadataRequest(title, artist, album, trackNo, genre)
            )
            if (response.isSuccessful) {
                cache.invalidate("albums:")
                cache.invalidate("albumDetail:")
                cache.invalidate("items:")
                itemRepository.invalidateItem(itemId)
                Result.Success(Unit)
            } else Result.Error("HTTP ${response.code()}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error(e.message ?: "Unbekannter Fehler") }
    }
}
