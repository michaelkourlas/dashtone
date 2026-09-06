package net.kourlas.dashtone.service.data.mediastore

/**
 * A top-level folder found on a specific storage volume, surfaced to the folder-exclusion setting.
 * [key] (see [folderKey]) is what actually gets persisted/matched against; [volumeName] is kept
 * alongside it purely so the UI can label which physical storage a folder lives on when that's
 * ambiguous (two volumes sharing a folder name).
 */
data class LibraryFolder(
    val key: String,
    val volumeName: String,
    val topLevelFolder: String,
)
