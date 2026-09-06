package net.kourlas.dashtone.service.data.mediastore

interface LibraryScanner {
    /** Re-reads the full audio source and upserts all songs/albums/artists/playlists found. The
     * library is just a cache of what's on the file system, so every scan also deletes rows no
     * longer present in the source (including albums/artists left with no remaining songs, and
     * playlists whose backing file is gone), to keep the cache honest. Skips songs in a folder
     * excluded via [net.kourlas.dashtone.service.data.prefs.AppPreferences.excludedFolders].
     *
     * An automatic scan can run before MediaStore has finished (re-)indexing storage -- most
     * notably right after car ignition, when this scanner (and the service hosting it) has just
     * been freshly created -- so [force] defaults to false: for a brief grace period after this
     * scanner is created, a missing song is left alone rather than deleted, since it may simply
     * not be indexed yet (see the scanner implementation's own comment on why that's scoped to
     * just after creation, not the scanner's whole lifetime). [force] = true skips that grace
     * period and deletes immediately, for the one case where waiting would be actively wrong: an
     * explicit, deliberate user action -- tapping "Rescan Library," or just having granted the
     * permission this scan needed -- that legitimately means "sync to what's there right now,"
     * not "catch up eventually." */
    suspend fun scanLibrary(force: Boolean = false)

    /** All top-level folders (see [topLevelFolderOf]), one per distinct (volume, folder) pair,
     * currently found in the audio source, regardless of the current exclusion settings -- used
     * to populate the exclusion list itself, so an already-excluded folder doesn't disappear from
     * the list once it's excluded. */
    suspend fun availableFolders(): List<LibraryFolder>
}
