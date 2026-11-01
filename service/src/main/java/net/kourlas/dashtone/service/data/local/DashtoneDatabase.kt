package net.kourlas.dashtone.service.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        QueueStateEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class DashtoneDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun queueStateDao(): QueueStateDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        // A destructive fallback would also drop queue_state, and with it the queue that the car
        // resumes on ignition. The next scan fills the new column in for every song.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN volumeName TEXT")
            }
        }
    }
}
