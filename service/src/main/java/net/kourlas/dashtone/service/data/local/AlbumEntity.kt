package net.kourlas.dashtone.service.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * artistId is the artist of the first-encountered track on the album during a scan; MediaStore
 * doesn't reliably expose a distinct "album artist" prior to querying per-file metadata, and this
 * simplification matches how most local-file players treat single-artist-per-album libraries.
 */
@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val artistId: Long,
    val year: Int?,
    val trackCount: Int,
)
