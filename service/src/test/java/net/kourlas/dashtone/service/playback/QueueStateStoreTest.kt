package net.kourlas.dashtone.service.playback

import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.local.QueueStateEntity
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

    // Grouping is persisted by ordinal, so a reordered or removed constant yields an ordinal with
    // no enum behind it. The queue still restores; only double-tap category skipping is lost.
    @Test
    fun `an out-of-range grouping ordinal still restores the queue, without a grouping`() = runTest {
        dao.upsert(
            QueueStateEntity(
                songIdsJson = "[1,2]",
                naturalSongIdsJson = "[1,2]",
                currentIndex = 1,
                isShuffled = false,
                groupingOrdinal = Grouping.entries.size + 7,
                repeatModeOrdinal = 0,
                updatedAt = 0,
            ),
        )

        val state = store.load()

        assertEquals(listOf(1L, 2L), state?.songIds)
        assertEquals(1, state?.currentIndex)
        assertNull(state?.grouping)
    }

    // Unreadable JSON means there is no queue to resume, which every caller already handles --
    // DashtoneMediaLibraryService seeds resumption from a fire-and-forget launch whose
    // CoroutineExceptionHandler would otherwise swallow the throw and leave no trace.
    @Test
    fun `an unreadable queue reads back as nothing to resume`() = runTest {
        dao.upsert(
            QueueStateEntity(
                songIdsJson = "not json",
                naturalSongIdsJson = "[1]",
                currentIndex = 0,
                isShuffled = false,
                groupingOrdinal = null,
                repeatModeOrdinal = 0,
                updatedAt = 0,
            ),
        )

        assertNull(store.load())
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
