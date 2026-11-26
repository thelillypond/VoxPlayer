package com.voxfeed.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "music_playlists")
data class MusicPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "music_tracks",
    foreignKeys = [ForeignKey(
        entity = MusicPlaylistEntity::class, parentColumns = ["id"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId")]
)
data class MusicTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** Normalized https://www.youtube.com/watch?v=ID */
    val sourceUrl: String,
    val thumbnailUrl: String?,
    /** Non-null once downloaded (content:// or file:// URI) => cached offline. */
    val localUri: String?,
    val position: Int,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "'UNCLASSIFIED'") val genre: String = "UNCLASSIFIED"
)

@Dao
interface MusicDao {
    @Query("SELECT * FROM music_playlists ORDER BY id")
    fun observePlaylists(): Flow<List<MusicPlaylistEntity>>

    @Query("SELECT COUNT(*) FROM music_playlists")
    suspend fun playlistCount(): Int

    @Insert
    suspend fun insertPlaylist(p: MusicPlaylistEntity): Long

    @Query("SELECT * FROM music_tracks WHERE playlistId = :playlistId ORDER BY position, id")
    fun observeTracks(playlistId: Long): Flow<List<MusicTrackEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM music_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertTrack(t: MusicTrackEntity): Long

    /** Appends after the current last position; existing tracks are never touched. Returns rows with ids. */
    @Transaction
    suspend fun appendTracks(playlistId: Long, tracks: List<MusicTrackEntity>): List<MusicTrackEntity> {
        var pos = maxPosition(playlistId) + 1
        return tracks.map { t ->
            val row = t.copy(id = 0, playlistId = playlistId, position = pos++)
            row.copy(id = insertTrack(row))
        }
    }

    @Query("UPDATE music_tracks SET localUri = :uri WHERE id = :trackId")
    suspend fun setLocalUri(trackId: Long, uri: String?)

    /** Cache completion: flips every copy of the same source (other playlists, favourites) so all badges update. */
    @Query("UPDATE music_tracks SET localUri = :uri WHERE sourceUrl = :url AND localUri IS NULL")
    suspend fun setLocalUriBySource(url: String, uri: String)

    @Query("UPDATE music_tracks SET isFavorite = :fav WHERE sourceUrl = :url")
    suspend fun setFavoriteBySource(url: String, fav: Boolean)

    @Query("UPDATE music_tracks SET genre = :genre WHERE sourceUrl = :url")
    suspend fun setGenreBySource(url: String, genre: String)

    /** Permanent purge: removes every playlist link for this track (same source URL or same cached file). */
    @Query("DELETE FROM music_tracks WHERE sourceUrl = :url OR (localUri IS NOT NULL AND localUri = :uri)")
    suspend fun deleteEverywhere(url: String, uri: String)

    @Query("DELETE FROM music_tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    /** Tracks cascade via the foreign key. */
    @Query("DELETE FROM music_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM music_playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): MusicPlaylistEntity?

    @Query("SELECT * FROM music_tracks ORDER BY playlistId, position, id")
    fun observeAllTracks(): Flow<List<MusicTrackEntity>>

    @Query("SELECT COUNT(*) FROM music_tracks WHERE playlistId = :playlistId AND sourceUrl = :url")
    suspend fun countBySource(playlistId: Long, url: String): Int

    /** Copies (or moves) a track into another playlist, appended at the end; never duplicates a source URL. */
    @Transaction
    suspend fun copyTrackTo(trackId: Long, targetPlaylistId: Long, move: Boolean): Boolean {
        val t = getTrack(trackId) ?: return false
        if (t.playlistId == targetPlaylistId) return false
        val dup = countBySource(targetPlaylistId, t.sourceUrl) > 0
        if (!dup) appendTracks(targetPlaylistId, listOf(t))
        if (move) deleteTrack(trackId)
        return !dup
    }

    @Query("SELECT * FROM music_tracks WHERE id = :id")
    suspend fun getTrack(id: Long): MusicTrackEntity?

    @Query("UPDATE music_playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("SELECT * FROM music_playlists ORDER BY id")
    suspend fun getPlaylists(): List<MusicPlaylistEntity>

    @Query("SELECT * FROM music_playlists WHERE name = :name LIMIT 1")
    suspend fun findPlaylistByName(name: String): MusicPlaylistEntity?

    @Transaction
    suspend fun getOrCreatePlaylist(name: String): MusicPlaylistEntity =
        findPlaylistByName(name)
            ?: MusicPlaylistEntity(name = name, createdAt = System.currentTimeMillis()).let { it.copy(id = insertPlaylist(it)) }

    @Query("SELECT * FROM music_tracks WHERE isFavorite = 1 ORDER BY id")
    suspend fun getFavoriteTracks(): List<MusicTrackEntity>

    @Query("SELECT * FROM music_tracks ORDER BY playlistId, position, id")
    suspend fun getAllTracks(): List<MusicTrackEntity>

    @Query("SELECT * FROM music_tracks WHERE localUri IS NOT NULL")
    suspend fun getCachedTracks(): List<MusicTrackEntity>

    @Query("SELECT * FROM music_tracks WHERE playlistId = :playlistId ORDER BY position, id")
    suspend fun getTracks(playlistId: Long): List<MusicTrackEntity>

    @Query("UPDATE music_tracks SET position = :position WHERE id = :trackId")
    suspend fun setPosition(trackId: Long, position: Int)

    /** Moves a track one slot up (-1) or down (+1) and renumbers the playlist 0..n-1. */
    @Transaction
    suspend fun moveTrack(playlistId: Long, trackId: Long, delta: Int) {
        val list = getTracks(playlistId).toMutableList()
        val i = list.indexOfFirst { it.id == trackId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { k, t -> if (t.position != k) setPosition(t.id, k) }
    }

    /** Renames a legacy playlist in place (skipped if the new name is already taken). */
    @Query("UPDATE music_playlists SET name = :newName WHERE name = :oldName AND NOT EXISTS (SELECT 1 FROM music_playlists WHERE name = :newName)")
    suspend fun renameIfPresent(oldName: String, newName: String)

    @Transaction
    suspend fun ensureDefaultPlaylist() {
        LEGACY_DEFAULTS.forEach { renameIfPresent(it, DEFAULT_PLAYLIST) }
        LEGACY_RESCANS.forEach { renameIfPresent(it, RESCAN_PLAYLIST) }
        if (playlistCount() == 0) insertPlaylist(MusicPlaylistEntity(name = DEFAULT_PLAYLIST, createdAt = System.currentTimeMillis()))
    }

    companion object {
        const val GENRE_NONE = "UNCLASSIFIED"
        /** Virtual playlist id for the favourites view (never stored). */
        const val FAV_ID = -2L
        const val DEFAULT_PLAYLIST = "Default Playlist"
        /** Names used by earlier builds; renamed in place on first launch. */
        val LEGACY_DEFAULTS = listOf("VOX-ARCHIVE // DEFAULT")
        val LEGACY_RESCANS = listOf("RECOVERED DATA-LOGS")
        /** Home for orphaned files that match no existing playlist folder. */
        const val RESCAN_PLAYLIST = "Recovered Files"
    }
}
