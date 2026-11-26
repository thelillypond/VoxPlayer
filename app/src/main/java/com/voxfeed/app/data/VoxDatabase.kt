package com.voxfeed.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SubFeedEntity::class, ChannelEntity::class, VideoEntity::class, DigestEntity::class, MusicPlaylistEntity::class, MusicTrackEntity::class, VideoPlaylistEntity::class, VideoPlaylistItemEntity::class],
    version = 10,
    exportSchema = false
)
abstract class VoxDatabase : RoomDatabase() {
    abstract fun subFeedDao(): SubFeedDao
    abstract fun channelDao(): ChannelDao
    abstract fun videoDao(): VideoDao
    abstract fun digestDao(): DigestDao
    abstract fun musicDao(): MusicDao
    abstract fun videoPlaylistDao(): VideoPlaylistDao

    companion object {
        @Volatile private var instance: VoxDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Local offline download columns. Existing rows keep all data; archive = off. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN localVideoPath TEXT")
                db.execSQL("ALTER TABLE videos ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** AI summary cache. Nullable column: all existing rows and flags are preserved. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN voxDigest TEXT")
            }
        }

        /** Per-channel new-video alert flag; existing channels default to enabled. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN alertsEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Digest cache keyed by (videoId, depthMode). Legacy videos.voxDigest values become BRIEF entries. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `vox_digests` (`videoId` TEXT NOT NULL, `depthMode` TEXT NOT NULL, `digest` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`, `depthMode`), FOREIGN KEY(`videoId`) REFERENCES `videos`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vox_digests_videoId` ON `vox_digests` (`videoId`)")
                db.execSQL("INSERT INTO vox_digests (videoId, depthMode, digest, createdAt) SELECT videoId, 'BRIEF', voxDigest, 0 FROM videos WHERE voxDigest IS NOT NULL AND voxDigest != ''")
            }
        }
        /**
         * Build 3 repair: rebuilds only the digest cache (videoId + depthMode) so a table left over from an
         * inconsistent dev schema cannot fail Room's validation. Channels, videos and flags are untouched.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `vox_digests`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `vox_digests` (`videoId` TEXT NOT NULL, `depthMode` TEXT NOT NULL, `digest` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`, `depthMode`), FOREIGN KEY(`videoId`) REFERENCES `videos`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vox_digests_videoId` ON `vox_digests` (`videoId`)")
                db.execSQL("INSERT OR IGNORE INTO vox_digests (videoId, depthMode, digest, createdAt) SELECT videoId, 'BRIEF', voxDigest, 0 FROM videos WHERE voxDigest IS NOT NULL AND voxDigest != ''")
            }
        }
        /** Audio deck: playlists + tracks. Purely additive, so all feed data is preserved. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `music_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `music_tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `sourceUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `localUri` TEXT, `position` INTEGER NOT NULL, FOREIGN KEY(`playlistId`) REFERENCES `music_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_tracks_playlistId` ON `music_tracks` (`playlistId`)")
            }
        }
        /** Favourites + genre tags on music tracks. Additive; existing rows keep all data. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE music_tracks ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE music_tracks ADD COLUMN genre TEXT NOT NULL DEFAULT 'UNCLASSIFIED'")
            }
        }
        /** Custom video playlists. Purely additive, so all existing data is preserved. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `video_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `video_playlist_items` (`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`), FOREIGN KEY(`playlistId`) REFERENCES `video_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`videoId`) REFERENCES `videos`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_playlist_items_playlistId` ON `video_playlist_items` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_playlist_items_videoId` ON `video_playlist_items` (`videoId`)")
            }
        }
        fun get(context: Context): VoxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, VoxDatabase::class.java, "vox.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration() // last resort: only when no migration path exists
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        listOf("General", "Gardening", "Coding").forEachIndexed { i, n ->
                            db.execSQL("INSERT INTO sub_feeds (name, sortOrder) VALUES ('$n', $i)")
                        }
                    }
                })
                .build().also { instance = it }
        }
    }
}
