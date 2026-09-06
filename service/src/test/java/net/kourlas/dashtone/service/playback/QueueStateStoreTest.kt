package net.kourlas.dashtone.service.playback

import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.shuffle.Grouping
import net.kourlas.dashtone.service.testing.FakeQueueStateDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class QueueStateStoreTest {

    private lateinit var dao: FakeQueueStateDao
    private lateinit var store: QueueStateStore

    @Before
    fun setUp() {
        dao = FakeQueueStateDao()
        store = QueueStateStore(dao)
    }

    @Test
    fun `load returns null before anything has been saved`() = runTest {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips the whole queue`() = runTest {
        store.save(
            songIds = listOf(3L, 1L, 2L),
            naturalSongIds = listOf(1L, 2L, 3L),
            currentIndex = 1,
            isShuffled = true,
            grouping = Grouping.ALBUM,
            repeatModeOrdinal = 2,
        )

        val state = store.load()

        assertEquals(listOf(3L, 1L, 2L), state?.songIds)
        assertEquals(listOf(1L, 2L, 3L), state?.naturalSongIds)
        assertEquals(1, state?.currentIndex)
        assertEquals(true, state?.isShuffled)
        assertEquals(Grouping.ALBUM, state?.grouping)
        assertEquals(2, state?.repeatModeOrdinal)
    }

    @Test
    fun `null grouping round-trips for an ungrouped queue`() = runTest {
        store.save(
            songIds = listOf(1L),
            naturalSongIds = listOf(1L),
            currentIndex = 0,
            isShuffled = false,
            grouping = null,
            repeatModeOrdinal = 0,
        )

        assertNull(store.load()?.grouping)
    }

    @Test
    fun `save overwrites the previous state`() = runTest {
        store.save(listOf(1L), listOf(1L), 0, false, null, 0)
        store.save(listOf(2L), listOf(2L), 0, false, null, 1)

        val state = store.load()

        assertEquals(listOf(2L), state?.songIds)
        assertEquals(1, state?.repeatModeOrdinal)
    }
}
