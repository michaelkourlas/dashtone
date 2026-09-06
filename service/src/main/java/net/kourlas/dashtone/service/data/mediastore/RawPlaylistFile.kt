package net.kourlas.dashtone.service.data.mediastore

/** A single MediaStore playlist-file row, decoupled from android.database.Cursor/Uri for
 * testability, mirroring [RawAudioRow]. */
data class RawPlaylistFile(
    val id: Long,
    val name: String,
    val contentUri: String,
    val filePath: String,
    val dateModified: Long,
)
