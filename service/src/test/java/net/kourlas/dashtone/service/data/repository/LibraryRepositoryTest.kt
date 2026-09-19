package net.kourlas.dashtone.service.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.testing.FakeAlbumArtInvalidator
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Mimics a real scan populating the song table, so tests can distinguish "never scanned" from
 * "scanned but the DB was since wiped out from under the first-run flag" (Room destructive
 * migrations do exactly this). */
private class FakeLibraryScanner(private val songDao: FakeSongDao) : LibraryScanner {
    var scanCount = 0
        private set
    var lastForce: Boolean? = null
        private set

    override suspend fun scanLibrary(force: Boolean) {
        scanCount++
        lastForce = force
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1,
                    title = "Song",
                    trackNumber = 1,
                    discNumber = 1,
                    durationMs = 1000,
                    albumId = 1,
                    artistId = 1,
                    contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0,
                    dateModified = 0,
                ),
            ),
        )
    }

    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

class LibraryRepositoryTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var scanner: FakeLibraryScanner
    private lateinit var preferences: AppPreferences
    private lateinit var albumArtInvalidator: FakeAlbumArtInvalidator
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        playlistDao = FakePlaylistDao(songDao)
        scanner = FakeLibraryScanner(songDao)
        preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        albumArtInvalidator = FakeAlbumArtInvalidator()
        repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, scanner, preferences, albumArtInvalidator,
        )
    }

    // Album art is cached by album id, which a re-tag preserves, so a replaced cover stays hidden
    // until the cache is dropped. A forced scan is the deliberate user action ("Rescan Library", a
    // just-granted permission, a folder-inclusion change) worth paying that re-extraction for.
    @Test
    fun `a forced rescan drops the album art cache`() = runTest {
        repository.rescan(force = true)

        assertEquals(1, albumArtInvalidator.clearCount)
    }

    // LibraryChangeObserver fires on routine file-system noise, so an automatic catch-up scan
    // leaving the cache alone is what keeps that from re-extracting every cover in the library.
    @Test
    fun `an automatic rescan leaves the album art cache alone`() = runTest {
        repository.rescan()
        repository.ensureScanned()

        assertEquals(0, albumArtInvalidator.clearCount)
    }

    @Test
    fun `ensureScanned triggers a scan only on first run`() = runTest {
        repository.ensureScanned()
        assertEquals(1, scanner.scanCount)

        repository.ensureScanned()
        assertEquals(1, scanner.scanCount)
    }

    @Test
    fun `rescan always triggers a scan regardless of first run state`() = runTest {
        repository.rescan()
        repository.rescan()
        assertEquals(2, scanner.scanCount)
    }

    // The scanner's own missing-song confirmation delay only applies to force = false; an
    // automatic rescan (the default) must not force through it.
    @Test
    fun `rescan defaults to not forcing the scanner's confirmation delay`() = runTest {
        repository.rescan()
        assertEquals(false, scanner.lastForce)
    }

    // An explicit settings change (which folders are included) expects the library to reflect it
    // immediately, not after the delay an automatic scan waits out -- see
    // LibraryRepository.setExcludedFolders's own comment.
    @Test
    fun `setExcludedFolders always triggers a rescan that forces past the scanner's confirmation delay`() = runTest {
        repository.setExcludedFolders(setOf("external_primary/Podcasts"))
        assertEquals(1, scanner.scanCount)
        assertEquals(true, scanner.lastForce)
    }

    @Test
    fun `ensureScanned after an explicit rescan does not scan again`() = runTest {
        repository.rescan()
        repository.ensureScanned()
        assertEquals(1, scanner.scanCount)
    }

    @Test
    fun `ensureScanned rescans when the song table is empty even if first run already completed`() = runTest {
        repository.rescan()
        assertEquals(1, scanner.scanCount)

        // Simulates a Room destructive-migration wipe: the song table goes back to empty, but
        // hasCompletedFirstRun (a separate DataStore flag untouched by the Room migration) stays
        // true, so only the songs-empty check -- not the first-run flag -- can catch this.
        songDao.deleteByIds(songDao.getAllIds())

        repository.ensureScanned()
        assertEquals(2, scanner.scanCount)
    }
}
