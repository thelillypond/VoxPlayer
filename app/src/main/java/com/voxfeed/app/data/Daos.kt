package com.voxfeed.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SubFeedDao {
    @Query("SELECT * FROM sub_feeds ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SubFeedEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sub_feeds")
    suspend fun maxSortOrder(): Int

    @Insert suspend fun insert(subFeed: SubFeedEntity): Long

    @Query("SELECT * FROM sub_feeds ORDER BY sortOrder, id")
    suspend fun getAll(): List<SubFeedEntity>

    @Query("DELETE FROM sub_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE channelId != 'ADHOC' ORDER BY title COLLATE NOCASE")
    suspend fun getAllChannels(): List<ChannelEntity>


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(channel: ChannelEntity): Long

    @Query("UPDATE channels SET subFeedId = :subFeedId, excludeShorts = :excludeShorts WHERE channelId = :id")
    suspend fun updateSettings(id: String, subFeedId: Long, excludeShorts: Boolean)

    @Query("UPDATE channels SET title = :title, lastCheckedAt = :time WHERE channelId = :id")
    suspend fun updateChecked(id: String, title: String, time: Long)

    @Query("SELECT * FROM channels WHERE channelId != 'ADHOC'")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE alertsEnabled = 1 AND channelId != 'ADHOC'")
    suspend fun getAlertEnabled(): List<ChannelEntity>

    @Query("UPDATE channels SET alertsEnabled = :enabled WHERE channelId = :id")
    suspend fun setAlertsEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM channels WHERE subFeedId = :subFeedId AND channelId != 'ADHOC'")
    suspend fun getBySubFeed(subFeedId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE channelId = :id")
    suspend fun get(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE channelId != 'ADHOC' ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("DELETE FROM channels WHERE channelId = :id")
    suspend fun delete(id: String)
}

/** Lightweight projection (not an entity): known channelTitle per channelId. */
data class ChannelTitleRow(val channelId: String, val channelTitle: String)

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE channelId = :channelId ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun getRecentByChannel(channelId: String, limit: Int): List<VideoEntity>


    @Query("SELECT channelId, channelTitle FROM videos WHERE channelTitle != '' GROUP BY channelId")
    fun observeChannelTitles(): Flow<List<ChannelTitleRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    /** Returns row ids; -1 for rows ignored because the video already exists. */
    suspend fun insertAll(videos: List<VideoEntity>): List<Long>

    @Query("UPDATE videos SET subFeedId = :subFeedId WHERE channelId = :channelId")
    suspend fun moveChannelVideos(channelId: String, subFeedId: Long)

    @Query("SELECT * FROM videos ORDER BY publishedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE subFeedId = :subFeedId ORDER BY publishedAt DESC")
    fun observeBySubFeed(subFeedId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE isFavorite = 1 ORDER BY publishedAt DESC")
    fun observeFavorites(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE isFavorite = 1 AND subFeedId = :subFeedId ORDER BY publishedAt DESC")
    fun observeFavoritesBySubFeed(subFeedId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE isArchived = 1 ORDER BY publishedAt DESC")
    fun observeArchived(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE videoId = :id")
    suspend fun get(id: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE videoId = :id")
    fun observe(id: String): Flow<VideoEntity?>

    @Query("UPDATE videos SET localVideoPath = :path, isArchived = 1 WHERE videoId = :id")
    suspend fun setArchived(id: String, path: String)

    @Query("UPDATE videos SET localVideoPath = NULL, isArchived = 0 WHERE videoId = :id")
    suspend fun clearArchive(id: String)

    @Query("UPDATE videos SET description = :description WHERE videoId = :id")
    suspend fun updateDescription(id: String, description: String)

    @Query("UPDATE videos SET voxDigest = :digest WHERE videoId = :id")
    suspend fun setVoxDigest(id: String, digest: String?)

    @Query("UPDATE videos SET isRead = :read WHERE videoId = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE videos SET isFavorite = :fav WHERE videoId = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    /** Re-homes an ad-hoc video under its (newly subscribed) channel. */
    @Query("UPDATE videos SET channelId = :channelId, subFeedId = :subFeedId WHERE videoId = :id")
    suspend fun moveVideo(id: String, channelId: String, subFeedId: Long)

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int

    @Query("SELECT * FROM videos WHERE isRead = 1 OR isFavorite = 1")
    suspend fun getFlagged(): List<VideoEntity>

    @Query("UPDATE videos SET isRead = :read, isFavorite = :fav WHERE videoId = :id")
    suspend fun setFlags(id: String, read: Boolean, fav: Boolean): Int

    @Query("DELETE FROM videos WHERE channelId = :channelId AND isArchived = 0")
    suspend fun deleteUnarchivedByChannel(channelId: String)

    @Query("SELECT COUNT(*) FROM videos WHERE channelId = :channelId AND isArchived = 1")
    suspend fun countArchivedByChannel(channelId: String): Int
}



@Dao
interface DigestDao {
    @Query("SELECT digest FROM vox_digests WHERE videoId = :videoId AND depthMode = :mode")
    fun observe(videoId: String, mode: String): Flow<String?>

    @Query("SELECT COUNT(*) FROM vox_digests WHERE videoId = :videoId")
    fun observeCount(videoId: String): Flow<Int>

    /** Replaces only the (videoId, depthMode) row; the other mode's cache is untouched. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DigestEntity)
}


@Dao
interface VideoPlaylistDao {
    @Query("SELECT * FROM video_playlists ORDER BY id")
    suspend fun getPlaylists(): List<VideoPlaylistEntity>

    @Query("SELECT * FROM video_playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): VideoPlaylistEntity?

    @Query(
        "SELECT v.* FROM videos v INNER JOIN video_playlist_items i ON i.videoId = v.videoId " +
            "WHERE i.playlistId = :playlistId ORDER BY i.addedAt, v.publishedAt DESC"
    )
    suspend fun getVideos(playlistId: Long): List<VideoEntity>


    @Query("SELECT * FROM video_playlists ORDER BY id")
    fun observePlaylists(): Flow<List<VideoPlaylistEntity>>

    @Query("SELECT * FROM video_playlist_items")
    fun observeItems(): Flow<List<VideoPlaylistItemEntity>>

    /** Videos in one playlist, oldest addition first. */
    @Query(
        "SELECT v.* FROM videos v INNER JOIN video_playlist_items i ON i.videoId = v.videoId " +
            "WHERE i.playlistId = :playlistId ORDER BY i.addedAt, v.publishedAt DESC"
    )
    fun observeVideos(playlistId: Long): Flow<List<VideoEntity>>

    @Insert suspend fun insertPlaylist(p: VideoPlaylistEntity): Long

    @Query("UPDATE video_playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM video_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: VideoPlaylistItemEntity): Long

    @Query("DELETE FROM video_playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeItem(playlistId: Long, videoId: String)
}
