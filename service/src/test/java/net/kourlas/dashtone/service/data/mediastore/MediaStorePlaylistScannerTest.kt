package net.kourlas.dashtone.service.data.mediastore

import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.mediastore.MediaStorePlaylistScanner.Companion.STARTUP_GRACE_PERIOD_MS
import net.kourlas.dashtone.service.testing.FakeMediaStorePlaylistSource
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import net.kourlas.dashtone.service.testing.rawPlaylistFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaStorePlaylistScannerTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var source: FakeMediaStorePlaylistSource
    private lateinit var scanner: MediaStorePlaylistScanner
    private var fakeClockMs = 0L

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        playlistDao = FakePlaylistDao(songDao)
        source = FakeMediaStorePlaylistSource()
        scanner = MediaStorePlaylistScanner(source, playlistDao)
        scanner.elapsedRealtimeMs = { fakeClockMs }
    }

    @Test
    fun `scan resolves entries to song ids in file order, dropping unmatched entries`() = runTest {
        val file = rawPlaylistFile(id = 1, name = "Road Trip", filePath = "/storage/emulated/0/Playlists/Road Trip.m3u")
        source.files = listOf(file)
        source.textByContentUri = mapOf(
            file.contentUri to """
                /storage/emulated/0/Music/B.mp3
                /storage/emulated/0/Music/Missing.mp3
                /storage/emulated/0/Music/A.mp3
            """.trimIndent(),
        )
        val pathToSongId = mapOf(
            "/storage/emulated/0/Music/A.mp3" to 1L,
            "/storage/emulated/0/Music/B.mp3" to 2L,
        )

        scanner.scanPlaylists(pathToSongId)

        assertEquals("Road Trip", playlistDao.current.getValue(1).name)
        assertEquals(listOf(2L, 1L), playlistDao.currentEntries.getValue(1).sortedBy { it.position }.map { it.songId })
    }

    @Test
    fun `scan resolves relative entries against the playlist file's own directory`() = runTest {
        val file = rawPlaylistFile(id = 1, name = "Road Trip", filePath = "/storage/emulated/0/Playlists/Road Trip.m3u")
        source.files = listOf(file)
        source.textByContentUri = mapOf(file.contentUri to "../Music/A.mp3")
        val pathToSongId = mapOf("/storage/emulated/0/Music/A.mp3" to 1L)

        scanner.scanPlaylists(pathToSongId)

        assertEquals(listOf(1L), playlistDao.currentEntries.getValue(1).map { it.songId })
    }

    @Test
    fun `rescanning removes a playlist whose backing file is gone once the startup grace period has passed`() = runTest {
        val file = rawPlaylistFile(id = 1, name = "Road Trip", filePath = "/storage/emulated/0/Playlists/Road Trip.m3u")
        source.files = listOf(file)
        source.textByContentUri = mapOf(file.contentUri to "/storage/emulated/0/Music/A.mp3")
        scanner.scanPlaylists(mapOf("/storage/emulated/0/Music/A.mp3" to 1L))
        assertTrue(playlistDao.current.containsKey(1))

        source.files = emptyList()
        fakeClockMs += STARTUP_GRACE_PERIOD_MS
        scanner.scanPlaylists(emptyMap())

        assertFalse(playlistDao.current.containsKey(1))
        assertTrue(playlistDao.currentEntries[1].isNullOrEmpty())
    }

    // force = true (an explicit, deliberate user action) must delete immediately regardless of
    // how recently the scanner was created -- see MediaStoreLibraryScanner's equivalent test.
    @Test
    fun `force scanning deletes a missing playlist immediately, even during the startup grace period`() = runTest {
        val file = rawPlaylistFile(id = 1, name = "Road Trip", filePath = "/storage/emulated/0/Playlists/Road Trip.m3u")
        source.files = listOf(file)
        source.textByContentUri = mapOf(file.contentUri to "/storage/emulated/0/Music/A.mp3")
        scanner.scanPlaylists(mapOf("/storage/emulated/0/Music/A.mp3" to 1L))

        source.files = emptyList()
        scanner.scanPlaylists(emptyMap(), force = true)

        assertFalse(playlistDao.current.containsKey(1))
    }

    // Same guard as MediaStoreLibraryScanner.scanLibrary (see its own comment): an empty scan
    // result while several playlists are already known is far more likely to mean MediaStore
    // hasn't finished indexing yet than that the user deleted them all between connections.
    @Test
    fun `an empty scan result does not delete existing playlists`() = runTest {
        val files = (1..5L).map { id -> rawPlaylistFile(id = id, name = "Playlist $id", filePath = "/storage/emulated/0/Playlists/$id.m3u") }
        source.files = files
        scanner.scanPlaylists(emptyMap())
        assertEquals(5, playlistDao.current.size)

        source.files = emptyList()
        scanner.scanPlaylists(emptyMap())

        assertEquals(5, playlistDao.current.size)
    }

    @Test
    fun `rescanning replaces a playlist's entries rather than appending to them`() = runTest {
        val file = rawPlaylistFile(id = 1, name = "Road Trip", filePath = "/storage/emulated/0/Playlists/Road Trip.m3u")
        source.files = listOf(file)
        source.textByContentUri = mapOf(file.contentUri to "/storage/emulated/0/Music/A.mp3")
        scanner.scanPlaylists(mapOf("/storage/emulated/0/Music/A.mp3" to 1L))

        source.textByContentUri = mapOf(file.contentUri to "/storage/emulated/0/Music/B.mp3")
        scanner.scanPlaylists(mapOf("/storage/emulated/0/Music/B.mp3" to 2L))

        assertEquals(listOf(2L), playlistDao.currentEntries.getValue(1).map { it.songId })
    }
}
