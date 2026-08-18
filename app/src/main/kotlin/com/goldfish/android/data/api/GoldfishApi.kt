package com.goldfish.android.data.api

import com.goldfish.android.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface GoldfishApi {

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthStatus>

    @GET("api/auth/status")
    suspend fun authStatus(): Response<AuthStatus>

    @POST("api/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/home")
    suspend fun getHome(): Response<HomeResponse>

    @GET("api/libraries")
    suspend fun getLibraries(): Response<List<Library>>

    @GET("api/items")
    suspend fun getItems(
        @Query("libraryId") libraryIds: List<Int>? = null,
        @Query("folder") folder: String? = null,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null,
        @Query("search") search: String? = null,
        @Query("watched") watched: String? = null,
        @Query("favorite") favorite: String? = null,
        @Query("bucket") bucket: List<String>? = null
    ): Response<List<Item>>

    @GET("api/items/random")
    suspend fun getRandomItem(
        @Query("libraryId") libraryIds: List<Int>? = null,
        @Query("folder") folder: String? = null,
        @Query("watched") watched: String? = null
    ): Response<Item>

    @GET("api/items/{id}/variants")
    suspend fun getItemVariants(@Path("id") itemId: Int): Response<List<Item>>

    @GET("api/items/{id}")
    suspend fun getItem(@Path("id") id: Int): Response<Item>

    @DELETE("api/items/{id}")
    suspend fun deleteItem(
        @Path("id") id: Int,
        @Query("deleteFile") deleteFile: Boolean = true
    ): Response<Unit>

    @GET("api/playback/{id}")
    suspend fun getPlayback(
        @Path("id") id: Int,
        @Query("mode") mode: String? = null,
        @Query("profile") profile: String? = null
    ): Response<PlaybackInfo>

    @PUT("api/items/{id}/watched")
    suspend fun setWatched(
        @Path("id") id: Int,
        @Body request: WatchedRequest
    ): Response<Unit>

    // Setzt user_item_state.last_played_at = now (Server). Browser ruft das beim
    // Player-Open; ohne den Call erscheint ein in der App gespieltes Video nie
    // in der Online-Sortierung "Zuletzt gespielt".
    @POST("api/items/{id}/played")
    suspend fun markPlayed(@Path("id") id: Int): Response<Unit>

    @PUT("api/items/{id}/favorite")
    suspend fun setFavorite(
        @Path("id") id: Int,
        @Body request: FavoriteRequest
    ): Response<Unit>

    @PUT("api/items/{id}/resume")
    suspend fun setResume(
        @Path("id") id: Int,
        @Body request: ResumeRequest
    ): Response<Unit>

    @GET("api/items/{id}/resume")
    suspend fun getResume(@Path("id") id: Int): Response<ResumeResponse>

    @Streaming
    @GET("api/download/{id}")
    suspend fun downloadItem(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/trickplay/{id}/thumbs.vtt")
    suspend fun getTrickplayVtt(@Path("id") id: Int): Response<ResponseBody>

    @GET("api/metadata/{id}/cast")
    suspend fun getMetadataCast(@Path("id") metadataId: Int): Response<List<CastMember>>

    /** TMDB-Suche via Server-Proxy — fuer lokale Bibliotheken-Anreicherung.
     *  Server erfordert Admin-Login + konfigurierten TMDB-Key. */
    @GET("api/metadata/search")
    suspend fun searchMetadata(
        @Query("q") title: String,
        @Query("type") type: String? = null, // "movie" | "tv"
        @Query("year") year: Int? = null
    ): Response<List<com.goldfish.android.data.model.TmdbSearchResult>>

    @GET("api/libraries/{id}/folders")
    suspend fun getFolders(
        @Path("id") libraryId: Int,
        // Wenn gesetzt: liefert direkte Unterordner dieses Parent-Pfads
        // (Drilldown-Modus). Ohne parent: Top-Level-Folders.
        @Query("parent") parent: String? = null
    ): Response<List<FolderItem>>

    @PUT("api/libraries/{id}/folders/drilldown")
    suspend fun setFolderDrilldown(
        @Path("id") libraryId: Int,
        @Body body: FolderDrilldownRequest
    ): Response<Unit>

    @GET("api/libraries/{id}/stats")
    suspend fun getLibraryStats(
        @Path("id") libraryId: Int,
        @Query("folder") folder: String? = null
    ): Response<LibraryStats>

    @GET("api/libraries/{id}/seasons")
    suspend fun getSeasons(
        @Path("id") libraryId: Int,
        @Query("folder") folder: String,
        // refresh=true invalidiert den TMDB-Cache auf dem Server vor dem
        // Fetch — wird vom "TMDB neu laden"-Button getriggert nach
        // manueller Korrektur im Browser.
        @Query("refresh") refresh: Boolean? = null
    ): Response<SeasonResponse>

    @GET("api/collections")
    suspend fun getCollections(): Response<List<MediaCollection>>

    @GET("api/collections/{id}/items")
    suspend fun getCollectionItems(@Path("id") collectionId: Int): Response<List<MediaCollectionPart>>

    @GET("api/playlists")
    suspend fun getPlaylists(): Response<List<Playlist>>

    @GET("api/playlists/{id}/items")
    suspend fun getPlaylistItems(@Path("id") playlistId: Int): Response<List<Item>>

    @POST("api/playlists")
    suspend fun createPlaylist(@Body request: CreatePlaylistRequest): Response<Playlist>

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") playlistId: Int): Response<Unit>
}
