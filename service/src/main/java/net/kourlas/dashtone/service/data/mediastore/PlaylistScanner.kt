package net.kourlas.dashtone.service.data.mediastore

interface PlaylistScanner {
    /** Re-reads all playlist files, resolving each entry to a song id via [pathToSongId] (built
     * by the caller from the same audio scan pass so paths and songs are always in sync). Entries
     * that don't match a known song (moved/deleted files, streaming URLs) are silently dropped.
     * A playlist whose backing file no longer exists is deleted too -- the library is just a
     * cache of what's on the file system, see [LibraryScanner.scanLibrary]'s doc comment, which
     * [force] also matches exactly: false withholds deletion during the brief grace period right
     * after this scanner was created, true (an explicit user-requested rescan) deletes immediately
     * regardless of when the scanner was created, and additionally re-reads every playlist file.
     *
     * [librarySongsChanged] reports whether the scan that produced [pathToSongId] added or removed
     * songs. A playlist file whose own timestamp is unchanged is otherwise left alone, and this is
     * what keeps that from stranding entries: an entry that resolved to nothing on an earlier scan
     * (its song not yet indexed by MediaStore) resolves once that song arrives, even though the
     * file itself never changed. */
    suspend fun scanPlaylists(
        pathToSongId: Map<String, Long>,
        force: Boolean = false,
        librarySongsChanged: Boolean = true,
    )
}
