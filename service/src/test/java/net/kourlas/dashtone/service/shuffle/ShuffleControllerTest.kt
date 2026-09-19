package net.kourlas.dashtone.service.shuffle

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.testing.FakeAlbumArtInvalidator
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class NoOpLibraryScanner : LibraryScanner {
    override suspend fun scanLibrary(force: Boolean) = Unit
    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

/** ShuffleController is a pure computation utility -- there's no persisted "current shuffle
 * state" of its own anymore (see its class doc comment); the single current playback queue is
 * persisted uniformly elsewhere, by QueueStateStore, regardless of which mechanism built it. These
 * tests cover only queue-building correctness. */
class ShuffleControllerTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var repository: LibraryRepository
    private lateinit var controller: ShuffleController

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        val preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        val playlistDao = FakePlaylistDao(songDao)
        repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, NoOpLibraryScanner(), preferences, FakeAlbumArtInvalidator(),
        )
        controller = ShuffleController(repository)
    }

    private suspend fun seedLibrary(albumCount: Int = 3, tracksPerAlbum: Int = 2) {
        val artist = ArtistEntity(id = 1, name = "Artist")
        artistDao.upsertAll(listOf(artist))
        val albums = (1..albumCount).map {
            AlbumEntity(id = it.toLong(), name = "Album $it", artistId = 1, year = null, trackCount = tracksPerAlbum)
        }
        albumDao.upsertAll(albums)
        val songs = albums.flatMap { album ->
            (1..tracksPerAlbum).map { track ->
                val id = album.id * 100 + track
                SongEntity(
                    id = id, title = "Song $id", trackNumber = track, discNumber = 1, durationMs = 200_000,
                    albumId = album.id, artistId = 1, contentUri = "content://media/external/audio/media/$id",
                    dateAdded = 0, dateModified = 0,
                )
            }
        }
        songDao.upsertAll(songs)
    }

    @Test
    fun `applying the Shuffle Albums preset builds a queue where each album plays fully in order`() = runTest {
        seedLibrary()
        val queue = controller.apply(ShufflePresets.SHUFFLE_ALBUMS)
        assertEquals(ShufflePresets.SHUFFLE_ALBUMS, queue.settings)
        val positions = queue.songs.mapIndexed { index, song -> song.id to index }.toMap()
        for (albumId in 1L..3L) {
            val first = albumId * 100 + 1
            val second = albumId * 100 + 2
            assertEquals(positions.getValue(first) + 1, positions.getValue(second))
        }
    }

    @Test
    fun `Shuffle Artists preset groups by artist rather than album`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 1, name = "Artist A"), ArtistEntity(id = 2, name = "Artist B")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 1, name = "Album 1", artistId = 1, year = null, trackCount = 1),
                AlbumEntity(id = 2, name = "Album 2", artistId = 2, year = null, trackCount = 1),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(id = 10, title = "S1", trackNumber = 1, discNumber = 1, durationMs = 1, albumId = 1, artistId = 1, contentUri = "c1", dateAdded = 0, dateModified = 0),
                SongEntity(id = 20, title = "S2", trackNumber = 1, discNumber = 1, durationMs = 1, albumId = 2, artistId = 2, contentUri = "c2", dateAdded = 0, dateModified = 0),
            ),
        )

        val queue = controller.apply(ShufflePresets.SHUFFLE_ARTISTS)

        assertEquals(Grouping.ARTIST, queue.settings.grouping)
        assertEquals(2, queue.songs.size)
    }

    @Test
    fun `naturalOrderFor Shuffle Albums returns albums alphabetically with tracks in track order`() = runTest {
        seedLibrary(albumCount = 3, tracksPerAlbum = 2)

        val natural = controller.naturalOrderFor(ShufflePresets.SHUFFLE_ALBUMS)

        val expectedIds = (1L..3L).flatMap { albumId -> listOf(albumId * 100 + 1, albumId * 100 + 2) }
        assertEquals(expectedIds, natural.map { it.id })
    }

    @Test
    fun `naturalOrderFor Shuffle All returns songs alphabetically by title`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 1, name = "Artist")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 1, name = "Album", artistId = 1, year = null, trackCount = 3)))
        songDao.upsertAll(
            listOf(
                SongEntity(id = 1, title = "Charlie", trackNumber = 1, discNumber = 1, durationMs = 1, albumId = 1, artistId = 1, contentUri = "c1", dateAdded = 0, dateModified = 0),
                SongEntity(id = 2, title = "Alpha", trackNumber = 2, discNumber = 1, durationMs = 1, albumId = 1, artistId = 1, contentUri = "c2", dateAdded = 0, dateModified = 0),
                SongEntity(id = 3, title = "Bravo", trackNumber = 3, discNumber = 1, durationMs = 1, albumId = 1, artistId = 1, contentUri = "c3", dateAdded = 0, dateModified = 0),
            ),
        )

        val natural = controller.naturalOrderFor(ShufflePresets.SHUFFLE_ALL)

        assertEquals(listOf(2L, 3L, 1L), natural.map { it.id })
    }

    // applyCategoryShuffle is what "Shuffle Albums"/"Shuffle Artists"/etc. actually calls -- unlike
    // apply(), which takes a fully-specified ShuffleSettings directly, this reads the current
    // CategoryShuffleMode setting to decide which axis (or both) to randomize, so the tests below
    // set that preference first via the repository the controller shares with it.

    @Test
    fun `applyCategoryShuffle defaults to CATEGORIES mode when no preference has ever been set`() = runTest {
        seedLibrary()
        val queue = controller.applyCategoryShuffle(Grouping.ALBUM)
        assertEquals(ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true, shuffleSongs = false), queue.settings)
    }

    @Test
    fun `applyCategoryShuffle in SONGS mode shuffles songs within each category but keeps category order natural`() =
        runTest {
            seedLibrary(albumCount = 3, tracksPerAlbum = 2)
            repository.setCategoryShuffleMode(CategoryShuffleMode.SONGS)

            val queue = controller.applyCategoryShuffle(Grouping.ALBUM)

            assertEquals(ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = false, shuffleSongs = true), queue.settings)
            // Category order itself must still be natural (alphabetical): album N's two songs
            // both land somewhere in the block reachable by their album's alphabetical rank, i.e.
            // every song from "Album 1" appears before every song from "Album 2", etc., even
            // though which of the two songs comes first WITHIN that block isn't guaranteed.
            val albumIdOf = { songId: Long -> songId / 100 }
            val albumSequence = queue.songs.map { albumIdOf(it.id) }
            assertEquals(albumSequence.sorted(), albumSequence)
        }

    @Test
    fun `applyCategoryShuffle in BOTH mode shuffles both category order and songs within each`() = runTest {
        seedLibrary(albumCount = 4, tracksPerAlbum = 2)
        repository.setCategoryShuffleMode(CategoryShuffleMode.BOTH)

        val queue = controller.applyCategoryShuffle(Grouping.ALBUM)

        assertEquals(ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true, shuffleSongs = true), queue.settings)
    }

    @Test
    fun `applyCategoryShuffle in CATEGORIES mode still plays each category's songs in track order`() = runTest {
        seedLibrary()
        repository.setCategoryShuffleMode(CategoryShuffleMode.CATEGORIES)

        val queue = controller.applyCategoryShuffle(Grouping.ALBUM)

        val positions = queue.songs.mapIndexed { index, song -> song.id to index }.toMap()
        for (albumId in 1L..3L) {
            val first = albumId * 100 + 1
            val second = albumId * 100 + 2
            assertEquals(positions.getValue(first) + 1, positions.getValue(second))
        }
    }
}
