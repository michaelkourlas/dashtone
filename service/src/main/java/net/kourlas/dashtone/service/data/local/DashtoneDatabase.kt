package net.kourlas.dashtone.service.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

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
}
