package net.kourlas.dashtone.service.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Backed by an M3U/M3U8 playlist file on disk (see [net.kourlas.dashtone.service.data.mediastore.M3uParser]);
 * there is no in-app playlist creation, so this table is always fully re-derived from a scan
 * rather than user-edited directly.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val dateModified: Long,
)
