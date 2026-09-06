package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import net.kourlas.dashtone.service.shuffle.Grouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers two real bugs in the generic (non-preset) shuffle toggle:
 *
 * 1. Turning shuffle on used to shuffle the whole natural-order queue and just locate wherever
 *    the playing song landed, which left every track that landed BEFORE it permanently
 *    unreachable (playback only ever advances forward from the current index) -- the playing
 *    song visibly "ending up in the middle of the queue" with tracks ahead of it that would
 *    never play.
 * 2. The toggle ignored category structure and the user's configured [CategoryShuffleMode]
 *    entirely, always doing a flat per-song shuffle -- unlike a "Shuffle Albums"-style preset
 *    action, which [net.kourlas.dashtone.service.shuffle.GroupedQueueBuilder] builds correctly.
 *
 * Runs under Robolectric because [MediaMetadata] construction touches Android framework classes,
 * same as [QueueShufflePlayerDoubleTapScenarioTest]. */
@RunWith(RobolectricTestRunner::class)
class ReorderForShuffleToggleTest {

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun song(id: String, albumTitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle(albumTitle).build())
        .build()

    @Test
    fun `enabling shuffle always puts the playing song at index 0`() {
        val naturalOrder = listOf(item("a"), item("b"), item("c"), item("d"), item("e"))

        val (reordered, startIndex) = reorderForShuffleToggle(naturalOrder, currentMediaId = "c", shuffleModeEnabled = true)

        assertEquals(0, startIndex)
        assertEquals("c", reordered[0].mediaId)
    }

    @Test
    fun `enabling shuffle keeps every natural-order track reachable, just reordered after the playing one`() {
        val naturalOrder = listOf(item("a"), item("b"), item("c"), item("d"), item("e"))

        val (reordered, _) = reorderForShuffleToggle(naturalOrder, currentMediaId = "c", shuffleModeEnabled = true)

        assertEquals(naturalOrder.size, reordered.size)
        assertEquals(naturalOrder.map { it.mediaId }.toSet(), reordered.map { it.mediaId }.toSet())
    }

    @Test
    fun `disabling shuffle restores natural order and resumes wherever the playing song naturally falls`() {
        val naturalOrder = listOf(item("a"), item("b"), item("c"), item("d"), item("e"))

        val (reordered, startIndex) = reorderForShuffleToggle(naturalOrder, currentMediaId = "c", shuffleModeEnabled = false)

        assertEquals(naturalOrder, reordered)
        assertEquals(2, startIndex)
    }

    @Test
    fun `a playing song not found in natural order still returns a valid start index`() {
        val naturalOrder = listOf(item("a"), item("b"))

        val (reorderedOn, startIndexOn) = reorderForShuffleToggle(naturalOrder, currentMediaId = "missing", shuffleModeEnabled = true)
        val (reorderedOff, startIndexOff) = reorderForShuffleToggle(naturalOrder, currentMediaId = "missing", shuffleModeEnabled = false)

        assertEquals(naturalOrder.size, reorderedOn.size)
        assertEquals(0, startIndexOn)
        assertEquals(naturalOrder, reorderedOff)
        assertEquals(0, startIndexOff)
    }

    @Test
    fun `no grouping falls back to a flat shuffle regardless of category shuffle mode`() {
        // A single album, playlist, or search result has no category structure to speak of --
        // CategoryShuffleMode is meaningless here, so this must not throw or behave differently
        // depending on it.
        val naturalOrder = listOf(item("a"), item("b"), item("c"), item("d"))

        val (reordered, startIndex) = reorderForShuffleToggle(
            naturalOrder, currentMediaId = "b", shuffleModeEnabled = true,
            grouping = null, categoryShuffleMode = CategoryShuffleMode.BOTH,
        )

        assertEquals(0, startIndex)
        assertEquals("b", reordered[0].mediaId)
        assertEquals(naturalOrder.map { it.mediaId }.toSet(), reordered.map { it.mediaId }.toSet())
    }

    @Test
    fun `CATEGORIES mode shuffles album order but keeps every album's own track order intact`() {
        val naturalOrder = listOf(
            song("a1", "Album A"), song("a2", "Album A"),
            song("b1", "Album B"), song("b2", "Album B"),
            song("c1", "Album C"), song("c2", "Album C"),
        )

        val (reordered, startIndex) = reorderForShuffleToggle(
            naturalOrder, currentMediaId = "b1", shuffleModeEnabled = true,
            grouping = Grouping.ALBUM, categoryShuffleMode = CategoryShuffleMode.CATEGORIES,
        )

        assertEquals(0, startIndex)
        assertEquals("b1", reordered[0].mediaId)
        // Every album's two tracks must appear back-to-back in original relative order --
        // including Album B, the playing song's own album: b2 must immediately follow b1, not be
        // scattered off wherever Album B's category turn would otherwise land.
        val idsInOrder = reordered.map { it.mediaId }
        val albumOf = mapOf("a1" to "A", "a2" to "A", "b1" to "B", "b2" to "B", "c1" to "C", "c2" to "C")
        val albumRuns = idsInOrder.map { albumOf.getValue(it) }
        var i = 0
        while (i < albumRuns.size) {
            val run = albumRuns[i]
            val runLength = albumRuns.drop(i).takeWhile { it == run }.size
            assertEquals("album $run's tracks must be contiguous", 2, runLength)
            i += runLength
        }
        assertEquals(listOf("a1", "a2"), idsInOrder.filter { albumOf.getValue(it) == "A" })
        assertEquals(listOf("b1", "b2"), idsInOrder.filter { albumOf.getValue(it) == "B" })
        assertEquals(listOf("c1", "c2"), idsInOrder.filter { albumOf.getValue(it) == "C" })
    }

    @Test
    fun `the playing song's own category always leads, even when category order is shuffled`() {
        val naturalOrder = listOf(
            song("a1", "Album A"), song("a2", "Album A"),
            song("b1", "Album B"), song("b2", "Album B"), song("b3", "Album B"),
            song("c1", "Album C"), song("c2", "Album C"),
        )

        repeat(20) {
            val (reordered, _) = reorderForShuffleToggle(
                naturalOrder, currentMediaId = "b2", shuffleModeEnabled = true,
                grouping = Grouping.ALBUM, categoryShuffleMode = CategoryShuffleMode.BOTH,
            )
            // Album B (the playing song's album) must occupy the first three slots no matter how
            // the other categories get shuffled around it, with b2 leading.
            assertEquals("b2", reordered[0].mediaId)
            assertEquals(setOf("b1", "b2", "b3"), reordered.take(3).map { it.mediaId }.toSet())
        }
    }

    @Test
    fun `SONGS mode keeps album order natural but may shuffle songs within each album`() {
        val naturalOrder = listOf(
            song("a1", "Album A"), song("a2", "Album A"), song("a3", "Album A"),
            song("b1", "Album B"), song("b2", "Album B"),
        )

        val (reordered, _) = reorderForShuffleToggle(
            naturalOrder, currentMediaId = "a2", shuffleModeEnabled = true,
            grouping = Grouping.ALBUM, categoryShuffleMode = CategoryShuffleMode.SONGS,
        )

        // Category order is untouched -- every Album A track (regardless of internal order)
        // still comes before every Album B track.
        val idsInOrder = reordered.map { it.mediaId }
        val lastAlbumAIndex = idsInOrder.indexOfLast { it in setOf("a1", "a2", "a3") }
        val firstAlbumBIndex = idsInOrder.indexOfFirst { it in setOf("b1", "b2") }
        assertTrue(lastAlbumAIndex < firstAlbumBIndex)
    }

    @Test
    fun `BOTH mode still keeps the playing song first even though every axis is randomized`() {
        val naturalOrder = listOf(
            song("a1", "Album A"), song("a2", "Album A"),
            song("b1", "Album B"), song("b2", "Album B"),
        )

        val (reordered, startIndex) = reorderForShuffleToggle(
            naturalOrder, currentMediaId = "a1", shuffleModeEnabled = true,
            grouping = Grouping.ALBUM, categoryShuffleMode = CategoryShuffleMode.BOTH,
        )

        assertEquals(0, startIndex)
        assertEquals("a1", reordered[0].mediaId)
        assertEquals(naturalOrder.map { it.mediaId }.toSet(), reordered.map { it.mediaId }.toSet())
    }
}
