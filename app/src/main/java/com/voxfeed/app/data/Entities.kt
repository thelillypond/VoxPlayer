package com.voxfeed.app.data

import androidx.room.*

@Entity(tableName = "sub_feeds")
data class SubFeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = SubFeedEntity::class, parentColumns = ["id"],
        childColumns = ["subFeedId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("subFeedId")]
)
data class ChannelEntity(
    @PrimaryKey val channelId: String,
    val subFeedId: Long,
    val title: String,
    val excludeShorts: Boolean = true,
    val lastCheckedAt: Long = 0,
    /** Vox Alerts opt-in; false = muted. */
    @ColumnInfo(defaultValue = "1") val alertsEnabled: Boolean = true
)

@Entity(
    tableName = "videos",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class, parentColumns = ["channelId"],
        childColumns = ["channelId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("channelId"), Index("subFeedId"), Index("publishedAt")]
)
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val subFeedId: Long,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val publishedAt: Long,
    val isRead: Boolean = false,
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "''") val description: String = "",
    val localVideoPath: String? = null,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
    /** Cached Silica Animus (Gemini) digest; never touched by RSS refreshes. */
    val voxDigest: String? = null
)


/** Cached Silica Animus summary, one row per video and depth mode (BRIEF / DEEP_DOSSIER). */
@Entity(
    tableName = "vox_digests",
    primaryKeys = ["videoId", "depthMode"],
    foreignKeys = [ForeignKey(
        entity = VideoEntity::class, parentColumns = ["videoId"],
        childColumns = ["videoId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("videoId")]
)
data class DigestEntity(
    val videoId: String,
    val depthMode: String,
    val digest: String,
    val createdAt: Long
)
