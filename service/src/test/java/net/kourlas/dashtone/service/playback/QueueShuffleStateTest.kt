package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import net.kourlas.dashtone.service.shuffle.Grouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the isShuffled setter's change-notification -- the exact root cause of a real bug this
 * session where the shuffle command button's icon defaulted back to "off" after "Shuffle Albums"
 * despite genuinely being shuffled, because the original code only refreshed the icon from one
 * specific call site rather than on any real change to this property. */
class QueueShuffleStateTest {

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `setNatural marks the state unshuffled with no grouping`() {
        val state = QueueShuffleState()

        state.setShuffled(listOf(item("a")), Grouping.ALBUM)
        state.setNatural(listOf(item("b"), item("c")))

        assertFalse(state.isShuffled)
        assertNull(state.currentGrouping)
        assertEquals(listOf(item("b"), item("c")), state.naturalOrder)
    }

    @Test
    fun `setShuffled marks the state shuffled with the given grouping`() {
        val state = QueueShuffleState()

        state.setShuffled(listOf(item("a"), item("b")), Grouping.ARTIST)

        assertTrue(state.isShuffled)
        assertEquals(Grouping.ARTIST, state.currentGrouping)
        assertEquals(listOf(item("a"), item("b")), state.naturalOrder)
    }

    @Test
    fun `setShuffled with a null grouping is used for a flat shuffle-all queue`() {
        val state = QueueShuffleState()

        state.setShuffled(listOf(item("a")), grouping = null)

        assertTrue(state.isShuffled)
        assertNull(state.currentGrouping)
    }

    @Test
    fun `onShuffleChanged fires when isShuffled actually changes via setShuffled`() {
        var lastNotified: Boolean? = null
        val state = QueueShuffleState { shuffled -> lastNotified = shuffled }

        state.setShuffled(listOf(item("a")), Grouping.ALBUM)

        assertEquals(true, lastNotified)
    }

    @Test
    fun `onShuffleChanged fires when isShuffled actually changes via setNatural`() {
        var lastNotified: Boolean? = null
        val state = QueueShuffleState { shuffled -> lastNotified = shuffled }
        state.setShuffled(listOf(item("a")), Grouping.ALBUM)
        lastNotified = null

        state.setNatural(listOf(item("b")))

        assertEquals(false, lastNotified)
    }

    @Test
    fun `onShuffleChanged does not fire again when the new value matches the current one`() {
        var notifyCount = 0
        val state = QueueShuffleState { notifyCount++ }
        state.setShuffled(listOf(item("a")), Grouping.ALBUM)
        assertEquals(1, notifyCount)

        // Still shuffled, just a different queue -- isShuffled itself doesn't change.
        state.setShuffled(listOf(item("b")), Grouping.ARTIST)

        assertEquals(1, notifyCount)
    }

    @Test
    fun `direct assignment to isShuffled also fires onShuffleChanged, not just the queue-establishing methods`() {
        // This is the actual bug: the icon-refresh callback must fire no matter which code path
        // flips isShuffled, since a live QueueShufflePlayer toggle sets it directly rather than
        // going through setNatural/setShuffled.
        var notifyCount = 0
        val state = QueueShuffleState { notifyCount++ }

        state.isShuffled = true

        assertEquals(1, notifyCount)
        assertTrue(state.isShuffled)
    }

    @Test
    fun `assigning isShuffled to its current value does not notify`() {
        var notifyCount = 0
        val state = QueueShuffleState { notifyCount++ }

        state.isShuffled = false // already false by default

        assertEquals(0, notifyCount)
    }
}
