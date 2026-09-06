package net.kourlas.dashtone.service.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorRecoveryTest {

    @Test
    fun `skips to next item on error when the queue has more to try`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveErrors = 5)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
    }

    @Test
    fun `gives up immediately when there's nothing left in the queue`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveErrors = 5)

        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(hasNextItem = false))
    }

    @Test
    fun `gives up once consecutive errors exceed the cap, even with items left`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveErrors = 2)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(hasNextItem = true))
    }

    @Test
    fun `a successful playback start resets the failure streak`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveErrors = 2)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
        recovery.onPlaybackStarted()

        // Would have been GiveUp here without the reset (this is the 3rd error in the sequence).
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
    }

    @Test
    fun `giving up itself resets the streak, so a fresh run of errors later gets its own full budget`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveErrors = 1)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(hasNextItem = true))

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(hasNextItem = true))
    }
}
