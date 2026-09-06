package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.kourlas.dashtone.service.shuffle.Grouping
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun song(id: String, albumTitle: String) = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle(albumTitle).build())
    .build()

/**
 * Composes [DoubleTapNavigation] with [QueueGroupNavigation] the exact way
 * [QueueShufflePlayer.handlePossibleDoubleTap] does, reproducing the three scenarios reported
 * against the live app so they're covered by a fast, deterministic test rather than only by
 * flaky, hard-to-reproduce timing over `adb shell input tap`. Runs under Robolectric for the
 * same reason [QueueGroupNavigationTest] does: MediaItem/MediaMetadata construction touches
 * Android framework classes.
 */
@RunWith(RobolectricTestRunner::class)
class QueueShufflePlayerDoubleTapScenarioTest {

    // Mirrors QueueShufflePlayer.handlePossibleDoubleTap: a tap either lets a plain single-item
    // skip proceed (returns the naively-advanced index, exactly like ExoPlayer's own
    // seekToNext()/seekToPrevious() would) or, on a confirmed double-tap, jumps to the adjacent
    // group's start computed from the ORIGIN the double-tap recorded -- never from wherever the
    // single skip already landed.
    private fun simulateTap(
        nav: DoubleTapNavigation,
        nowMs: Long,
        currentIndex: Int,
        queue: List<MediaItem>,
        forward: Boolean,
    ): Int {
        val outcome = nav.onTap(nowMs, currentIndex)
        val origin = (outcome as? DoubleTapNavigation.Outcome.DoubleTap)?.originIndex
            ?: return (if (forward) currentIndex + 1 else currentIndex - 1).coerceIn(queue.indices)
        return QueueGroupNavigation.adjacentGroupStartIndex(queue, origin, Grouping.ALBUM, forward) ?: currentIndex
    }

    @Test
    fun `double-tap when both the current and next album have only one song lands on the next album, not the one after`() {
        // Album A (1 song), Album B (1 song), Album C (2 songs) -- currently on A's only song.
        val queue = listOf(
            song("a1", albumTitle = "Album A"),
            song("b1", albumTitle = "Album B"),
            song("c1", albumTitle = "Album C"),
            song("c2", albumTitle = "Album C"),
        )
        val nav = DoubleTapNavigation(windowMs = 800)

        val afterTap1 = simulateTap(nav, nowMs = 1000, currentIndex = 0, queue, forward = true)
        val afterTap2 = simulateTap(nav, nowMs = 1200, currentIndex = afterTap1, queue, forward = true)

        assertEquals("tap 1 alone already reaches Album B, since Album A has only one song", 1, afterTap1)
        assertEquals("double-tap must land on Album B (\"b1\"), not overshoot into Album C", 1, afterTap2)
    }

    @Test
    fun `double-tap when the current album has one song and the next has several still lands on the next album`() {
        // Album A (1 song), Album B (3 songs), Album C (1 song) -- currently on A's only song.
        val queue = listOf(
            song("a1", albumTitle = "Album A"),
            song("b1", albumTitle = "Album B"),
            song("b2", albumTitle = "Album B"),
            song("b3", albumTitle = "Album B"),
            song("c1", albumTitle = "Album C"),
        )
        val nav = DoubleTapNavigation(windowMs = 800)

        val afterTap1 = simulateTap(nav, nowMs = 1000, currentIndex = 0, queue, forward = true)
        val afterTap2 = simulateTap(nav, nowMs = 1200, currentIndex = afterTap1, queue, forward = true)

        assertEquals("tap 1 alone already reaches Album B's first song", 1, afterTap1)
        assertEquals("double-tap must land on Album B (\"b1\"), not overshoot into Album C", 1, afterTap2)
    }

    @Test
    fun `double-tap while playing all songs ungrouped skips exactly one song, not two`() {
        val queue = listOf(song("s1", albumTitle = "Ignored"), song("s2", albumTitle = "Ignored"), song("s3", albumTitle = "Ignored"))
        val nav = DoubleTapNavigation(windowMs = 800)

        // No grouping active (Grouping.ALBUM is passed above only because QueueGroupNavigation
        // requires one; the real bug was in the ungrouped path, where QueueShufflePlayer's
        // handlePossibleDoubleTap never calls adjacentGroupStartIndex at all -- reproduced here by
        // asserting the tap-count-to-index-advance relationship directly instead of the grouped
        // helper).
        val outcome1 = nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 0)
        val outcome2 = nav.onTap(nowMs = 1200, currentIndexBeforeThisTap = 1)

        check(outcome1 is DoubleTapNavigation.Outcome.SingleTap) // tap 1: real skip, index 0 -> 1
        // Tap 2 must be an outcome the caller swallows as a no-op when there's no grouping
        // (QueueShufflePlayer.handlePossibleDoubleTap: `state.currentGrouping ?: return true`) --
        // confirmed here as a DoubleTap outcome that the caller does NOT act on, rather than a
        // SingleTap that would trigger a second real skip to index 2.
        check(outcome2 is DoubleTapNavigation.Outcome.DoubleTap)
        assertEquals(0, outcome2.originIndex)
    }
}
