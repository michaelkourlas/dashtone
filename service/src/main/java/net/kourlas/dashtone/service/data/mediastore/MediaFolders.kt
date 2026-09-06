package net.kourlas.dashtone.service.data.mediastore

/**
 * The top-level folder a song lives in, derived from MediaStore's RELATIVE_PATH column (e.g.
 * "Music/ABBA/Gold/" -> "Music", "Podcasts/Show/" -> "Podcasts"). RELATIVE_PATH is always relative
 * to the containing volume's public root, so this stays consistent across internal/removable
 * storage and multi-user profiles -- unlike the raw DATA path, whose prefix varies by volume/user
 * (see AAOS emulator testing notes on emulated;0 vs emulated;10).
 */
internal fun topLevelFolderOf(relativePath: String?): String? =
    relativePath?.trim('/')?.substringBefore('/')?.takeIf { it.isNotEmpty() }

/** The full folder path a song lives in (e.g. "Music/ABBA/Gold/" -> "Music/ABBA/Gold"), backing
 * the Folders browse category -- unlike [topLevelFolderOf], which only needs the first segment
 * for the folder-exclusion setting, this keeps the whole path so [net.kourlas.dashtone.service
 * .playback.LibraryBrowseTree] can reconstruct real subfolder nesting at browse time. */
internal fun fullFolderPathOf(relativePath: String?): String? =
    relativePath?.trim('/')?.takeIf { it.isNotEmpty() }

/**
 * Stable identifier for a top-level folder on a specific storage volume, used both to store
 * exclusions ([net.kourlas.dashtone.service.data.prefs.AppPreferences.excludedFolders]) and to
 * filter a scan against them. RELATIVE_PATH alone ("Music/...") is volume-relative -- internal
 * storage and a removable SD card/USB volume can each have their own top-level folder with the
 * exact same name, and without the volume folded in here, excluding one would silently also
 * exclude the other. Null if either half is unknown (API < 29, no VOLUME_NAME/RELATIVE_PATH
 * columns), matching the pre-volume-aware behavior of simply never excluding such rows.
 */
internal fun folderKey(volumeName: String?, topLevelFolder: String?): String? =
    if (volumeName == null || topLevelFolder == null) null else "$volumeName/$topLevelFolder"
