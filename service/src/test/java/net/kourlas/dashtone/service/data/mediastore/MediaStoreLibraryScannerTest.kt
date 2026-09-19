package net.kourlas.dashtone.service.data.mediastore

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.mediastore.MediaStoreLibraryScanner.Companion.STARTUP_GRACE_PERIOD_MS
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakeMediaStoreAudioSource
import net.kourlas.dashtone.service.testing.FakePlaylistScanner
import net.kourlas.dashtone.service.testing.FakeSongDao
import net.kourlas.dashtone.service.testing.rawAudioRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaStoreLibraryScannerTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var source: FakeMediaStoreAudioSource
    private lateinit var playlistScanner: FakePlaylistScanner
    private lateinit var appPreferences: AppPreferences
    private lateinit var scanner: MediaStoreLibraryScanner
    private var fakeClockMs = 0L

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        source = FakeMediaStoreAudioSource()
        playlistScanner = FakePlaylistScanner()
        appPreferences = AppPreferences(FakeDataStore(emptyPreferences()))
        scanner = MediaStoreLibraryScanner(source, playlistScanner, songDao, albumDao, artistDao, appPreferences)
        scanner.elapsedRealtimeMs = { fakeClockMs }
    }

    @Test
    fun `scan groups songs into albums and artists`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
            rawAudioRow(id = 2, title = "Song B", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 2),
            rawAudioRow(id = 3, title = "Song C", artistId = 20, artistName = "Artist Y", albumId = 200, albumName = "Album 2", rawTrack = 1),
        )

        scanner.scanLibrary()

        assertEquals(3, songDao.current.size)
        assertEquals(2, albumDao.current.size)
        assertEquals(2, artistDao.current.size)
        assertEquals("Artist X", artistDao.current.getValue(10).name)
        assertEquals(100L, songDao.current.getValue(1).albumId)
    }

    @Test
    fun `scan decodes disc and track number from packed track column`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 2005),
        )

        scanner.scanLibrary()

        val song = songDao.current.getValue(1)
        assertEquals(2, song.discNumber)
        assertEquals(5, song.trackNumber)
    }

    @Test
    fun `scan derives the full folder path from the row's relative path`() = runTest {
        source.rows = listOf(
            rawAudioRow(
                id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1",
                relativePath = "Music/ABBA/Gold/",
            ),
        )

        scanner.scanLibrary()

        assertEquals("Music/ABBA/Gold", songDao.current.getValue(1).folderPath)
    }

    @Test
    fun `rescanning removes songs no longer present and cleans up orphan album and artist once the startup grace period has passed`() =
        runTest {
            source.rows = listOf(
                rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
            )
            scanner.scanLibrary()
            assertTrue(songDao.current.containsKey(1))
            assertTrue(albumDao.current.containsKey(100))
            assertTrue(artistDao.current.containsKey(10))

            source.rows = emptyList()
            fakeClockMs += STARTUP_GRACE_PERIOD_MS
            scanner.scanLibrary()

            assertTrue(songDao.current.isEmpty())
            assertTrue(albumDao.current.isEmpty())
            assertTrue(artistDao.current.isEmpty())
        }

    // Retagging keeps a song's MediaStore id, so this scan deletes no songs at all -- orphan
    // cleanup has to run anyway or the vacated album lingers forever as an empty entry in the
    // Albums list. The clock stays inside the startup grace period to show the cleanup is
    // independent of the song-deletion path it used to be gated behind.
    @Test
    fun `retagging a song onto another album removes the album it left behind`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()
        assertTrue(albumDao.current.containsKey(100))

        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 200, albumName = "Album 2", rawTrack = 1),
        )
        scanner.scanLibrary()

        assertTrue("the song itself survives a retag", songDao.current.containsKey(1))
        assertEquals(200L, songDao.current.getValue(1).albumId)
        assertTrue("the new album is present", albumDao.current.containsKey(200))
        assertFalse("the vacated album is cleaned up", albumDao.current.containsKey(100))
    }

    // An artist left with no songs goes the same way as an orphaned album, and is cleaned up after
    // albums so an album row is gone before the artist it points at is considered.
    @Test
    fun `retagging a song onto another artist removes the artist it left behind`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()
        assertTrue(artistDao.current.containsKey(10))

        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 20, artistName = "Artist Y", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()

        assertTrue("the song itself survives a retag", songDao.current.containsKey(1))
        assertTrue("the new artist is present", artistDao.current.containsKey(20))
        assertFalse("the vacated artist is cleaned up", artistDao.current.containsKey(10))
    }

    // force = true is for an explicit, deliberate user action (the "Rescan Library" button, a
    // just-granted permission, a folder-inclusion change) -- it must delete immediately regardless
    // of how recently the scanner was created, unlike every other test in this class, which never
    // advances the clock past the startup grace period.
    @Test
    fun `force scanning deletes missing songs immediately, even during the startup grace period`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()

        source.rows = emptyList()
        scanner.scanLibrary(force = true)

        assertTrue(songDao.current.isEmpty())
        assertTrue(albumDao.current.isEmpty())
        assertTrue(artistDao.current.isEmpty())
    }

    // Guards against a real production bug: at car ignition, MediaStore can take a noticeable
    // time to (re-)index external/USB storage after boot, so a scan run soon after this scanner
    // is freshly created by that same ignition can see far fewer songs than actually exist -- in
    // the worst case, none at all. Treating that as ground truth would wipe out the whole library
    // (and, via QueueStateStore's song-id resolution, most of the resumable queue) purely because
    // the scan ran too early, not because anything was actually deleted. See
    // MediaStoreLibraryScanner.startupGraceDeadlineMs's own comment.
    @Test
    fun `an empty scan result does not delete an existing library during the startup grace period`() = runTest {
        source.rows = (1..10L).map { id ->
            rawAudioRow(id = id, title = "Song $id", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = id.toInt())
        }
        scanner.scanLibrary()
        assertEquals(10, songDao.current.size)

        source.rows = emptyList()
        scanner.scanLibrary()

        assertEquals(10, songDao.current.size)
        assertTrue(albumDao.current.containsKey(100))
        assertTrue(artistDao.current.containsKey(10))
    }

    // The exact real-world failure pattern, confirmed live: a single car.media connection
    // handshake calls root() -- and so rescan() -- three times within about 75ms. Holding the
    // clock nearly still across repeated scans confirms that burst alone, however many calls it
    // involves, can never itself advance past the startup grace period.
    @Test
    fun `several rapid rescans within milliseconds still do not delete a missing song`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()

        source.rows = emptyList()
        repeat(3) {
            fakeClockMs += 25L // ~75ms total, matching the observed real-world burst
            scanner.scanLibrary()
        }

        assertTrue(songDao.current.containsKey(1))
    }

    // Confirms the grace period is scoped to just after the scanner is created, not to the
    // scanner's whole lifetime: a song that goes missing well into an already-running session
    // (long past the grace period) is deleted on the very next scan, with no further waiting --
    // exactly the pre-fix behavior for the case the fix was never meant to change.
    @Test
    fun `a song that goes missing after the startup grace period has passed is removed on the next scan`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
            rawAudioRow(id = 2, title = "Song B", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 2),
        )
        scanner.scanLibrary()
        fakeClockMs += STARTUP_GRACE_PERIOD_MS + 1

        source.rows = source.rows.filter { it.id != 1L }
        scanner.scanLibrary()

        assertFalse(songDao.current.containsKey(1))
        assertTrue(songDao.current.containsKey(2))
        assertTrue(albumDao.current.containsKey(100))
        assertTrue(artistDao.current.containsKey(10))
    }

    @Test
    fun `rescanning updates changed metadata in place rather than duplicating`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Old Title", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()

        source.rows = listOf(
            rawAudioRow(id = 1, title = "New Title", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1", rawTrack = 1),
        )
        scanner.scanLibrary()

        assertEquals(1, songDao.current.size)
        assertEquals("New Title", songDao.current.getValue(1).title)
        assertNull(songDao.current[2])
    }

    @Test
    fun `scan builds a path-to-song-id map for the playlist scanner from rows with a file path`() = runTest {
        source.rows = listOf(
            rawAudioRow(
                id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100,
                albumName = "Album 1", filePath = "/storage/emulated/0/Music/Song A.mp3",
            ),
            rawAudioRow(
                id = 2, title = "Song B", artistId = 10, artistName = "Artist X", albumId = 100,
                albumName = "Album 1", filePath = null,
            ),
        )

        scanner.scanLibrary()

        assertEquals(mapOf("/storage/emulated/0/Music/Song A.mp3" to 1L), playlistScanner.lastPathToSongId)
    }

    // The playlist scanner leaves an unchanged file alone unless the songs its entries could
    // resolve to are different now -- see PlaylistScanner.scanPlaylists.
    @Test
    fun `the playlist scanner is told the library gained songs`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1"),
        )

        scanner.scanLibrary()

        assertEquals(true, playlistScanner.lastLibrarySongsChanged)
    }

    @Test
    fun `the playlist scanner is told nothing changed when a rescan finds the same songs`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1"),
        )
        scanner.scanLibrary()

        scanner.scanLibrary()

        assertEquals(false, playlistScanner.lastLibrarySongsChanged)
    }

    // A song the scan missed during the startup grace period is not deleted, so its playlist
    // entries should stay put too -- this reports what left the library, not what a scan missed.
    @Test
    fun `a song missing only during the startup grace period does not count as a library change`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1"),
        )
        scanner.scanLibrary()

        source.rows = emptyList()
        scanner.scanLibrary()

        assertEquals(false, playlistScanner.lastLibrarySongsChanged)
        assertTrue("the song itself is still there", songDao.current.containsKey(1))
    }

    @Test
    fun `a song that actually leaves the library counts as a library change`() = runTest {
        source.rows = listOf(
            rawAudioRow(id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100, albumName = "Album 1"),
        )
        scanner.scanLibrary()

        source.rows = emptyList()
        fakeClockMs += STARTUP_GRACE_PERIOD_MS
        scanner.scanLibrary()

        assertEquals(true, playlistScanner.lastLibrarySongsChanged)
    }

    @Test
    fun `songs in an excluded top-level folder are left out of the scan`() = runTest {
        appPreferences.setExcludedFolders(setOf("external_primary/Podcasts"))
        source.rows = listOf(
            rawAudioRow(
                id = 1, title = "Song A", artistId = 10, artistName = "Artist X", albumId = 100,
                albumName = "Album 1", relativePath = "Music/Album 1/",
            ),
            rawAudioRow(
                id = 2, title = "Episode 1", artistId = 20, artistName = "Show", albumId = 200,
                albumName = "Podcast", relativePath = "Podcasts/Show/",
            ),
        )

        scanner.scanLibrary()

        assertTrue(songDao.current.containsKey(1))
        assertFalse(songDao.current.containsKey(2))
    }

    @Test
    fun `re-including a previously excluded folder brings its songs back on the next scan`() = runTest {
        source.rows = listOf(
            rawAudioRow(
                id = 1, title = "Episode 1", artistId = 20, artistName = "Show", albumId = 200,
                albumName = "Podcast", relativePath = "Podcasts/Show/",
            ),
        )
        appPreferences.setExcludedFolders(setOf("external_primary/Podcasts"))
        scanner.scanLibrary()
        assertFalse(songDao.current.containsKey(1))

        appPreferences.setExcludedFolders(emptySet())
        scanner.scanLibrary()

        assertTrue(songDao.current.containsKey(1))
    }

    @Test
    fun `availableFolders lists distinct top-level folders regardless of exclusion settings`() = runTest {
        appPreferences.setExcludedFolders(setOf("external_primary/Podcasts"))
        source.rows = listOf(
            rawAudioRow(id = 1, title = "A", artistId = 1, artistName = "X", albumId = 1, albumName = "A1", relativePath = "Music/A1/"),
            rawAudioRow(id = 2, title = "B", artistId = 1, artistName = "X", albumId = 1, albumName = "A1", relativePath = "Music/A1/"),
            rawAudioRow(id = 3, title = "C", artistId = 2, artistName = "Y", albumId = 2, albumName = "A2", relativePath = "Podcasts/Show/"),
        )

        val folders = scanner.availableFolders()

        assertEquals(
            listOf(
                LibraryFolder(key = "external_primary/Music", volumeName = "external_primary", topLevelFolder = "Music"),
                LibraryFolder(key = "external_primary/Podcasts", volumeName = "external_primary", topLevelFolder = "Podcasts"),
            ),
            folders,
        )
    }

    @Test
    fun `two volumes with same-named folders are kept as distinct entries, and excluding one doesn't affect the other`() =
        runTest {
            appPreferences.setExcludedFolders(setOf("external_primary/Music"))
            source.rows = listOf(
                rawAudioRow(
                    id = 1, title = "A", artistId = 1, artistName = "X", albumId = 1, albumName = "A1",
                    relativePath = "Music/A1/", volumeName = "external_primary",
                ),
                rawAudioRow(
                    id = 2, title = "B", artistId = 1, artistName = "X", albumId = 1, albumName = "A1",
                    relativePath = "Music/A1/", volumeName = "1234-5678",
                ),
            )

            val folders = scanner.availableFolders()
            assertEquals(
                listOf(
                    LibraryFolder(key = "1234-5678/Music", volumeName = "1234-5678", topLevelFolder = "Music"),
                    LibraryFolder(key = "external_primary/Music", volumeName = "external_primary", topLevelFolder = "Music"),
                ),
                folders,
            )

            scanner.scanLibrary()
            assertFalse(songDao.current.containsKey(1))
            assertTrue(songDao.current.containsKey(2))
        }
}
