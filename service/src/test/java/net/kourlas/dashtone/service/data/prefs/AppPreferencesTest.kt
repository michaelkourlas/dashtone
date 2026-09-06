package net.kourlas.dashtone.service.data.prefs

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.testing.FakeDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class AppPreferencesTest {

    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        preferences = AppPreferences(FakeDataStore(emptyPreferences()))
    }

    @Test
    fun `defaults to first run not completed and zero scan timestamp`() = runTest {
        assertFalse(preferences.hasCompletedFirstRun.first())
        assertEquals(0L, preferences.lastScanTimestampMillis.first())
        assertEquals(emptySet<String>(), preferences.excludedFolders.first())
    }

    @Test
    fun `defaults category order to declaration order with nothing disabled`() = runTest {
        assertEquals(BrowseCategory.entries.toList(), preferences.categoryOrder.first())
        assertEquals(emptySet<BrowseCategory>(), preferences.disabledCategories.first())
    }

    @Test
    fun `persists a custom category order and disabled set`() = runTest {
        val customOrder = listOf(
            BrowseCategory.PLAYLISTS,
            BrowseCategory.SONGS,
            BrowseCategory.ARTISTS,
            BrowseCategory.ALBUMS,
            BrowseCategory.GENRES,
            BrowseCategory.ALBUM_ARTISTS,
        )

        preferences.setCategoryOrder(customOrder)
        preferences.setDisabledCategories(setOf(BrowseCategory.GENRES))

        // Categories not included in the stored order (e.g. one added in a later app version,
        // like COMPOSERS/DECADES/etc. here) are appended at the end in declaration order -- see
        // categoryOrder's own doc comment.
        val expectedOrder = customOrder + BrowseCategory.entries.filter { it !in customOrder }
        assertEquals(expectedOrder, preferences.categoryOrder.first())
        assertEquals(setOf(BrowseCategory.GENRES), preferences.disabledCategories.first())
    }

    @Test
    fun `persists updated values`() = runTest {
        preferences.setHasCompletedFirstRun(true)
        preferences.setLastScanTimestampMillis(1_700_000_000_000L)
        preferences.setExcludedFolders(setOf("Podcasts", "Audiobooks"))

        assertEquals(true, preferences.hasCompletedFirstRun.first())
        assertEquals(1_700_000_000_000L, preferences.lastScanTimestampMillis.first())
        assertEquals(setOf("Podcasts", "Audiobooks"), preferences.excludedFolders.first())
    }
}
