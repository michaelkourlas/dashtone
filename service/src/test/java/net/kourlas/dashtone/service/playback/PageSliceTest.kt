package net.kourlas.dashtone.service.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the paging arithmetic behind onGetChildren/onGetSearchResult. Plain JUnit, no Robolectric:
 * [pageSlice] is deliberately free of Media3 types so it can be exercised on a list of anything.
 */
class PageSliceTest {

    private val items = (1..10).toList()

    @Test
    fun `the first page starts at the beginning`() {
        assertEquals(listOf(1, 2, 3), items.pageSlice(page = 0, pageSize = 3))
    }

    @Test
    fun `a later page starts at its own offset`() {
        assertEquals(listOf(4, 5, 6), items.pageSlice(page = 1, pageSize = 3))
    }

    @Test
    fun `a partial final page returns what is left`() {
        assertEquals(listOf(10), items.pageSlice(page = 3, pageSize = 3))
    }

    @Test
    fun `a page past the end is empty`() {
        assertEquals(emptyList<Int>(), items.pageSlice(page = 99, pageSize = 3))
    }

    // Media3's legacy bridge passes Int.MAX_VALUE when a browser sets no page options, so this is
    // the realistic unbounded request rather than a synthetic edge case.
    @Test
    fun `an unbounded page size returns the whole list on the first page`() {
        assertEquals(items, items.pageSlice(page = 0, pageSize = Int.MAX_VALUE))
    }

    // page * pageSize overflows Int to a negative offset here, and List.drop rejects a negative
    // count outright -- which reached the host as the whole browse request failing.
    @Test
    fun `a later page of an unbounded page size is empty rather than throwing`() {
        assertEquals(emptyList<Int>(), items.pageSlice(page = 1, pageSize = Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), items.pageSlice(page = 5000, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `a non-positive page size is empty`() {
        assertEquals(emptyList<Int>(), items.pageSlice(page = 0, pageSize = 0))
        assertEquals(emptyList<Int>(), items.pageSlice(page = 0, pageSize = -1))
    }

    @Test
    fun `a negative page reads as the first page`() {
        assertEquals(listOf(1, 2, 3), items.pageSlice(page = -1, pageSize = 3))
    }

    @Test
    fun `an empty list pages to empty`() {
        assertEquals(emptyList<Int>(), emptyList<Int>().pageSlice(page = 0, pageSize = 10))
    }
}
