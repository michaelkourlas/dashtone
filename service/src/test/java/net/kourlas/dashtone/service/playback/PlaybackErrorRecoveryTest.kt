package net.kourlas.dashtone.service.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TRANSIENT = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
private const val PERMANENT = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

class PlaybackErrorRecoveryTest {

    @Test
    fun `retries the current item on a transient error instead of skipping`() {
        val recovery = PlaybackErrorRecovery()

        assertEquals(
            PlaybackErrorRecovery.Action.RetryCurrent(1_000L),
            recovery.onError(TRANSIENT, hasNextItem = true),
        )
    }

    @Test
    fun `retries a transient error even when the queue has nothing left`() {
        val recovery = PlaybackErrorRecovery()

        assertTrue(recovery.onError(TRANSIENT, hasNextItem = false) is PlaybackErrorRecovery.Action.RetryCurrent)
    }

    @Test
    fun `retry delays grow and then hold at the last delay`() {
        val recovery = PlaybackErrorRecovery(maxRetriesPerItem = 5, retryDelaysMs = listOf(10L, 20L, 40L))

        val delays = (1..5).map {
            (recovery.onError(TRANSIENT, hasNextItem = true) as PlaybackErrorRecovery.Action.RetryCurrent).delayMs
        }

        assertEquals(listOf(10L, 20L, 40L, 40L, 40L), delays)
    }

    @Test
    fun `skips once the retry budget for the current item runs out`() {
        val recovery = PlaybackErrorRecovery(maxRetriesPerItem = 2)

        recovery.onError(TRANSIENT, hasNextItem = true)
        recovery.onError(TRANSIENT, hasNextItem = true)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(TRANSIENT, hasNextItem = true))
    }

    @Test
    fun `the next item gets a fresh retry budget after a skip`() {
        val recovery = PlaybackErrorRecovery(maxRetriesPerItem = 1)

        recovery.onError(TRANSIENT, hasNextItem = true)
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(TRANSIENT, hasNextItem = true))
        recovery.onItemChanged()

        assertTrue(recovery.onError(TRANSIENT, hasNextItem = true) is PlaybackErrorRecovery.Action.RetryCurrent)
    }

    @Test
    fun `a manual item change resets the retry budget`() {
        val recovery = PlaybackErrorRecovery(maxRetriesPerItem = 1)

        recovery.onError(TRANSIENT, hasNextItem = true)
        recovery.onItemChanged()

        assertTrue(recovery.onError(TRANSIENT, hasNextItem = true) is PlaybackErrorRecovery.Action.RetryCurrent)
    }

    @Test
    fun `skips immediately on a permanent error when the queue has more to try`() {
        val recovery = PlaybackErrorRecovery()

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
    }

    @Test
    fun `gives up on a permanent error when there's nothing left in the queue`() {
        val recovery = PlaybackErrorRecovery()

        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(PERMANENT, hasNextItem = false))
    }

    @Test
    fun `gives up once consecutive skips exceed the cap, even with items left`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveSkips = 2)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(PERMANENT, hasNextItem = true))
    }

    @Test
    fun `a successful playback start resets the skip streak and the retry budget`() {
        val recovery = PlaybackErrorRecovery(maxRetriesPerItem = 1, maxConsecutiveSkips = 2)

        recovery.onError(PERMANENT, hasNextItem = true)
        recovery.onError(PERMANENT, hasNextItem = true)
        recovery.onError(TRANSIENT, hasNextItem = true)
        recovery.onPlaybackStarted()

        // Without the reset, both of these would have moved on to a skip and then a give-up.
        assertTrue(recovery.onError(TRANSIENT, hasNextItem = true) is PlaybackErrorRecovery.Action.RetryCurrent)
        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
    }

    @Test
    fun `giving up itself resets the streak, so a fresh run of errors later gets its own full budget`() {
        val recovery = PlaybackErrorRecovery(maxConsecutiveSkips = 1)

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
        assertEquals(PlaybackErrorRecovery.Action.GiveUp, recovery.onError(PERMANENT, hasNextItem = true))

        assertEquals(PlaybackErrorRecovery.Action.SkipToNext, recovery.onError(PERMANENT, hasNextItem = true))
    }

    @Test
    fun `source and audio output errors are transient, file content errors are permanent`() {
        assertTrue(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertTrue(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertFalse(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(PlaybackErrorRecovery.isTransient(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }
}
