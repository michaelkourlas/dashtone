package net.kourlas.dashtone.service.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the real Room-generated SQL (ordering, upsert conflict resolution, orphan cleanup)
 * via Robolectric, without needing a connected Android device or emulator. */
@RunWith(RobolectricTestRunner::class)
class DashtoneDatabaseTest {

    private lateinit var database: DashtoneDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DashtoneDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `songs are ordered by title case-insensitively`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(
            listOf(
                songEntity(id = 1, title = "banana"),
                songEntity(id = 2, title = "Apple"),
                songEntity(id = 3, title = "cherry"),
            ),
        )

        val titles = database.songDao().observeAll().first().map { it.title }
        assertEquals(listOf("Apple", "banana", "cherry"), titles)
    }

    @Test
    fun `album songs are ordered by disc then track number`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(
            listOf(
                songEntity(id = 1, title = "Disc2Track1", discNumber = 2, trackNumber = 1),
                songEntity(id = 2, title = "Disc1Track2", discNumber = 1, trackNumber = 2),
                songEntity(id = 3, title = "Disc1Track1", discNumber = 1, trackNumber = 1),
            ),
        )

        val ids = database.songDao().observeForAlbum(1).first().map { it.id }
        assertEquals(listOf(3L, 2L, 1L), ids)
    }

    @Test
    fun `deleting a song orphans its album and artist for cleanup`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(listOf(songEntity(id = 1, title = "Solo")))

        database.songDao().deleteByIds(listOf(1))
        database.albumDao().deleteOrphans()
        database.artistDao().deleteOrphans()

        assertTrue(database.albumDao().getAllIds().isEmpty())
        assertTrue(database.artistDao().getAllIds().isEmpty())
    }

    @Test
    fun `upsert updates an existing row in place`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(listOf(songEntity(id = 1, title = "Old")))
        database.songDao().upsertAll(listOf(songEntity(id = 1, title = "New")))

        val songs = database.songDao().observeAll().first()
        assertEquals(1, songs.size)
        assertEquals("New", songs.single().title)
    }

    @Test
    fun `queue state singleton row is absent until written`() = runTest {
        assertNull(database.queueStateDao().get())
    }

    @Test
    fun `playlist songs are ordered by playlist position, not track number`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(
            listOf(
                songEntity(id = 1, title = "First Track", trackNumber = 1),
                songEntity(id = 2, title = "Second Track", trackNumber = 2),
            ),
        )
        database.playlistDao().upsertAll(listOf(PlaylistEntity(id = 1, name = "Road Trip", dateModified = 0)))
        database.playlistDao().insertEntries(
            listOf(
                PlaylistEntryEntity(playlistId = 1, position = 0, songId = 2),
                PlaylistEntryEntity(playlistId = 1, position = 1, songId = 1),
            ),
        )

        val ids = database.playlistDao().observeSongsForPlaylist(1).first().map { it.id }
        assertEquals(listOf(2L, 1L), ids)
    }

    @Test
    fun `deleting a playlist cascades to its entries`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(listOf(songEntity(id = 1, title = "Solo")))
        database.playlistDao().upsertAll(listOf(PlaylistEntity(id = 1, name = "Road Trip", dateModified = 0)))
        database.playlistDao().insertEntries(listOf(PlaylistEntryEntity(playlistId = 1, position = 0, songId = 1)))

        database.playlistDao().deleteByIds(listOf(1))

        assertTrue(database.playlistDao().observeSongsForPlaylist(1).first().isEmpty())
    }

    @Test
    fun `deleting a song cascades to its playlist entries`() = runTest {
        seedArtistAndAlbum()
        database.songDao().upsertAll(listOf(songEntity(id = 1, title = "Solo")))
        database.playlistDao().upsertAll(listOf(PlaylistEntity(id = 1, name = "Road Trip", dateModified = 0)))
        database.playlistDao().insertEntries(listOf(PlaylistEntryEntity(playlistId = 1, position = 0, songId = 1)))

        database.songDao().deleteByIds(listOf(1))

        assertTrue(database.playlistDao().observeSongsForPlaylist(1).first().isEmpty())
    }

    private suspend fun seedArtistAndAlbum() {
        database.artistDao().upsertAll(listOf(ArtistEntity(id = 1, name = "Artist")))
        database.albumDao().upsertAll(
            listOf(AlbumEntity(id = 1, name = "Album", artistId = 1, year = null, trackCount = 1)),
        )
    }

    private fun songEntity(
        id: Long,
        title: String,
        discNumber: Int = 1,
        trackNumber: Int = 1,
    ) = SongEntity(
        id = id,
        title = title,
        trackNumber = trackNumber,
        discNumber = discNumber,
        durationMs = 200_000,
        albumId = 1,
        artistId = 1,
        contentUri = "content://media/external/audio/media/$id",
        dateAdded = 0,
        dateModified = 0,
    )
}
