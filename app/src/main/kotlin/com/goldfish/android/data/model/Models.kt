package com.goldfish.android.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val username: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class AuthStatus(
    @Json(name = "loggedIn") val isAuthenticated: Boolean = false,
    val username: String? = null,
    @Json(name = "isAdmin") val isAdmin: Boolean = false
)

@JsonClass(generateAdapter = true)
data class Library(
    val id: Int,
    val name: String,
    val kind: String, // "movies", "tv", "private"
    val sortOrder: Int = 0,
    val channelLabelOnTop: Boolean = true
)

@JsonClass(generateAdapter = true)
data class LibraryStats(
    val totalItems: Int = 0,
    val folderCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class Metadata(
    val id: Int = 0,
    val title: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val genres: String? = null,
    val posterPath: String? = null,
    val tmdbType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @Json(name = "runtimeMin") val runtime: Int? = null,
    val backdropPath: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class Item(
    val id: Int,
    val title: String,
    val libraryId: Int,
    val metadataId: Int? = null,
    val watched: Boolean = false,
    val favorite: Boolean = false,
    val durationSec: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val hasThumb: Boolean = false,
    val metadata: Metadata? = null,
    val resumePosSec: Double? = null,
    val relPath: String? = null,
    val addedAt: String? = null,
    val releasedAt: String? = null,    // creation_time aus ffprobe, Fallback ModTime
    val metadataConfirmed: Boolean = false,
    val bitrateKbps: Int = 0,
    // variantCount kommt direkt vom Server (attachVariantCounts in sqlite.go).
    // Library-uebergreifender Geschwister-Counter — auch wenn die Geschwister
    // in einer anderen Library liegen und im Grid gar nicht auftauchen.
    val variantCount: Int = 0,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null
) {
    val displayTitle: String
        get() = metadata?.title?.takeIf { it.isNotBlank() } ?: title

    val isMovie: Boolean
        get() = metadata?.tmdbType == "movie"

    val isTvEpisode: Boolean
        get() = metadata?.tmdbType == "episode"
}

@JsonClass(generateAdapter = true)
data class HomeSection(
    val library: Library,
    @Json(name = "continue") val continueItems: List<Item> = emptyList(),
    val nextUp: List<Item> = emptyList(),
    val recent: List<Item> = emptyList()
)

@JsonClass(generateAdapter = true)
data class HomeResponse(
    val sections: List<HomeSection> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PlaybackInfo(
    val mode: String = "",
    val url: String = "",
    val item: Item? = null,
    val profiles: List<PlaybackProfile>? = null,
    val streams: List<MediaStream>? = null,
    val profile: String? = null,
    val interlaced: Boolean = false,
    val deinterlace: String? = null
)

@JsonClass(generateAdapter = true)
data class PlaybackProfile(
    @Json(name = "ID") val id: String,
    @Json(name = "Label") val label: String,
    @Json(name = "MaxHeight") val maxHeight: Int = 0,
    @Json(name = "VideoKbps") val videoKbps: Int = 0,
    @Json(name = "AudioKbps") val audioKbps: Int = 0
)

@JsonClass(generateAdapter = true)
data class MediaStream(
    val index: Int,
    val type: String,
    val language: String? = null,
    val codec: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
)

@JsonClass(generateAdapter = true)
data class WatchedRequest(
    val watched: Boolean
)

@JsonClass(generateAdapter = true)
data class FavoriteRequest(
    val favorite: Boolean
)

@JsonClass(generateAdapter = true)
data class ResumeRequest(
    val positionSec: Double
)

@JsonClass(generateAdapter = true)
data class ResumeResponse(
    val positionSec: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class ItemsResponse(
    val items: List<Item> = emptyList(),
    val total: Int = 0
)

@JsonClass(generateAdapter = true)
data class FolderItem(
    @Json(name = "name") val folder: String,
    @Json(name = "itemCount") val count: Int = 0,
    @Json(name = "thumbItemId") val thumbItemId: Int? = null,
    val metadataId: Int? = null,
    val metadata: Metadata? = null,
    val drilldown: Boolean = false
)

@JsonClass(generateAdapter = true)
data class FolderDrilldownRequest(
    val folder: String,
    val drilldown: Boolean
)

// --- Season / Episode Models ---

// ShowInfo: wie Metadata, aber genres kommt als echtes JSON-Array (nicht als String)
@JsonClass(generateAdapter = true)
data class ShowInfo(
    val id: Int = 0,
    val title: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),  // Array in seasons-API
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val tmdbType: String? = null,
    val tmdbId: Int? = null,
    @Json(name = "runtimeMin") val runtime: Int? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null
)

@JsonClass(generateAdapter = true)
data class SeasonResponse(
    val seasons: List<SeasonInfo> = emptyList(),
    val show: ShowInfo? = null,
    val showTmdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class SeasonInfo(
    val seasonNumber: Int,
    val name: String = "",
    val posterPath: String? = null,
    val airDate: String? = null,
    val ownedCount: Int = 0,
    val total: Int = 0,
    val episodes: List<EpisodeInfo> = emptyList()
)

@JsonClass(generateAdapter = true)
data class EpisodeInfo(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val stillPath: String? = null,
    val owned: Boolean = false,
    val itemId: Int? = null
)

// --- Collections ---

@JsonClass(generateAdapter = true)
data class MediaCollection(
    val id: Int,
    val name: String,
    val movieCount: Int = 0,
    val posterPath: String? = null,
    val partCount: Int = 0,
    val hiddenCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class MediaCollectionPart(
    val tmdbId: Int? = null,
    val title: String? = null,
    val owned: Boolean = false,
    val itemId: Int? = null,
    val posterPath: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val hidden: Boolean = false
)

// --- Playlists ---

@JsonClass(generateAdapter = true)
data class Playlist(
    val id: Int,
    val name: String,
    val itemCount: Int = 0,
    val posterItemId: Int? = null,
    val posterMetadataId: Int? = null,
    val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CreatePlaylistRequest(
    val name: String
)

// --- Cast / Schauspieler ---

@JsonClass(generateAdapter = true)
data class CastMember(
    val personId: Int = 0,
    val tmdbId: Int = 0,
    val name: String = "",
    val profilePath: String? = null,
    val character: String = "",
    val role: String = "",     // "main" | "guest"
    val order: Int = 0
)

/** TMDB-Suchergebnis vom Goldfish-Server (/api/metadata/search).
 *  Wird fuer lokale Bibliotheken-Anreicherung verwendet. */
@JsonClass(generateAdapter = true)
data class TmdbSearchResult(
    val tmdbType: String = "",  // "movie" | "tv"
    val id: Long = 0,
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val overview: String = "",
    val rating: Double = 0.0,
    val posterPath: String? = null,
    val backdropPath: String? = null
)
