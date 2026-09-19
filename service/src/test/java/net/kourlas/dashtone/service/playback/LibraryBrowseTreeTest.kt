package net.kourlas.dashtone.service.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntryEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoOpLibraryScanner : LibraryScanner {
    override suspend fun scanLibrary(force: Boolean) = Unit
    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

/** Runs under Robolectric because [MediaItemMapper] parses real android.net.Uri values. */
@RunWith(RobolectricTestRunner::class)
class LibraryBrowseTreeTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var repository: LibraryRepository
    private lateinit var browseTree: LibraryBrowseTree

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        playlistDao = FakePlaylistDao(songDao)
        val preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, NoOpLibraryScanner(), preferences, FakeAlbumArtInvalidator(),
        )
        browseTree = LibraryBrowseTree(repository, MediaItemMapper(ApplicationProvider.getApplicationContext()))
    }

    private suspend fun seedLibrary() {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 2, title = "Song B", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0,
                ),
            ),
        )
    }

    @Test
    fun `root is browsable and triggers a first-run scan`() = runTest {
        val root = browseTree.root()
        assertEquals(MediaIds.ROOT, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
    }

    @Test
    fun `root shows only the first three categories as tabs, plus a More tab for the rest`() = runTest {
        // Default order is Albums, Artists, Songs, Playlists, Genres, Album Artists, all enabled --
        // six categories, but the car UI's tab strip only fits four (see LibraryBrowseTree's doc
        // comment), so only the first three get their own tab and the rest land behind "More".
        val children = browseTree.children(MediaIds.ROOT)
        assertEquals(
            listOf(MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT, MediaIds.SONGS_ROOT, MediaIds.MORE_ROOT),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `More tab lists the categories that didn't fit as their own root tab, in order`() = runTest {
        val children = browseTree.children(MediaIds.MORE_ROOT)
        assertEquals(
            listOf(
                MediaIds.PLAYLISTS_ROOT,
                MediaIds.GENRES_ROOT,
                MediaIds.ALBUM_ARTISTS_ROOT,
                MediaIds.COMPOSERS_ROOT,
                MediaIds.DECADES_ROOT,
                MediaIds.FOLDERS_ROOT,
            ),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `disabling categories removes them from root and drops the More tab once three or fewer remain`() = runTest {
        repository.setDisabledCategories(
            setOf(
                BrowseCategory.PLAYLISTS, BrowseCategory.GENRES, BrowseCategory.ALBUM_ARTISTS,
                BrowseCategory.COMPOSERS, BrowseCategory.DECADES, BrowseCategory.FOLDERS,
            ),
        )

        val children = browseTree.children(MediaIds.ROOT)

        // Three enabled categories now fit directly (MAX_ROOT_TABS - 1) -- no overflow, so no
        // More tab is needed.
        assertEquals(
            listOf(MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT, MediaIds.SONGS_ROOT),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `reordering categories changes which three get their own tab`() = runTest {
        repository.setCategoryOrder(
            listOf(
                BrowseCategory.PLAYLISTS,
                BrowseCategory.GENRES,
                BrowseCategory.ALBUM_ARTISTS,
                BrowseCategory.ALBUMS,
                BrowseCategory.ARTISTS,
                BrowseCategory.SONGS,
            ),
        )
        // Keep this test's concern limited to the six categories it's actually reordering --
        // otherwise COMPOSERS/DECADES/etc. (unmentioned above, so still enabled and appended at
        // the end of the order -- see AppPreferences.categoryOrder's doc comment) would also leak
        // into the More assertion below.
        repository.setDisabledCategories(
            setOf(BrowseCategory.COMPOSERS, BrowseCategory.DECADES, BrowseCategory.FOLDERS),
        )

        val children = browseTree.children(MediaIds.ROOT)

        assertEquals(
            listOf(MediaIds.PLAYLISTS_ROOT, MediaIds.GENRES_ROOT, MediaIds.ALBUM_ARTISTS_ROOT, MediaIds.MORE_ROOT),
            children?.map { it.mediaId },
        )
        assertEquals(
            listOf(MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT, MediaIds.SONGS_ROOT),
            browseTree.children(MediaIds.MORE_ROOT)?.map { it.mediaId },
        )
    }

    @Test
    fun `root and More tab stay mutually consistent even if settings change mid-connection`() = runTest {
        // Simulates the real failure mode this guards against: a car host fetches root() once and
        // caches the resulting tab strip for its whole connection, but re-fetches More live. If
        // enabledCategoriesInOrder() re-read settings on every call, a mid-connection settings
        // change could make the (host-cached, stale) tab strip and the (live) More list describe
        // two different states -- e.g. a category could appear in both, or vanish from both, at
        // once. Snapshotting inside root() prevents that: everything between one root() call and
        // the next must describe the exact same settings state.
        browseTree.root()

        repository.setDisabledCategories(
            setOf(
                BrowseCategory.PLAYLISTS, BrowseCategory.GENRES, BrowseCategory.ALBUM_ARTISTS,
                BrowseCategory.COMPOSERS, BrowseCategory.DECADES, BrowseCategory.FOLDERS,
            ),
        )

        // The tab strip still reflects the pre-change snapshot taken at root()...
        assertEquals(
            listOf(MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT, MediaIds.SONGS_ROOT, MediaIds.MORE_ROOT),
            browseTree.children(MediaIds.ROOT)?.map { it.mediaId },
        )
        // ...and so does More: Genres/Album Artists (now disabled) still appear here, matching the
        // tab strip that still promises a More tab containing them, rather than silently
        // disappearing out from under it.
        assertEquals(
            listOf(
                MediaIds.PLAYLISTS_ROOT,
                MediaIds.GENRES_ROOT,
                MediaIds.ALBUM_ARTISTS_ROOT,
                MediaIds.COMPOSERS_ROOT,
                MediaIds.DECADES_ROOT,
                MediaIds.FOLDERS_ROOT,
            ),
            browseTree.children(MediaIds.MORE_ROOT)?.map { it.mediaId },
        )

        // A fresh root() call -- what a genuine host reconnect looks like -- picks up the new
        // settings, and tabs and More move together onto the new state.
        browseTree.root()
        assertEquals(
            listOf(MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT, MediaIds.SONGS_ROOT),
            browseTree.children(MediaIds.ROOT)?.map { it.mediaId },
        )
    }

    @Test
    fun `albums root leads with Play All Albums then Shuffle Albums, then lists albums with artist name attached`() =
        runTest {
            seedLibrary()
            val children = browseTree.children(MediaIds.ALBUMS_ROOT)
            assertEquals(3, children?.size)
            assertEquals(MediaIds.PLAY_ALL_ALBUMS, children?.get(0)?.mediaId)
            assertEquals(MediaIds.SHUFFLE_ALBUMS, children?.get(1)?.mediaId)
            val album = children!![2]
            assertEquals(MediaIds.album(100), album.mediaId)
            assertEquals("Artist X", album.mediaMetadata.artist.toString())
        }

    @Test
    fun `artists root leads with Play All Artists then Shuffle Artists`() = runTest {
        seedLibrary()
        val children = browseTree.children(MediaIds.ARTISTS_ROOT)
        assertEquals(MediaIds.PLAY_ALL_ARTISTS, children?.get(0)?.mediaId)
        assertEquals(MediaIds.SHUFFLE_ARTISTS, children?.get(1)?.mediaId)
        assertEquals(MediaIds.artist(10), children?.get(2)?.mediaId)
    }

    @Test
    fun `songs root leads with Play All Songs then Shuffle All Songs`() = runTest {
        seedLibrary()
        val children = browseTree.children(MediaIds.SONGS_ROOT)
        assertEquals(MediaIds.PLAY_ALL_SONGS, children?.get(0)?.mediaId)
        assertEquals(MediaIds.SHUFFLE_ALL, children?.get(1)?.mediaId)
        assertEquals(listOf(MediaIds.song(1), MediaIds.song(2)), children?.drop(2)?.map { it.mediaId })
    }

    @Test
    fun `genres root leads with Play All Genres then Shuffle Genres, then lists distinct genres, falling back to Unknown Genre`() =
        runTest {
            artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
            albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
            songDao.upsertAll(
                listOf(
                    SongEntity(
                        id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                        dateAdded = 0, dateModified = 0, genreName = "Rock",
                    ),
                    SongEntity(
                        id = 2, title = "Song B", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                        dateAdded = 0, dateModified = 0, genreName = null,
                    ),
                ),
            )

            val children = browseTree.children(MediaIds.GENRES_ROOT)

            assertEquals(MediaIds.PLAY_ALL_GENRES, children?.get(0)?.mediaId)
            assertEquals(MediaIds.SHUFFLE_GENRES, children?.get(1)?.mediaId)
            assertEquals(
                listOf("Rock", "Unknown Genre"),
                children?.drop(2)?.map { it.mediaMetadata.title.toString() },
            )
        }

    @Test
    fun `browsing into a genre leads with All Songs then lists only albums containing that genre`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "Zebra Album", artistId = 10, year = null),
                AlbumEntity(id = 200, name = "Apple Album", artistId = 10, year = null),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, genreName = "Rock",
                ),
                SongEntity(
                    id = 2, title = "Song B", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, genreName = "Jazz",
                ),
                SongEntity(
                    id = 3, title = "Song C", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0, genreName = "Rock",
                ),
            ),
        )

        val children = browseTree.children(MediaIds.genre("Rock"))
        val parentId = MediaIds.genre("Rock")

        assertEquals(
            listOf(
                MediaIds.playAlbumsFor(parentId),
                MediaIds.shuffleAlbumsFor(parentId),
                MediaIds.allGenreSongs("Rock"),
                MediaIds.album(200),
                MediaIds.album(100),
            ),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `All Genre Songs is browsable, not playable, and opens onto every song with that genre, sorted by title`() =
        runTest {
            artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
            albumDao.upsertAll(
                listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)),
            )
            songDao.upsertAll(
                listOf(
                    SongEntity(
                        id = 1, title = "Zebra", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                        dateAdded = 0, dateModified = 0, genreName = "Rock",
                    ),
                    SongEntity(
                        id = 2, title = "Apple", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                        dateAdded = 0, dateModified = 0, genreName = "Rock",
                    ),
                    SongEntity(
                        id = 3, title = "Other", trackNumber = 3, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/3",
                        dateAdded = 0, dateModified = 0, genreName = "Jazz",
                    ),
                ),
            )

            val parentId = MediaIds.allGenreSongs("Rock")
            val item = browseTree.item(parentId)
            assertTrue(item?.mediaMetadata?.isBrowsable == true)
            assertTrue(item?.mediaMetadata?.isPlayable == false)

            val expectedSongs = listOf(MediaIds.song(2), MediaIds.song(1))
            assertEquals(
                listOf(MediaIds.playSongsFor(parentId), MediaIds.shuffleSongsFor(parentId)) + expectedSongs,
                browseTree.children(parentId)?.map { it.mediaId },
            )
            // songsForGenre is the other caller of this same flat list -- voice/search resolution
            // (see DashtoneLibrarySessionCallback.resolveGenreByName/resolveTextSearch), which needs
            // a playable queue directly rather than a browsable node to open.
            assertEquals(expectedSongs, browseTree.songsForGenre("Rock").map { it.mediaId })
        }

    @Test
    fun `album artists root leads with Play All Album Artists then Shuffle Album Artists, then lists distinct album artists`() =
        runTest {
            artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
            albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
            songDao.upsertAll(
                listOf(
                    SongEntity(
                        id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                        dateAdded = 0, dateModified = 0, albumArtistName = "Various Artists",
                    ),
                ),
            )

            val children = browseTree.children(MediaIds.ALBUM_ARTISTS_ROOT)

            assertEquals(MediaIds.PLAY_ALL_ALBUM_ARTISTS, children?.get(0)?.mediaId)
            assertEquals(MediaIds.SHUFFLE_ALBUM_ARTISTS, children?.get(1)?.mediaId)
            assertEquals(MediaIds.albumArtist("Various Artists"), children?.get(2)?.mediaId)
        }

    @Test
    fun `genre and album artist names containing slashes round-trip through the media id`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Thunderstruck", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, albumArtistName = "AC/DC",
                ),
            ),
        )

        val parentId = MediaIds.albumArtist("AC/DC")
        val children = browseTree.children(parentId)

        assertEquals(
            listOf(
                MediaIds.playAlbumsFor(parentId),
                MediaIds.shuffleAlbumsFor(parentId),
                MediaIds.allAlbumArtistSongs("AC/DC"),
                MediaIds.album(100),
            ),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `composers root leads with Play All Composers then Shuffle Composers, then lists distinct composers`() =
        runTest {
            artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
            albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
            songDao.upsertAll(
                listOf(
                    SongEntity(
                        id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                        albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                        dateAdded = 0, dateModified = 0, composerName = "John Williams",
                    ),
                ),
            )

            val children = browseTree.children(MediaIds.COMPOSERS_ROOT)

            assertEquals(MediaIds.PLAY_ALL_COMPOSERS, children?.get(0)?.mediaId)
            assertEquals(MediaIds.SHUFFLE_COMPOSERS, children?.get(1)?.mediaId)
            assertEquals(MediaIds.composer("John Williams"), children?.get(2)?.mediaId)
        }

    @Test
    fun `browsing into a composer leads with All Songs then lists that composer's albums`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "Zebra Score", artistId = 10, year = null),
                AlbumEntity(id = 200, name = "Apple Score", artistId = 10, year = null),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, composerName = "Hans Zimmer",
                ),
                SongEntity(
                    id = 2, title = "Song B", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, composerName = "Hans Zimmer",
                ),
            ),
        )

        val parentId = MediaIds.composer("Hans Zimmer")
        val children = browseTree.children(parentId)

        assertEquals(
            listOf(
                MediaIds.playAlbumsFor(parentId),
                MediaIds.shuffleAlbumsFor(parentId),
                MediaIds.allComposerSongs("Hans Zimmer"),
                MediaIds.album(200),
                MediaIds.album(100),
            ),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `All Composer Songs resolves to every song with that composer, sorted by title, across albums`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Zebra", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, composerName = "Hans Zimmer",
                ),
                SongEntity(
                    id = 2, title = "Apple", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, composerName = "Hans Zimmer",
                ),
            ),
        )

        val songs = browseTree.songsForComposer("Hans Zimmer")

        assertEquals(listOf(MediaIds.song(2), MediaIds.song(1)), songs.map { it.mediaId })
    }

    @Test
    fun `decades root lists decades most-recent-first, with Unknown Decade pinned last`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "80s Album", artistId = 10, year = 1985),
                AlbumEntity(id = 200, name = "2020s Album", artistId = 10, year = 2021),
                AlbumEntity(id = 300, name = "No Year Album", artistId = 10, year = null),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Song A", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 2, title = "Song B", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 3, title = "Song C", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 300, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0,
                ),
            ),
        )

        val children = browseTree.children(MediaIds.DECADES_ROOT)

        // No "Shuffle Decades" entry: decades group albums, not a per-song field, so there's no
        // matching Grouping for the shuffle engine to hook into -- see LibraryBrowseTree's comment.
        assertEquals(
            listOf("2020s", "1980s", "Unknown Decade"),
            children?.map { it.mediaMetadata.title.toString() },
        )
    }

    @Test
    fun `browsing into a decade leads with All Songs then lists only albums released in that decade`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "Zebra 80s Album", artistId = 10, year = 1985),
                AlbumEntity(id = 200, name = "Apple 80s Album", artistId = 10, year = 1989),
                AlbumEntity(id = 300, name = "2020s Album", artistId = 10, year = 2021),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Zebra", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 2, title = "Apple", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 3, title = "Other", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 300, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0,
                ),
            ),
        )

        val parentId = MediaIds.decade("1980s")
        val children = browseTree.children(parentId)

        assertEquals(
            listOf(
                MediaIds.playAlbumsFor(parentId),
                MediaIds.shuffleAlbumsFor(parentId),
                MediaIds.allDecadeSongs("1980s"),
                MediaIds.album(200),
                MediaIds.album(100),
            ),
            children?.map { it.mediaId },
        )
    }

    @Test
    fun `All Decade Songs resolves to every song from albums released in that decade, sorted by title`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "80s Album", artistId = 10, year = 1985),
                AlbumEntity(id = 200, name = "Another 80s Album", artistId = 10, year = 1989),
                AlbumEntity(id = 300, name = "2020s Album", artistId = 10, year = 2021),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Zebra", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 2, title = "Apple", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 3, title = "Other", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 300, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0,
                ),
            ),
        )

        val songs = browseTree.songsForDecade("1980s")

        assertEquals(listOf(MediaIds.song(2), MediaIds.song(1)), songs.map { it.mediaId })
    }

    @Test
    fun `folders root lists only top-level folders -- songs one or more levels deep are not shown directly`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            listOf(
                // Lives directly inside "Music" (depth 1) -- belongs under folder("Music") when
                // browsing into it, not at the true FOLDERS_ROOT alongside "Music" itself. A real
                // scan can never actually produce folderPath = "" for a song (fullFolderPathOf
                // maps a truly-empty relative path to null, same as the API < 29/no-RELATIVE_PATH
                // case below), so there's no reachable scenario where a song sits directly at
                // FOLDERS_ROOT next to a folder entry.
                SongEntity(
                    id = 1, title = "Shallow Song", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, folderPath = "Music",
                ),
                SongEntity(
                    id = 2, title = "Nested Song", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, folderPath = "Music/ABBA/Gold",
                ),
                SongEntity(
                    id = 3, title = "No Folder Song", trackNumber = 3, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0, folderPath = null,
                ),
            ),
        )

        val children = browseTree.children(MediaIds.FOLDERS_ROOT)

        // "Music" appears exactly once as a single folder entry, deduplicated across both the
        // shallow and deeply-nested song that each contribute it as their first path segment;
        // neither song is also listed directly at this level, and the folder-less (null) song
        // doesn't appear anywhere in this category.
        assertEquals(listOf(MediaIds.folder("Music")), children?.map { it.mediaId })
    }

    @Test
    fun `browsing into a folder shows its subfolders then its direct songs`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Direct Song", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, folderPath = "Music/ABBA",
                ),
                SongEntity(
                    id = 2, title = "Nested Song", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, folderPath = "Music/ABBA/Gold",
                ),
            ),
        )

        val children = browseTree.children(MediaIds.folder("Music/ABBA"))

        assertEquals(listOf(MediaIds.folder("Music/ABBA/Gold"), MediaIds.song(1)), children?.map { it.mediaId })

        val grandchildren = browseTree.children(MediaIds.folder("Music/ABBA/Gold"))
        assertEquals(listOf(MediaIds.song(2)), grandchildren?.map { it.mediaId })
    }

    @Test
    fun `browsing into an album leads with Play Songs and Shuffle Songs then lists its songs in track order`() =
        runTest {
            seedLibrary()
            val parentId = MediaIds.album(100)
            val children = browseTree.children(parentId)
            assertEquals(
                listOf(MediaIds.playSongsFor(parentId), MediaIds.shuffleSongsFor(parentId), MediaIds.song(1), MediaIds.song(2)),
                children?.map { it.mediaId },
            )
            assertTrue(children!!.all { it.mediaMetadata.isPlayable == true })
        }

    @Test
    fun `browsing into an artist leads with Play Albums and Shuffle Albums, then All Songs, then lists their albums`() =
        runTest {
            seedLibrary()
            val parentId = MediaIds.artist(10)
            val children = browseTree.children(parentId)
            assertEquals(
                listOf(
                    MediaIds.playAlbumsFor(parentId),
                    MediaIds.shuffleAlbumsFor(parentId),
                    MediaIds.allArtistSongs(10),
                    MediaIds.album(100),
                ),
                children?.map { it.mediaId },
            )
        }

    @Test
    fun `All Artist Songs is browsable, not playable, titled with the artist's name, and opens onto their songs`() =
        runTest {
            seedLibrary()
            val parentId = MediaIds.allArtistSongs(10)

            val item = browseTree.item(parentId)
            assertEquals("All Artist X Songs", item?.mediaMetadata?.title.toString())
            assertTrue(item?.mediaMetadata?.isBrowsable == true)
            assertTrue(item?.mediaMetadata?.isPlayable == false)

            val expected = listOf(MediaIds.song(1), MediaIds.song(2))
            assertEquals(
                listOf(MediaIds.playSongsFor(parentId), MediaIds.shuffleSongsFor(parentId)) + expected,
                browseTree.children(parentId)?.map { it.mediaId },
            )
            // songsForArtist is the other caller of this same flat list -- voice/search resolution.
            assertEquals(expected, browseTree.songsForArtist(10).map { it.mediaId })
        }

    @Test
    fun `playlists root lists playlists with no shuffle entry`() = runTest {
        seedLibrary()
        playlistDao.upsertAll(listOf(PlaylistEntity(id = 5, name = "Road Trip", dateModified = 0)))

        val children = browseTree.children(MediaIds.PLAYLISTS_ROOT)

        assertEquals(listOf(MediaIds.playlist(5)), children?.map { it.mediaId })
    }

    @Test
    fun `browsing into a playlist leads with Play Songs and Shuffle Songs then lists its songs in file order, not track order`() =
        runTest {
            seedLibrary()
            playlistDao.upsertAll(listOf(PlaylistEntity(id = 5, name = "Road Trip", dateModified = 0)))
            playlistDao.insertEntries(
                listOf(
                    PlaylistEntryEntity(playlistId = 5, position = 0, songId = 2),
                    PlaylistEntryEntity(playlistId = 5, position = 1, songId = 1),
                ),
            )
            val parentId = MediaIds.playlist(5)

            val children = browseTree.children(parentId)

            assertEquals(
                listOf(MediaIds.playSongsFor(parentId), MediaIds.shuffleSongsFor(parentId), MediaIds.song(2), MediaIds.song(1)),
                children?.map { it.mediaId },
            )
        }

    @Test
    fun `albumsQueueFor natural order groups by album, matching what hand-browsing that node would show`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "Zebra Album", artistId = 10, year = null),
                AlbumEntity(id = 200, name = "Apple Album", artistId = 10, year = null),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Song A", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 2, title = "Song B", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0,
                ),
                SongEntity(
                    id = 3, title = "Song C", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 10, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0,
                ),
            ),
        )

        val queue = browseTree.albumsQueueFor(MediaIds.artist(10), shuffled = false)

        // Apple Album (200) sorts before Zebra Album (100); within Zebra Album, track order (song
        // 2 is track 1, song 1 is track 2).
        assertEquals(listOf(MediaIds.song(3), MediaIds.song(2), MediaIds.song(1)), queue?.map { it.mediaId })
    }

    @Test
    fun `albumsQueueFor shuffled keeps each album's track order but honors CategoryShuffleMode for which axis randomizes`() =
        runTest {
            artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
            albumDao.upsertAll(
                listOf(
                    AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null),
                    AlbumEntity(id = 200, name = "Album 2", artistId = 10, year = null),
                ),
            )
            songDao.upsertAll(
                listOf(
                    SongEntity(id = 1, title = "S1", trackNumber = 1, discNumber = 1, durationMs = 1, albumId = 100, artistId = 10, contentUri = "c1", dateAdded = 0, dateModified = 0),
                    SongEntity(id = 2, title = "S2", trackNumber = 2, discNumber = 1, durationMs = 1, albumId = 100, artistId = 10, contentUri = "c2", dateAdded = 0, dateModified = 0),
                    SongEntity(id = 3, title = "S3", trackNumber = 1, discNumber = 1, durationMs = 1, albumId = 200, artistId = 10, contentUri = "c3", dateAdded = 0, dateModified = 0),
                    SongEntity(id = 4, title = "S4", trackNumber = 2, discNumber = 1, durationMs = 1, albumId = 200, artistId = 10, contentUri = "c4", dateAdded = 0, dateModified = 0),
                ),
            )
            // CATEGORIES is the default CategoryShuffleMode (see ShuffleControllerTest) -- shuffles
            // which album comes first but keeps each album's own two tracks adjacent, in order.
            val queue = browseTree.albumsQueueFor(MediaIds.artist(10), shuffled = true)

            val positions = queue?.mapIndexed { index, item -> item.mediaId to index }?.toMap().orEmpty()
            assertEquals(positions.getValue(MediaIds.song(1)) + 1, positions.getValue(MediaIds.song(2)))
            assertEquals(positions.getValue(MediaIds.song(3)) + 1, positions.getValue(MediaIds.song(4)))
        }

    @Test
    fun `albumsQueueFor returns null for a parentId that isn't an album-list container`() = runTest {
        seedLibrary()
        assertNull(browseTree.albumsQueueFor(MediaIds.album(100), shuffled = false))
        assertNull(browseTree.albumsQueueFor("not-a-real-id", shuffled = false))
    }

    @Test
    fun `songsQueueFor natural order matches what browsing that album already shows, track order`() = runTest {
        seedLibrary()
        val queue = browseTree.songsQueueFor(MediaIds.album(100), shuffled = false)
        assertEquals(listOf(MediaIds.song(1), MediaIds.song(2)), queue?.map { it.mediaId })
    }

    @Test
    fun `songsQueueFor shuffled is a flat shuffle with no album grouping to preserve`() = runTest {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Artist X")))
        albumDao.upsertAll(listOf(AlbumEntity(id = 100, name = "Album 1", artistId = 10, year = null)))
        songDao.upsertAll(
            (1..8).map { id ->
                SongEntity(
                    id = id.toLong(), title = "Song $id", trackNumber = id, discNumber = 1, durationMs = 1,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/$id",
                    dateAdded = 0, dateModified = 0,
                )
            },
        )

        val queue = browseTree.songsQueueFor(MediaIds.album(100), shuffled = true)

        assertEquals((1..8).map { MediaIds.song(it.toLong()) }.toSet(), queue?.map { it.mediaId }?.toSet())
    }

    @Test
    fun `songsQueueFor returns null for a parentId that isn't a song-list container`() = runTest {
        seedLibrary()
        assertNull(browseTree.songsQueueFor(MediaIds.artist(10), shuffled = false))
        assertNull(browseTree.songsQueueFor("not-a-real-id", shuffled = false))
    }

    @Test
    fun `browsing an unknown container returns null`() = runTest {
        assertNull(browseTree.children(MediaIds.album(999)))
        assertNull(browseTree.children(MediaIds.artist(999)))
        assertNull(browseTree.children(MediaIds.playlist(999)))
        assertNull(browseTree.children("not-a-real-id"))
    }

    @Test
    fun `item resolves a song by its media id with album and artist metadata`() = runTest {
        seedLibrary()
        val item = browseTree.item(MediaIds.song(1))
        assertEquals("Song A", item?.mediaMetadata?.title.toString())
        assertEquals("Album 1", item?.mediaMetadata?.albumTitle.toString())
        assertEquals("Artist X", item?.mediaMetadata?.artist.toString())
    }

    @Test
    fun `item returns null for a missing song or an unrecognized media id`() = runTest {
        assertNull(browseTree.item(MediaIds.song(999)))
        assertNull(browseTree.item(MediaIds.album(999)))
        assertNull(browseTree.item(MediaIds.artist(999)))
        assertNull(browseTree.item(MediaIds.playlist(999)))
        assertNull(browseTree.item("not-a-real-id"))
    }

    // Every category root and container id must resolve to a real, browsable item: the default
    // MediaLibrarySession.Callback.onSubscribe() gates a subscription on this exact lookup
    // succeeding (see LibraryBrowseTree.item's doc comment), so any id that returns null here can
    // never be subscribed to, which makes notifyChildrenChanged for it a silent no-op.
    @Test
    fun `item resolves every category root to a browsable item`() = runTest {
        val rootIds = listOf(
            MediaIds.ROOT, MediaIds.MORE_ROOT, MediaIds.ALBUMS_ROOT, MediaIds.ARTISTS_ROOT,
            MediaIds.GENRES_ROOT, MediaIds.ALBUM_ARTISTS_ROOT, MediaIds.COMPOSERS_ROOT,
            MediaIds.DECADES_ROOT, MediaIds.FOLDERS_ROOT, MediaIds.SONGS_ROOT, MediaIds.PLAYLISTS_ROOT,
        )
        for (id in rootIds) {
            assertEquals(id, browseTree.item(id)?.mediaId)
            assertEquals(true, browseTree.item(id)?.mediaMetadata?.isBrowsable)
        }
    }

    @Test
    fun `item resolves a container by its media id`() = runTest {
        seedLibrary()
        assertEquals(MediaIds.album(100), browseTree.item(MediaIds.album(100))?.mediaId)
        assertEquals(MediaIds.artist(10), browseTree.item(MediaIds.artist(10))?.mediaId)
        assertEquals(MediaIds.genre("Rock"), browseTree.item(MediaIds.genre("Rock"))?.mediaId)
        assertEquals(MediaIds.albumArtist("X"), browseTree.item(MediaIds.albumArtist("X"))?.mediaId)
        assertEquals(MediaIds.composer("Y"), browseTree.item(MediaIds.composer("Y"))?.mediaId)
        assertEquals(MediaIds.decade("1980s"), browseTree.item(MediaIds.decade("1980s"))?.mediaId)
        assertEquals(MediaIds.folder("Music"), browseTree.item(MediaIds.folder("Music"))?.mediaId)
    }

    @Test
    fun `search matches songs, albums, artists, and playlists case-insensitively`() = runTest {
        seedLibrary()
        playlistDao.upsertAll(listOf(PlaylistEntity(id = 5, name = "Road trip", dateModified = 0)))

        assertEquals(listOf(MediaIds.song(1)), browseTree.search("song a").map { it.mediaId })
        assertEquals(listOf(MediaIds.album(100)), browseTree.search("album 1").map { it.mediaId })
        assertEquals(listOf(MediaIds.artist(10)), browseTree.search("artist x").map { it.mediaId })
        assertEquals(listOf(MediaIds.playlist(5)), browseTree.search("ROAD").map { it.mediaId })
    }

    @Test
    fun `search groups results by type -- songs, then albums, then artists, then playlists`() = runTest {
        seedLibrary()
        playlistDao.upsertAll(listOf(PlaylistEntity(id = 5, name = "Artist X Essentials", dateModified = 0)))

        val results = browseTree.search("Artist X")

        assertEquals(
            listOf(MediaIds.artist(10), MediaIds.playlist(5)),
            results.map { it.mediaId },
        )
    }

    @Test
    fun `search returns nothing for a blank query or no matches`() = runTest {
        seedLibrary()

        assertTrue(browseTree.search("   ").isEmpty())
        assertTrue(browseTree.search("nonexistent").isEmpty())
    }
}
