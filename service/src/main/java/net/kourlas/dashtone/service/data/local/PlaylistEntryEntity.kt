package net.kourlas.dashtone.service.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One (playlist, position, song) row rather than a bare join table, since playlist order comes
 * from the backing file and must be preserved -- unlike an album's track order, it can't be
 * re-derived from song metadata. songId cascades on delete so a song removed from the library
 * (e.g. a deleted file) silently drops out of any playlists that referenced it.
 */
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val songId: Long,
)
