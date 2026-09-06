package net.kourlas.dashtone.service.data.mediastore

/** Source of the device's playlist files. Abstracted from ContentResolver so [PlaylistScanner]'s
 * parsing/matching logic can be unit-tested without an Android runtime. */
interface MediaStorePlaylistSource {
    fun queryAllPlaylistFiles(): List<RawPlaylistFile>

    /** Raw text content of the playlist file at [contentUri], or null if it can no longer be
     * read (e.g. removed between the file listing and this read). */
    fun readText(contentUri: String): String?
}
