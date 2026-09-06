package net.kourlas.dashtone.service.playback

/**
 * Pure timing/state logic behind [QueueShufflePlayer]'s double-tap category skip, kept separate
 * -- same rationale as [QueueGroupNavigation] -- so it's directly unit-testable rather than only
 * exercisable through a real [androidx.media3.common.Player] and real elapsed-time delays. Time
 * is passed in by the caller rather than read from `SystemClock` internally, so tests can use
 * arbitrary fixed instants instead of real sleeps.
 *
 * The problem this solves: each physical tap arrives as an independent call, and the first tap of
 * a pair always executes immediately as a plain single-item skip (so a single tap stays
 * responsive rather than waiting out the whole double-tap window to see if a second one follows).
 * If a second tap's "jump to the next category" were computed from wherever that first skip left
 * the queue, a category with as few as one item would make the jump overshoot by a whole extra
 * category, since the first tap's plain skip would have already crossed into the next category on
 * its own. Remembering the index from BEFORE the first tap's skip and resolving the second tap's
 * jump relative to THAT origin -- not the post-first-skip position -- keeps a double-tap's total
 * effect at exactly one category jump no matter how many items are in any category involved.
 */
internal class DoubleTapNavigation(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private var lastTapAtMs: Long = NEVER
    private var pendingOriginIndex: Int = NO_INDEX

    sealed interface Outcome {
        /** First tap of a pair, or the previous tap was too long ago to pair with this one --
         * let it proceed as a normal single-item skip. */
        data object SingleTap : Outcome

        /** Second tap of a pair, confirmed within the window -- the category jump (if any) should
         * be resolved from [originIndex], the position the FIRST tap of this pair started from,
         * not wherever that first tap's own skip ended up. */
        data class DoubleTap(val originIndex: Int) : Outcome
    }

    /** [nowMs] and [currentIndexBeforeThisTap] must both be captured before any skip this tap
     * triggers is allowed to run, so a [DoubleTap] outcome's [Outcome.DoubleTap.originIndex]
     * reflects genuinely pre-tap state. */
    fun onTap(nowMs: Long, currentIndexBeforeThisTap: Int): Outcome {
        val isDoubleTap = nowMs - lastTapAtMs in 0..windowMs
        if (!isDoubleTap) {
            lastTapAtMs = nowMs
            pendingOriginIndex = currentIndexBeforeThisTap
            return Outcome.SingleTap
        }
        // Cleared rather than updated to `now`: a third rapid tap must start a fresh pair of its
        // own, not chain onto this one as if it were a triple-tap.
        lastTapAtMs = NEVER
        return Outcome.DoubleTap(pendingOriginIndex)
    }

    private companion object {
        // What's being timed here is "two skip commands arriving close together": car.media
        // handles the actual tap and forwards it as a MediaSession skip command, so this measures
        // car.media's own dispatch plus IPC latency on top of the user's actual tap timing, rather
        // than a raw touch gesture -- hence not read from ViewConfiguration.getDoubleTapTimeout()
        // (Android's own raw-touch-gesture double-tap window, ~300ms by default). 300ms flat would
        // very plausibly be too tight once that overhead is added, effectively never letting
        // genuine double-taps register at all. 800ms (the original value) proved too loose the
        // other way -- two deliberate, unhurried single taps were getting merged into a
        // double-tap. 400ms is a middle ground: closer to the platform's own convention while
        // leaving some headroom for the extra dispatch latency this path has beyond a raw touch
        // gesture.
        const val DEFAULT_WINDOW_MS = 400L
        const val NEVER = Long.MIN_VALUE / 2 // arithmetic-safe "effectively never" sentinel
        const val NO_INDEX = -1
    }
}
