package net.kourlas.dashtone.service.playback

import android.os.Bundle
import android.provider.MediaStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntryEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.mediastore.LibraryFolder
import net.kourlas.dashtone.service.data.mediastore.LibraryScanner
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.ShuffleController
import net.kourlas.dashtone.service.testing.FakeAlbumArtInvalidator
import net.kourlas.dashtone.service.testing.FakeAlbumDao
import net.kourlas.dashtone.service.testing.FakeArtistDao
import net.kourlas.dashtone.service.testing.FakeDataStore
import net.kourlas.dashtone.service.testing.FakePlaylistDao
import net.kourlas.dashtone.service.testing.FakeQueueStateDao
import net.kourlas.dashtone.service.testing.FakeSongDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoOpScannerForVoiceTest : LibraryScanner {
    override suspend fun scanLibrary(force: Boolean) = Unit
    override suspend fun availableFolders(): List<LibraryFolder> = emptyList()
}

/** Covers [DashtoneLibrarySessionCallback.resolveVoiceRequest] -- the voice-action routing behind
 * https://developer.android.com/training/cars/media/voice-actions -- made `internal` specifically
 * so it's directly testable without needing a real [androidx.media3.session.MediaSession]. Runs
 * under Robolectric because [MediaItemMapper] parses real android.net.Uri values. */
@RunWith(RobolectricTestRunner::class)
class DashtoneLibrarySessionCallbackTest {

    private lateinit var songDao: FakeSongDao
    private lateinit var albumDao: FakeAlbumDao
    private lateinit var artistDao: FakeArtistDao
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var callback: DashtoneLibrarySessionCallback

    @Before
    fun setUp() {
        songDao = FakeSongDao()
        albumDao = FakeAlbumDao(songDao)
        artistDao = FakeArtistDao(songDao)
        playlistDao = FakePlaylistDao(songDao)
        val preferences = AppPreferences(FakeDataStore(emptyPreferences()))
        val repository = LibraryRepository(
            songDao, albumDao, artistDao, playlistDao, NoOpScannerForVoiceTest(), preferences, FakeAlbumArtInvalidator(),
        )
        val browseTree = LibraryBrowseTree(repository, MediaItemMapper(ApplicationProvider.getApplicationContext()))
        val shuffleController = ShuffleController(repository)
        val resumptionProvider = PlaybackResumptionProvider(browseTree, QueueStateStore(FakeQueueStateDao()))
        callback = DashtoneLibrarySessionCallback(
            browseTree,
            shuffleController,
            resumptionProvider,
            QueueShuffleState(),
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private suspend fun seedLibrary() {
        artistDao.upsertAll(listOf(ArtistEntity(id = 10, name = "Adele"), ArtistEntity(id = 20, name = "ABBA")))
        albumDao.upsertAll(
            listOf(
                AlbumEntity(id = 100, name = "19", artistId = 10, year = null, trackCount = 2),
                AlbumEntity(id = 200, name = "ABBA Gold", artistId = 20, year = null, trackCount = 1),
            ),
        )
        songDao.upsertAll(
            listOf(
                SongEntity(
                    id = 1, title = "Make You Feel My Love", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/1",
                    dateAdded = 0, dateModified = 0, genreName = "Pop",
                ),
                SongEntity(
                    id = 2, title = "Someone Like You", trackNumber = 2, discNumber = 1, durationMs = 200_000,
                    albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/2",
                    dateAdded = 0, dateModified = 0, genreName = "Pop",
                ),
                SongEntity(
                    id = 3, title = "Dancing Queen", trackNumber = 1, discNumber = 1, durationMs = 200_000,
                    albumId = 200, artistId = 20, contentUri = "content://media/external/audio/media/3",
                    dateAdded = 0, dateModified = 0, genreName = "Disco",
                ),
            ),
        )
        playlistDao.upsertAll(listOf(PlaylistEntity(id = 5, name = "Road Trip", dateModified = 0)))
        playlistDao.insertEntries(
            listOf(
                PlaylistEntryEntity(playlistId = 5, position = 0, songId = 3),
                PlaylistEntryEntity(playlistId = 5, position = 1, songId = 1),
            ),
        )
    }

    private fun extrasOf(vararg pairs: Pair<String, String>) = Bundle().apply {
        for ((key, value) in pairs) putString(key, value)
    }

    @Test
    fun `genre focus plays every song in that genre`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_GENRE to "Pop",
        )
        val result = callback.resolveVoiceRequest("play some pop", extras)
        assertEquals(listOf(MediaIds.song(1), MediaIds.song(2)), result.map { it.mediaId })
    }

    @Test
    fun `artist focus plays all of an artist's songs, not their album list`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_ARTIST to "Adele",
        )
        val result = callback.resolveVoiceRequest("play adele", extras)
        assertEquals(listOf(MediaIds.song(1), MediaIds.song(2)), result.map { it.mediaId })
        assertTrue("expected playable songs, not browsable albums", result.all { it.mediaMetadata.isPlayable == true })
    }

    @Test
    fun `album focus plays that album's songs`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_ALBUM to "19",
        )
        val result = callback.resolveVoiceRequest("play the album 19", extras)
        assertEquals(listOf(MediaIds.song(1), MediaIds.song(2)), result.map { it.mediaId })
    }

    @Test
    fun `playlist focus plays that playlist in file order`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_PLAYLIST to "Road Trip",
        )
        val result = callback.resolveVoiceRequest("play my road trip playlist", extras)
        assertEquals(listOf(MediaIds.song(3), MediaIds.song(1)), result.map { it.mediaId })
    }

    @Test
    fun `song focus plays that one song alone`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_TITLE to "Dancing Queen",
        )
        val result = callback.resolveVoiceRequest("play dancing queen", extras)
        assertEquals(listOf(MediaIds.song(3)), result.map { it.mediaId })
    }

    @Test
    fun `plain text query with no extras falls back to substring search`() = runTest {
        seedLibrary()
        val result = callback.resolveVoiceRequest("Dancing Queen", null)
        assertEquals(listOf(MediaIds.song(3)), result.map { it.mediaId })
    }

    @Test
    fun `plain text query matching an artist plays all their songs, not their albums`() = runTest {
        seedLibrary()
        val result = callback.resolveVoiceRequest("ABBA", null)
        assertEquals(listOf(MediaIds.song(3)), result.map { it.mediaId })
    }

    @Test
    fun `blank query with no usable extras falls back to shuffling everything`() = runTest {
        seedLibrary()
        val result = callback.resolveVoiceRequest("", null)
        assertEquals(3, result.size)
        assertEquals(setOf(MediaIds.song(1), MediaIds.song(2), MediaIds.song(3)), result.map { it.mediaId }.toSet())
    }

    @Test
    fun `a focus extra with no matching library entry falls back to plain text search`() = runTest {
        seedLibrary()
        val extras = extrasOf(
            MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE,
            MediaStore.EXTRA_MEDIA_GENRE to "Nonexistent Genre",
        )
        val result = callback.resolveVoiceRequest("Dancing Queen", extras)
        assertEquals(listOf(MediaIds.song(3)), result.map { it.mediaId })
    }
}
