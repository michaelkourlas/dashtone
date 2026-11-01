package net.kourlas.dashtone.service.playback

import androidx.media3.common.PlaybackException

/**
 * Pure decision logic behind [DashtoneMediaLibraryService]'s player-error recovery, kept separate
 * so it's directly unit-testable against plain values rather than a real ExoPlayer.
 *
 * A transient error (see [isTransient]) keeps playback on the current item and retries it after
 * a growing delay, up to [maxRetriesPerItem] times. This covers the window right after a car
 * boots, when the storage volume, MediaStore or audio output may not be ready yet: every item in
 * the queue fails the same way during that window, so skipping ahead only discards songs. A
 * permanent error (a corrupt file, an unsupported codec), or a transient one that outlasts the
 * retry budget, skips to the next item instead. [maxConsecutiveSkips] bounds the run of skips so
 * that a queue that has gone entirely bad stops cleanly.
 */
internal class PlaybackErrorRecovery(
    private val maxRetriesPerItem: Int = 20,
    private val maxConsecutiveSkips: Int = 5,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    private var retriesOnCurrentItem = 0
    private var consecutiveSkips = 0

    /** Call when a queued item actually starts playing -- resets both counters, since the limits
     * only apply to failures that are truly back-to-back. */
    fun onPlaybackStarted() {
        retriesOnCurrentItem = 0
        consecutiveSkips = 0
    }

    /** Call when a different item becomes current, so that each item gets its own retry budget. A
     * retry of the current item does not count as a change. */
    fun onItemChanged() {
        retriesOnCurrentItem = 0
    }

    /** Call from [androidx.media3.common.Player.Listener.onPlayerError]. [hasNextItem] is whether
     * the queue has anything left to fall back to. */
    fun onError(errorCode: Int, hasNextItem: Boolean): Action {
        if (isTransient(errorCode) && retriesOnCurrentItem < maxRetriesPerItem) {
            val delayMs = retryDelaysMs[retriesOnCurrentItem.coerceAtMost(retryDelaysMs.lastIndex)]
            retriesOnCurrentItem++
            return Action.RetryCurrent(delayMs)
        }
        retriesOnCurrentItem = 0
        consecutiveSkips++
        if (!hasNextItem || consecutiveSkips > maxConsecutiveSkips) {
            consecutiveSkips = 0
            return Action.GiveUp
        }
        return Action.SkipToNext
    }

    sealed interface Action {
        data class RetryCurrent(val delayMs: Long) : Action
        data object SkipToNext : Action
        data object GiveUp : Action
    }

    companion object {
        // The last delay repeats for every retry after the list runs out. With the default budget
        // of 20 retries, the current item stays queued for about four minutes before a skip.
        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** Whether [errorCode] (a [PlaybackException] error code) describes a condition that can
         * clear on its own while the item itself stays intact: a source that cannot be opened
         * yet, or an audio output or codec that is temporarily unavailable. Parsing, decoding and
         * DRM errors describe the file itself, so a retry of the same file fails the same way. */
        fun isTransient(errorCode: Int): Boolean = when (errorCode) {
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            -> true
            else -> false
        }
    }
}
