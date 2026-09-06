package net.kourlas.dashtone.service.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapNavigationTest {

    @Test
    fun `a single tap with nothing before it is never a double-tap`() {
        val nav = DoubleTapNavigation()
        val outcome = nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 5)
        assertTrue(outcome is DoubleTapNavigation.Outcome.SingleTap)
    }

    @Test
    fun `a second tap within the window is a double-tap`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 2)
        val outcome = nav.onTap(nowMs = 1500, currentIndexBeforeThisTap = 3)
        assertTrue(outcome is DoubleTapNavigation.Outcome.DoubleTap)
    }

    @Test
    fun `a second tap outside the window is treated as a fresh single tap, not a double-tap`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 2)
        val outcome = nav.onTap(nowMs = 1801, currentIndexBeforeThisTap = 3)
        assertTrue(outcome is DoubleTapNavigation.Outcome.SingleTap)
    }

    @Test
    fun `exactly at the window boundary still counts as a double-tap`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 2)
        val outcome = nav.onTap(nowMs = 1800, currentIndexBeforeThisTap = 3)
        assertTrue(outcome is DoubleTapNavigation.Outcome.DoubleTap)
    }

    // The whole reason this class exists: the double-tap's origin must be where the FIRST tap
    // started from, not wherever that first tap's own single-item skip already moved to by the
    // time the second tap arrives. The caller passes the CURRENT index at each tap, and by the
    // second tap that's already post-first-skip -- this must be ignored in favor of what was
    // recorded on the first tap.
    @Test
    fun `the double-tap's origin is where the FIRST tap started, not the index passed on the second tap`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 2) // first tap starts at index 2
        // Second tap arrives after the first tap's own skip already moved the queue to index 3 --
        // that's the index this call reports, but it must NOT be what the jump is computed from.
        val outcome = nav.onTap(nowMs = 1500, currentIndexBeforeThisTap = 3)
        assertEquals(DoubleTapNavigation.Outcome.DoubleTap(originIndex = 2), outcome)
    }

    @Test
    fun `a third rapid tap right after a confirmed double-tap starts a fresh pair, not a triple-tap`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 0) // tap 1
        nav.onTap(nowMs = 1200, currentIndexBeforeThisTap = 1) // tap 2 -- confirms the double-tap
        // Tap 3, arriving immediately after tap 2, must be tap 1 of a NEW pair (SingleTap), not
        // chained onto the pair that already resolved.
        val outcome = nav.onTap(nowMs = 1250, currentIndexBeforeThisTap = 7)
        assertTrue(outcome is DoubleTapNavigation.Outcome.SingleTap)
    }

    @Test
    fun `after a fresh pair starts following a resolved double-tap, its own second tap resolves normally`() {
        val nav = DoubleTapNavigation(windowMs = 800)
        nav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 0) // pair 1, tap 1
        nav.onTap(nowMs = 1200, currentIndexBeforeThisTap = 1) // pair 1, tap 2 -- resolved
        nav.onTap(nowMs = 1250, currentIndexBeforeThisTap = 7) // pair 2, tap 1 -- origin is 7
        val outcome = nav.onTap(nowMs = 1400, currentIndexBeforeThisTap = 8) // pair 2, tap 2
        assertEquals(DoubleTapNavigation.Outcome.DoubleTap(originIndex = 7), outcome)
    }

    @Test
    fun `forward and backward taps are tracked independently by using separate instances`() {
        // QueueShufflePlayer holds one instance per direction specifically so a forward tap and a
        // backward tap are never paired against each other -- this test documents that contract at
        // the unit the guarantee actually lives in.
        val nextNav = DoubleTapNavigation(windowMs = 800)
        val previousNav = DoubleTapNavigation(windowMs = 800)
        nextNav.onTap(nowMs = 1000, currentIndexBeforeThisTap = 5)
        val previousOutcome = previousNav.onTap(nowMs = 1100, currentIndexBeforeThisTap = 5)
        assertTrue(previousOutcome is DoubleTapNavigation.Outcome.SingleTap)
    }
}
