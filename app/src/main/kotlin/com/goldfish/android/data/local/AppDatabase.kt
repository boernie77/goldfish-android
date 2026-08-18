package com.goldfish.android.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val itemId: Int,
    val localPath: String,
    val fileSize: Long,
    val downloadedAt: Long,
    val title: String,
    val thumbPath: String? = null,
    // v2: fuer Offline-Filter — wir muessen wissen, zu welcher Library/Top-Folder
    // ein Download gehoert, ohne Server-Round-Trip.
    val libraryId: Int = 0,
    val relPath: String = "",
    // v3: komplette Item-JSON, damit die App Detail/Player/Listen/Seasons-Grid
    // OHNE Server-Call bauen kann. Wird beim Download geschrieben + per
    // Backfill fuer Legacy-Eintraege nachgezogen. Leer = noch nicht gefuellt
    // (Bestand aus v1/v2 vor Backfill).
    val itemJson: String = "",
    // v6: lokaler "zuletzt abgespielt"-Timestamp (epoch ms). Wird beim Abspielen
    // eines Downloads gesetzt — der Server-last_played_at ist offline nicht
    // verfuegbar. 0 = noch nie (offline) abgespielt. Treibt den Offline-Sort
    // "Zuletzt abgespielt".
    val lastPlayedAt: Long = 0
)

// Library-Metadaten gecacht — fuer Offline-Library-Liste (sonst kennen wir
// im Offline-Mode nur Library-IDs, aber keine Namen/Kinds). Wird bei jedem
// erfolgreichen /api/libraries-Fetch aktualisiert.
@Entity(tableName = "library_cache")
data class LibraryCacheEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val kind: String,
    val sortOrder: Int = 0,
    val channelLabelOnTop: Boolean = true
)

// Folder-Cache: pro (libraryId, folder) das Server-Folder-Item gecacht.
// Wichtig: speichert die SHOW-Metadata (metadataId+metadataJson), nicht
// die der ersten enthaltenen Episode — damit die Show-Kachel im
// Offline-Mode das richtige TMDB-Show-Poster zeigt.
@Entity(tableName = "library_folder_cache", primaryKeys = ["libraryId", "folder"])
data class LibraryFolderCacheEntity(
    val libraryId: Int,
    val folder: String,
    val count: Int = 0,
    val thumbItemId: Int? = null,
    val metadataId: Int? = null,
    // Komplette Metadata als JSON (title, year, posterPath, rating, etc.)
    val metadataJson: String = ""
)

// Seasons-Cache: pro (libraryId, folder) die komplette SeasonResponse vom
// Server gecacht. Enthaelt Show-Info + Staffel-Liste + alle Episoden mit
// stillPath/posterPath. Ohne Cache zeigt der Offline-Mode in der Staffel-
// Ansicht keine Bilder (TMDB-URLs sind direkt, nicht ueber Server-Proxy).
@Entity(tableName = "library_seasons_cache", primaryKeys = ["libraryId", "folder"])
data class LibrarySeasonsCacheEntity(
    val libraryId: Int,
    val folder: String,
    val seasonResponseJson: String = "",
    val cachedAt: Long = 0L
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun getAllDownloadsNow(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun getDownload(itemId: Int): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Delete
    suspend fun deleteDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE itemId = :itemId")
    suspend fun deleteDownloadById(itemId: Int)

    // Offline "zuletzt abgespielt": Timestamp beim Abspielen setzen.
    @Query("UPDATE downloads SET lastPlayedAt = :ts WHERE itemId = :itemId")
    suspend fun setLastPlayed(itemId: Int, ts: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE itemId = :itemId)")
    fun isDownloaded(itemId: Int): Flow<Boolean>

    @Query("SELECT itemId FROM downloads")
    fun getDownloadedItemIds(): Flow<List<Int>>

    // Offline-Filter: alle Library-IDs mit mindestens einem Download.
    @Query("SELECT DISTINCT libraryId FROM downloads WHERE libraryId > 0")
    fun getLibraryIdsWithDownloads(): Flow<List<Int>>

    // Alle Downloads einer Library — fuer Folder-Filter + Item-Filter pro Lib.
    @Query("SELECT * FROM downloads WHERE libraryId = :libraryId")
    fun getDownloadsForLibrary(libraryId: Int): Flow<List<DownloadEntity>>

    // Snapshot-Variante (suspend) fuer Offline-Reads, die kein Flow brauchen.
    @Query("SELECT * FROM downloads WHERE libraryId = :libraryId")
    suspend fun getDownloadsForLibraryNow(libraryId: Int): List<DownloadEntity>

    // Bestands-Downloads ohne libraryId oder ohne itemJson → werden beim
    // App-Start nachgefuellt (Backfill via /api/items/{id}).
    @Query("SELECT * FROM downloads WHERE libraryId = 0 OR itemJson = ''")
    suspend fun getDownloadsNeedingBackfill(): List<DownloadEntity>
}

@Dao
interface LibraryCacheDao {
    @Query("SELECT * FROM library_cache ORDER BY sortOrder, name COLLATE NOCASE")
    fun getAllCached(): Flow<List<LibraryCacheEntity>>

    @Query("SELECT * FROM library_cache WHERE id = :id")
    suspend fun getCached(id: Int): LibraryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libs: List<LibraryCacheEntity>)

    @Query("DELETE FROM library_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<Int>)
}

@Dao
interface LibraryFolderCacheDao {
    @Query("SELECT * FROM library_folder_cache WHERE libraryId = :libraryId")
    suspend fun getForLibrary(libraryId: Int): List<LibraryFolderCacheEntity>

    @Query("SELECT * FROM library_folder_cache WHERE libraryId = :libraryId AND folder = :folder")
    suspend fun get(libraryId: Int, folder: String): LibraryFolderCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryFolderCacheEntity>)

    @Query("DELETE FROM library_folder_cache WHERE libraryId = :libraryId AND folder NOT IN (:keepFolders)")
    suspend fun deleteNotIn(libraryId: Int, keepFolders: List<String>)
}

@Dao
interface LibrarySeasonsCacheDao {
    @Query("SELECT * FROM library_seasons_cache WHERE libraryId = :libraryId AND folder = :folder")
    suspend fun get(libraryId: Int, folder: String): LibrarySeasonsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LibrarySeasonsCacheEntity)

    @Query("DELETE FROM library_seasons_cache WHERE libraryId = :libraryId AND folder = :folder")
    suspend fun delete(libraryId: Int, folder: String)
}

// v1 → v2: libraryId + relPath fuer Offline-Filter.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN libraryId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN relPath TEXT NOT NULL DEFAULT ''")
    }
}

// v2 → v3: itemJson fuer Offline-First (komplettes Item-Modell lokal),
// plus library_cache-Tabelle fuer Library-Metadaten ohne Server.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN itemJson TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library_cache (
                id INTEGER NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                channelLabelOnTop INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }
}

// v3 → v4: library_folder_cache fuer korrekte Show-Poster im Offline-Mode.
// Der /api/libraries/{id}/folders-Endpoint liefert metadataId = Show-Metadata
// (nicht Episode!). Den wollen wir lokal halten fuer offline Anzeige.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library_folder_cache (
                libraryId INTEGER NOT NULL,
                folder TEXT NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                thumbItemId INTEGER,
                metadataId INTEGER,
                metadataJson TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (libraryId, folder)
            )
            """.trimIndent()
        )
    }
}

// v4 → v5: library_seasons_cache fuer korrekte Bilder in der Staffel-
// Ansicht im Offline-Mode. /api/libraries/{id}/seasons liefert komplette
// SeasonResponse mit show/seasonPosterPath/stillPath — die wollen wir
// persistent ablegen.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS library_seasons_cache (
                libraryId INTEGER NOT NULL,
                folder TEXT NOT NULL,
                seasonResponseJson TEXT NOT NULL DEFAULT '',
                cachedAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (libraryId, folder)
            )
            """.trimIndent()
        )
    }
}

// v5 → v6: lastPlayedAt auf downloads fuer Offline-Sort "Zuletzt abgespielt".
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [DownloadEntity::class, LibraryCacheEntity::class, LibraryFolderCacheEntity::class, LibrarySeasonsCacheEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun libraryCacheDao(): LibraryCacheDao
    abstract fun libraryFolderCacheDao(): LibraryFolderCacheDao
    abstract fun librarySeasonsCacheDao(): LibrarySeasonsCacheDao
}
