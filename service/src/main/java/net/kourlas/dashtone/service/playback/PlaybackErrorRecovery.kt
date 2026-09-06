package net.kourlas.dashtone.service.playback

/**
 * Pure decision logic behind [DashtoneMediaLibraryService]'s player-error recovery, kept separate
 * so it's directly unit-testable against plain booleans rather than a real ExoPlayer/PlaybackException.
 */
internal class PlaybackErrorRecovery(private val maxConsecutiveErrors: Int = 5) {
    private var consecutiveErrors = 0

    /** Call when a queued item actually starts playing -- resets the failure streak, since a run
     * of failures should only trip the give-up threshold if they're truly back-to-back. */
    fun onPlaybackStarted() {
        consecutiveErrors = 0
    }

    /** Call from [androidx.media3.common.Player.Listener.onPlayerError]. [hasNextItem] is whether
     * the queue has anything left to fall back to. */
    fun onError(hasNextItem: Boolean): Action {
        consecutiveErrors++
        if (!hasNextItem || consecutiveErrors > maxConsecutiveErrors) {
            consecutiveErrors = 0
            return Action.GiveUp
        }
        return Action.SkipToNext
    }

    enum class Action { SkipToNext, GiveUp }
}
