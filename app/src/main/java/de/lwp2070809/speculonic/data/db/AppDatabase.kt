package de.lwp2070809.speculonic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        AlbumListItemEntity::class,
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlaybackQueueEntity::class,
        SyncTempIdEntity::class
    ],
    version = 18,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        
        
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlist_song_cross_ref_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        playlistId TEXT NOT NULL,
                        songId TEXT NOT NULL,
                        `order` INTEGER NOT NULL
                    )
                """.trimIndent())
                
                db.execSQL("""
                    INSERT INTO playlist_song_cross_ref_new (playlistId, songId, `order`)
                    SELECT playlistId, songId, `order` FROM playlist_song_cross_ref
                """.trimIndent())
                
                db.execSQL("DROP TABLE playlist_song_cross_ref")
                
                db.execSQL("ALTER TABLE playlist_song_cross_ref_new RENAME TO playlist_song_cross_ref")
                
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_song_cross_ref_playlistId_songId_order ON playlist_song_cross_ref (playlistId, songId, `order`)")
            }
        }

        
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `song_interaction_stats` (
                        `songId` TEXT NOT NULL, 
                        `clickCount` INTEGER NOT NULL, 
                        `skipCount` INTEGER NOT NULL, 
                        `completeCount` INTEGER NOT NULL, 
                        `lastInteractionTime` INTEGER NOT NULL, 
                        PRIMARY KEY(`songId`)
                    )
                """.trimIndent())
            }
        }

        
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `song_interaction_stats`")
            }
        }

        
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "speculonic_database"
                )
                    .addMigrations(MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                    
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

