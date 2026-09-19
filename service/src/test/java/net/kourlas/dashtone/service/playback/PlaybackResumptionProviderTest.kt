package net.kourlas.dashtone.service.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.Grouping
import net.kourlas.dashtone.service.testing.FakeAlbumArtInvalidator
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeQueueStateDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoOpScanner : LibraryScanner {
    override suspend fun scanLibrary(force: Boolean) = Unit
    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

/** Runs under Robolectric because [MediaItemMapper] (via [LibraryBrowseTree]) parses real
 * android.net.Uri values. */
@RunWith(RobolectricTestRunner::class)
class PlaybackResumptionProviderTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var queueStateDao: FakeQueueStateDao
    private lateinit var queueStateStore: QueueStateStore
    private lateinit var provider: PlaybackResumptionProvider

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        val albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        queueStateDao = FakeQueueStateDao()
        queueStateStore = QueueStateStore(queueStateDao)
        val preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        val playlistDao = FakePlaylistDao(songDao)
        val repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, NoOpScanner(), preferences, FakeAlbumArtInvalidator(),
        )
        val browseTree = LibraryBrowseTree(repository, MediaItemMapper(ApplicationProvider.getApplicationContext()))
        provider = PlaybackResumptionProvider(browseTree, queueStateStore)
    }

    private suspend fun seedSongs(count: Int) {
        artistDao.upsertAll(listOf(ArtistEntity(id = 1, name = "Artist")))
        songDao.upsertAll(
            (1..count).map { id ->
                SongEntity(
                    id = id.toLong(), title = "Song $id", trackNumber = id, discNumber = 1, durationMs = 200_000,
                    albumId = 1, artistId = 1, contentUri = "content://media/external/audio/media/$id",
                    dateAdded = 0, dateModified = 0,
                )
            },
        )
    }

    @Test
    fun `returns null when nothing has ever played`() = runTest {
        assertNull(provider.resumptionState())
    }

    @Test
    fun `restores the whole queue, in its saved order, at the saved index`() = runTest {
        seedSongs(3)
        queueStateStore.save(
            songIds = listOf(3L, 1L, 2L),
            naturalSongIds = listOf(1L, 2L, 3L),
            currentIndex = 1,
            isShuffled = false,
            grouping = null,
            repeatModeOrdinal = 1,
        )

        val resumption = provider.resumptionState()

        assertEquals(listOf(MediaIds.song(3), MediaIds.song(1), MediaIds.song(2)), resumption?.items?.map { it.mediaId })
        assertEquals(1, resumption?.startIndex)
        assertEquals(1, resumption?.repeatModeOrdinal)
    }

    @Test
    fun `natural items mirror the actual queue when not shuffled`() = runTest {
        seedSongs(2)
        queueStateStore.save(
            songIds = listOf(2L, 1L),
            naturalSongIds = listOf(2L, 1L),
            currentIndex = 0,
            isShuffled = false,
            grouping = null,
            repeatModeOrdinal = 0,
        )

        val resumption = provider.resumptionState()

        assertEquals(false, resumption?.isShuffled)
        assertEquals(resumption?.items?.map { it.mediaId }, resumption?.naturalItems?.map { it.mediaId })
    }

    @Test
    fun `natural items restore the unshuffled fallback order when shuffled, distinct from the actual queue`() =
        runTest {
            seedSongs(3)
            queueStateStore.save(
                songIds = listOf(3L, 1L, 2L),
                naturalSongIds = listOf(1L, 2L, 3L),
                currentIndex = 0,
                isShuffled = true,
                grouping = Grouping.ALBUM,
                repeatModeOrdinal = 0,
            )

            val resumption = provider.resumptionState()

            assertEquals(true, resumption?.isShuffled)
            assertEquals(Grouping.ALBUM, resumption?.grouping)
            assertEquals(listOf(MediaIds.song(3), MediaIds.song(1), MediaIds.song(2)), resumption?.items?.map { it.mediaId })
            assertEquals(listOf(MediaIds.song(1), MediaIds.song(2), MediaIds.song(3)), resumption?.naturalItems?.map { it.mediaId })
        }

    @Test
    fun `drops songs deleted since the queue was saved and clamps the index into the survivors`() = runTest {
        seedSongs(3)
        songDao.deleteByIds(listOf(2L))
        queueStateStore.save(
            songIds = listOf(1L, 2L, 3L),
            naturalSongIds = listOf(1L, 2L, 3L),
            currentIndex = 2,
            isShuffled = false,
            grouping = null,
            repeatModeOrdinal = 0,
        )

        val resumption = provider.resumptionState()

        assertEquals(listOf(MediaIds.song(1), MediaIds.song(3)), resumption?.items?.map { it.mediaId })
        assertEquals(1, resumption?.startIndex)
    }

    @Test
    fun `returns null if every saved song has since been deleted`() = runTest {
        seedSongs(1)
        songDao.deleteByIds(listOf(1L))
        queueStateStore.save(
            songIds = listOf(1L),
            naturalSongIds = listOf(1L),
            currentIndex = 0,
            isShuffled = false,
            grouping = null,
            repeatModeOrdinal = 0,
        )

        assertNull(provider.resumptionState())
    }
}
