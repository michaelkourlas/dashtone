package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.kourlas.dashtone.service.shuffle.Grouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun item(id: String, albumTitle: String, artist: String = "Artist") = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle(albumTitle).setArtist(artist).build())
    .build()

private fun itemWithGenreAndAlbumArtist(id: String, genre: String? = null, albumArtist: String? = null) =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setGenre(genre).setAlbumArtist(albumArtist).build())
        .build()

/** Runs under Robolectric because MediaItem/MediaMetadata construction touches Android framework
 * classes (mirrors the rest of the playback package's test conventions). */
@RunWith(RobolectricTestRunner::class)
class QueueGroupNavigationTest {

    private val queue = listOf(
        item("a1", albumTitle = "Album A"),
        item("a2", albumTitle = "Album A"),
        item("b1", albumTitle = "Album B"),
        item("b2", albumTitle = "Album B"),
        item("b3", albumTitle = "Album B"),
        item("c1", albumTitle = "Album C"),
    )

    @Test
    fun `forward from the middle of a group jumps to the start of the next group`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(queue, currentIndex = 3, Grouping.ALBUM, forward = true)
        assertEquals(5, target) // index of "c1"
    }

    @Test
    fun `backward from the middle of a group jumps to the start of the PREVIOUS group, not the current one`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(queue, currentIndex = 3, Grouping.ALBUM, forward = false)
        assertEquals(0, target) // index of "a1", start of Album A -- not index 2, start of Album B
    }

    @Test
    fun `forward from the last group returns null`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(queue, currentIndex = 5, Grouping.ALBUM, forward = true)
        assertNull(target)
    }

    @Test
    fun `backward from the first group returns null`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(queue, currentIndex = 1, Grouping.ALBUM, forward = false)
        assertNull(target)
    }

    @Test
    fun `a single-item queue has no adjacent group in either direction`() {
        val single = listOf(item("solo", albumTitle = "Only Album"))
        assertNull(QueueGroupNavigation.adjacentGroupStartIndex(single, 0, Grouping.ALBUM, forward = true))
        assertNull(QueueGroupNavigation.adjacentGroupStartIndex(single, 0, Grouping.ALBUM, forward = false))
    }

    // Mirrors a real Shuffle Albums queue observed live: a 3-track album, then two consecutive
    // single-track albums back to back -- the scenario where a "group" the double-tap lands on is
    // itself just one song, both when it's in the middle of the queue and when it's the last item.
    private val queueWithConsecutiveSingleTrackAlbums = listOf(
        item("dancing-queen", albumTitle = "ABBA Gold"),
        item("knowing-me-knowing-you", albumTitle = "ABBA Gold"),
        item("take-a-chance-on-me", albumTitle = "ABBA Gold"),
        item("make-you-feel-my-love", albumTitle = "19"),
        item("skyfall", albumTitle = "Skyfall - Single"),
    )

    @Test
    fun `forward from a single-track album in the middle jumps straight to the next album`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(
            queueWithConsecutiveSingleTrackAlbums, currentIndex = 3, Grouping.ALBUM, forward = true,
        )
        assertEquals(4, target) // "skyfall" -- there's nowhere else within "19" to skip past
    }

    @Test
    fun `backward from a single-track album in the middle jumps to the start of the PRECEDING multi-track album`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(
            queueWithConsecutiveSingleTrackAlbums, currentIndex = 3, Grouping.ALBUM, forward = false,
        )
        assertEquals(0, target) // "dancing-queen", start of the 3-track "ABBA Gold" -- not index 2
    }

    @Test
    fun `forward from the last item, itself a single-track album, returns null`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(
            queueWithConsecutiveSingleTrackAlbums, currentIndex = 4, Grouping.ALBUM, forward = true,
        )
        assertNull(target) // "skyfall" is both a single-track album AND the last item in the queue
    }

    @Test
    fun `backward from the last item, a single-track album, jumps to the PRECEDING single-track album`() {
        val target = QueueGroupNavigation.adjacentGroupStartIndex(
            queueWithConsecutiveSingleTrackAlbums, currentIndex = 4, Grouping.ALBUM, forward = false,
        )
        assertEquals(3, target) // "make-you-feel-my-love" -- the immediately preceding album, "19"
    }

    @Test
    fun `grouping by artist uses the artist field instead of album title`() {
        val byArtist = listOf(
            item("1", albumTitle = "Ignored", artist = "Artist X"),
            item("2", albumTitle = "Ignored", artist = "Artist X"),
            item("3", albumTitle = "Ignored", artist = "Artist Y"),
        )
        val target = QueueGroupNavigation.adjacentGroupStartIndex(byArtist, currentIndex = 0, Grouping.ARTIST, forward = true)
        assertEquals(2, target)
    }

    @Test
    fun `grouping by genre uses the genre field`() {
        val byGenre = listOf(
            itemWithGenreAndAlbumArtist("1", genre = "Rock"),
            itemWithGenreAndAlbumArtist("2", genre = "Rock"),
            itemWithGenreAndAlbumArtist("3", genre = "Jazz"),
        )
        val target = QueueGroupNavigation.adjacentGroupStartIndex(byGenre, currentIndex = 0, Grouping.GENRE, forward = true)
        assertEquals(2, target)
    }

    @Test
    fun `grouping by album artist uses the album artist field`() {
        val byAlbumArtist = listOf(
            itemWithGenreAndAlbumArtist("1", albumArtist = "Various Artists"),
            itemWithGenreAndAlbumArtist("2", albumArtist = "Various Artists"),
            itemWithGenreAndAlbumArtist("3", albumArtist = "Solo Artist"),
        )
        val target =
            QueueGroupNavigation.adjacentGroupStartIndex(byAlbumArtist, currentIndex = 0, Grouping.ALBUM_ARTIST, forward = true)
        assertEquals(2, target)
    }
}
