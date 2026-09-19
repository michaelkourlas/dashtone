package net.kourlas.dashtone.service.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.UNKNOWN_GENRE
import net.kourlas.dashtone.service.testing.FakeAlbumArtInvalidator
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoOpScannerForChildCountTest : LibraryScanner {
    override suspend fun scanLibrary(force: Boolean) = Unit
    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

/**
 * [LibraryBrowseTree.childCount] answers from COUNT/DISTINCT queries while [LibraryBrowseTree.children]
 * builds the real list, so the two derive the same number by different routes and could drift apart
 * silently -- a count that overshoots leaves the host rendering blank rows past the end of a list,
 * one that undershoots truncates it, and neither shows up as a crash.
 *
 * Every root is checked against a library seeded to hit the cases the counts have to fold by hand:
 * missing genre/album artist/composer tags, a literal "Unknown Genre" string sharing a bucket with
 * those nulls, two albums in one decade, an album with no year, nested folders, and an album with
 * no songs (which contributes no decade, matching the query's INNER JOIN).
 *
 * The root list mirrors DashtoneMediaLibraryService.notifyLibraryChildrenChanged, which is the only
 * caller that asks for counts.
 */
@RunWith(RobolectricTestRunner::class)
class ChildCountTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var browseTree: LibraryBrowseTree

    private val roots = listOf(
        MediaIds.ALBUMS_ROOT,
        MediaIds.ARTISTS_ROOT,
        MediaIds.GENRES_ROOT,
        MediaIds.ALBUM_ARTISTS_ROOT,
        MediaIds.COMPOSERS_ROOT,
        MediaIds.DECADES_ROOT,
        MediaIds.FOLDERS_ROOT,
        MediaIds.SONGS_ROOT,
        MediaIds.PLAYLISTS_ROOT,
    )

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        playlistDao = FakePlaylistDao(songDao)
        val preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        val repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, NoOpScannerForChildCountTest(), preferences, FakeAlbumArtInvalidator(),
        )
        browseTree = LibraryBrowseTree(repository, MediaItemMapper(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun `every root's count matches the list it would render`() = runTest {
        seedAwkwardLibrary()

        for (root in roots) {
            assertEquals(
                "childCount disagrees with children().size for $root",
                browseTree.children(root)?.size,
                browseTree.childCount(root),
            )
        }
    }

    // The sentinel rows are the whole reason these two can disagree, so an empty library -- where
    // the count is nothing but sentinels -- is the case most likely to expose a miscount.
    @Test
    fun `every root's count matches the list it would render for an empty library`() = runTest {
        for (root in roots) {
            assertEquals(
                "childCount disagrees with children().size for $root",
                browseTree.children(root)?.size,
                browseTree.childCount(root),
            )
        }
    }

    @Test
    fun `a category root counts its two sentinel rows on top of its entries`() = runTest {
        seedAwkwardLibrary()

        assertEquals(2 + albumDao.current.size, browseTree.childCount(MediaIds.ALBUMS_ROOT))
        assertEquals(2 + songDao.current.size, browseTree.childCount(MediaIds.SONGS_ROOT))
    }

    // Playlists are curated file order and decades have no matching Grouping, so neither leads with
    // a "Play All"/"Shuffle" pair the way the other roots do.
    @Test
    fun `playlists and decades count their entries alone`() = runTest {
        seedAwkwardLibrary()

        assertEquals(playlistDao.current.size, browseTree.childCount(MediaIds.PLAYLISTS_ROOT))
        assertEquals(listOf("1980s", "Unknown Decade").size, browseTree.childCount(MediaIds.DECADES_ROOT))
    }

    @Test
    fun `an unknown parent has no count`() = runTest {
        assertEquals(null, browseTree.childCount("nonsense/42"))
    }

    private suspend fun seedAwkwardLibrary() {
        artistDao.upsertAll(
            listOf(ArtistEntity(id = 10, name = "Artist X"), ArtistEntity(id = 20, name = "Artist Y")),
        )
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = 1985),
                // Same decade as Album 1, so the decade list collapses these two into one entry.
                AlbumEntity(id = 200, name = "Album 2", artistId = 20, year = 1989),
                AlbumEntity(id = 300, name = "Album 3", artistId = 10, year = null),
                // No songs, so it contributes no decade -- the query joins through songs.
                AlbumEntity(id = 400, name = "Songless", artistId = 20, year = 2001),
            ),
        )
        songDao.upsertAll(
            listOf(
                song(id = 1, albumId = 100, artistId = 10, genre = "Rock", folder = "Music/Rock/Album 1"),
                // A literal "Unknown Genre" shares the bucket the null below folds into.
                song(id = 2, albumId = 100, artistId = 10, genre = UNKNOWN_GENRE, folder = "Music/Rock/Album 1"),
                song(id = 3, albumId = 200, artistId = 20, genre = null, folder = "Music/Jazz"),
                song(id = 4, albumId = 300, artistId = 10, genre = "Rock", folder = null),
            ),
        )
        playlistDao.upsertAll(
            listOf(
                PlaylistEntity(id = 1, name = "Road Trip", dateModified = 0),
                PlaylistEntity(id = 2, name = "Quiet", dateModified = 0),
            ),
        )
    }

    private fun song(id: Long, albumId: Long, artistId: Long, genre: String?, folder: String?) = SongEntity(
        id = id,
        title = "Song $id",
        trackNumber = id.toInt(),
        discNumber = 1,
        durationMs = 200_000,
        albumId = albumId,
        artistId = artistId,
        contentUri = "content://media/external/audio/media/$id",
        dateAdded = 0,
        dateModified = 0,
        genreName = genre,
        // Left unset on purpose: the album artist and composer lists then have only the "unknown"
        // bucket to fold every song into, which is the collapsing case the counts must match.
        albumArtistName = null,
        composerName = null,
        folderPath = folder,
    )
}
