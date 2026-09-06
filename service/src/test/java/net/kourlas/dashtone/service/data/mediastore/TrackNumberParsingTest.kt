package net.kourlas.dashtone.service.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNumberParsingTest {

    @Test
    fun `unset track defaults to disc 1 track 0`() {
        assertEquals(1 to 0, parseDiscAndTrack(0))
        assertEquals(1 to 0, parseDiscAndTrack(-1))
    }

    @Test
    fun `track only, no disc encoded`() {
        assertEquals(1 to 5, parseDiscAndTrack(5))
        assertEquals(1 to 999, parseDiscAndTrack(999))
    }

    @Test
    fun `disc and track encoded as disc times 1000 plus track`() {
        assertEquals(1 to 5, parseDiscAndTrack(1005))
        assertEquals(2 to 10, parseDiscAndTrack(2010))
        assertEquals(3 to 1, parseDiscAndTrack(3001))
    }
}
