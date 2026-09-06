package net.kourlas.dashtone.service.data.mediastore

/** A single MediaStore audio row, decoupled from android.database.Cursor/Uri for testability. */
data class RawAudioRow(
    val id: Long,
    val title: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long,
    val albumName: String,
    val albumYear: Int?,
    val rawTrack: Int,
    val durationMs: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val contentUri: String,
    /** Absolute on-disk path (MediaStore's DATA column), used only to match this song against
     * playlist-file entries during a scan -- never persisted to [net.kourlas.dashtone.service.data.local.SongEntity].
     * Null if MediaStore doesn't expose a path for this row. */
    val filePath: String?,
    /** MediaStore's RELATIVE_PATH column (e.g. "Music/ABBA/Gold/"), used only to determine this
     * row's top-level folder for folder-exclusion filtering -- see [topLevelFolderOf]. */
    val relativePath: String?,
    /** MediaStore's VOLUME_NAME column (e.g. "external_primary", or a lowercase UUID for a
     * removable SD card/USB volume). RELATIVE_PATH alone can't disambiguate two different storage
     * volumes that happen to share a top-level folder name (e.g. "Music" on both internal storage
     * and an SD card) -- see [net.kourlas.dashtone.service.data.mediastore.folderKey]. Null on
     * API < 29, same gating as [relativePath]. */
    val volumeName: String?,
    val genreName: String?,
    val albumArtistName: String?,
    val composerName: String?,
)
