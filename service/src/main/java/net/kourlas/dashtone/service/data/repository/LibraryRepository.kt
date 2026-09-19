package net.kourlas.dashtone.service.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.kourlas.dashtone.service.albumart.AlbumArtInvalidator
import net.kourlas.dashtone.service.data.local.AlbumDao
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistDao
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistDao
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.SongDao
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import javax.inject.Inject
import javax.inject.Singleton

/** Single facade the playback and shuffle layers depend on for library access. */
@Singleton
class LibraryRepository @Inject constructor(
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val scanner: LibraryScanner,
    private val preferences: AppPreferences,
    private val albumArtCache: AlbumArtInvalidator,
) {
    val songs: Flow<List<SongEntity>> = songDao.observeAll()
    val albums: Flow<List<AlbumEntity>> = albumDao.observeAll()
    val artists: Flow<List<ArtistEntity>> = artistDao.observeAll()
    val playlists: Flow<List<PlaylistEntity>> = playlistDao.observeAll()
    val excludedFolders: Flow<Set<String>> = preferences.excludedFolders
    val categoryOrder: Flow<List<BrowseCategory>> = preferences.categoryOrder
    val disabledCategories: Flow<Set<BrowseCategory>> = preferences.disabledCategories
    val categoryShuffleMode: Flow<CategoryShuffleMode> = preferences.categoryShuffleMode

    fun songsForAlbum(albumId: Long): Flow<List<SongEntity>> = songDao.observeForAlbum(albumId)
    fun songsForArtist(artistId: Long): Flow<List<SongEntity>> = songDao.observeForArtist(artistId)
    fun albumsForArtist(artistId: Long): Flow<List<AlbumEntity>> = albumDao.observeForArtist(artistId)
    fun songsForPlaylist(playlistId: Long): Flow<List<SongEntity>> = playlistDao.observeSongsForPlaylist(playlistId)

    // Sizes and distinct category values, answered in SQL. The browse tree reports how many
    // children a node has far more often than it renders them (every library change notifies nine
    // roots at once), and going through the observeAll() flows for that means reading every row of
    // several tables and building a MediaItem per song to arrive at an Int.
    suspend fun songCount(): Int = songDao.count()
    suspend fun albumCount(): Int = albumDao.count()
    suspend fun artistCount(): Int = artistDao.count()
    suspend fun playlistCount(): Int = playlistDao.count()
    suspend fun distinctGenreNames(): List<String?> = songDao.distinctGenreNames()
    suspend fun distinctAlbumArtistNames(): List<String?> = songDao.distinctAlbumArtistNames()
    suspend fun distinctComposerNames(): List<String?> = songDao.distinctComposerNames()
    suspend fun distinctFolderPaths(): List<String> = songDao.distinctFolderPaths()
    suspend fun distinctAlbumYears(): List<Int?> = songDao.distinctAlbumYears()
    suspend fun songCountInFolder(folderPath: String): Int = songDao.countInFolder(folderPath)

    suspend fun songById(id: Long): SongEntity? = songDao.getById(id)
    suspend fun albumById(id: Long): AlbumEntity? = albumDao.getById(id)
    suspend fun artistById(id: Long): ArtistEntity? = artistDao.getById(id)
    suspend fun playlistById(id: Long): PlaylistEntity? = playlistDao.getById(id)

    /** Guarantees the browse tree is never left empty after an app update: scans the library on
     * first ever launch, or if the song table is unexpectedly empty despite
     * [AppPreferences.hasCompletedFirstRun] already being set -- which genuinely happens, since a
     * Room schema-version bump (fallbackToDestructiveMigration) drops every table on next DB open
     * while that flag lives in DataStore, an entirely separate store the migration doesn't touch.
     * Without this check, such an update would silently leave the browse tree empty until the user
     * happened to hit "Rescan Library" themselves.
     *
     * Returns whether a scan actually happened, so callers that also want to catch up on ordinary
     * changes (see [LibraryBrowseTree.root]) know whether this already covered that or whether
     * they still need to. */
    suspend fun ensureScanned(): Boolean {
        if (!preferences.hasCompletedFirstRun.first() || songs.first().isEmpty()) {
            rescan()
            return true
        }
        return false
    }

    /** Re-syncs the library with what's actually on the file system: the library is just a cache
     * of it, so every rescan adds, updates, AND deletes rows to match. See
     * [LibraryScanner.scanLibrary]'s own doc comment for what [force] controls -- default false
     * for an automatic catch-up scan (a fresh connection, a detected file-system change); true
     * for a scan triggered by an explicit, deliberate user action that expects the library to
     * reflect the file system right now, not eventually. */
    suspend fun rescan(force: Boolean = false) {
        scanner.scanLibrary(force)
        // A forced scan is the user saying "sync to what's on disk right now," which is also the
        // one moment worth re-reading artwork: album art is cached by album id, and re-tagging a
        // file keeps that id, so a replaced cover is invisible until the cache drops it. An
        // automatic scan leaves the cache alone -- LibraryChangeObserver fires often enough that
        // clearing there would mean re-extracting every cover for routine file-system noise.
        if (force) albumArtCache.clear()
        preferences.setLastScanTimestampMillis(System.currentTimeMillis())
        preferences.setHasCompletedFirstRun(true)
    }

    suspend fun availableFolders(): List<LibraryFolder> = scanner.availableFolders()

    // The user just deliberately toggled which folders are included -- excluding one should
    // remove its songs immediately, not after the same delay meant for an automatic scan racing
    // an unrelated storage-mount timing issue.
    suspend fun setExcludedFolders(folders: Set<String>) {
        preferences.setExcludedFolders(folders)
        rescan(force = true)
    }

    // Presentation-only (which categories show up as tabs, and in what order) -- unlike
    // setExcludedFolders, this never changes what's actually in the library, so no rescan.
    suspend fun setCategoryOrder(order: List<BrowseCategory>) = preferences.setCategoryOrder(order)
    suspend fun setDisabledCategories(categories: Set<BrowseCategory>) = preferences.setDisabledCategories(categories)
    suspend fun setCategoryShuffleMode(mode: CategoryShuffleMode) = preferences.setCategoryShuffleMode(mode)
}
