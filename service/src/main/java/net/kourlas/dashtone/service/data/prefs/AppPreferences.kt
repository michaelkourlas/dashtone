package net.kourlas.dashtone.service.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small scalar app state. The [DataStore] is injected rather than derived from a Context
 * internally, so this class can be unit-tested with an in-memory fake store on the plain JVM.
 */
@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val hasCompletedFirstRun: Flow<Boolean> =
        dataStore.data.map { it[HAS_COMPLETED_FIRST_RUN] ?: false }

    suspend fun setHasCompletedFirstRun(value: Boolean) {
        dataStore.edit { it[HAS_COMPLETED_FIRST_RUN] = value }
    }

    /** Timestamp of the most recent scan, whatever triggered it -- an automatic background
     * catch-up (see [net.kourlas.dashtone.service.playback.LibraryChangeObserver] and
     * [net.kourlas.dashtone.service.playback.LibraryBrowseTree.root]) or an explicit user-requested
     * rescan. Every scan re-syncs the library completely, so there's no separate "full scan"
     * timestamp to track. */
    val lastScanTimestampMillis: Flow<Long> =
        dataStore.data.map { it[LAST_SCAN_TIMESTAMP_MILLIS] ?: 0L }

    suspend fun setLastScanTimestampMillis(value: Long) {
        dataStore.edit { it[LAST_SCAN_TIMESTAMP_MILLIS] = value }
    }

    /** Top-level folder names (see [net.kourlas.dashtone.service.data.mediastore.topLevelFolderOf])
     * to leave out of the scanned library, e.g. a misclassified Podcasts folder living alongside
     * Music on the same volume. */
    val excludedFolders: Flow<Set<String>> =
        dataStore.data.map { it[EXCLUDED_FOLDERS] ?: emptySet() }

    suspend fun setExcludedFolders(value: Set<String>) {
        dataStore.edit { it[EXCLUDED_FOLDERS] = value }
    }

    /** Full ordering of every [BrowseCategory], user-customizable in Settings. Any category not
     * yet present in the stored value (e.g. one added in a later app version) is appended at the
     * end in declaration order, so it's still reachable rather than silently missing. */
    val categoryOrder: Flow<List<BrowseCategory>> = dataStore.data.map { prefs ->
        val stored = prefs[CATEGORY_ORDER]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { name -> BrowseCategory.entries.find { it.name == name } }
            .orEmpty()
        stored + BrowseCategory.entries.filter { it !in stored }
    }

    suspend fun setCategoryOrder(value: List<BrowseCategory>) {
        dataStore.edit { it[CATEGORY_ORDER] = value.joinToString(",") { category -> category.name } }
    }

    val disabledCategories: Flow<Set<BrowseCategory>> = dataStore.data.map { prefs ->
        prefs[DISABLED_CATEGORIES]
            ?.mapNotNull { name -> BrowseCategory.entries.find { it.name == name } }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setDisabledCategories(value: Set<BrowseCategory>) {
        dataStore.edit { it[DISABLED_CATEGORIES] = value.map { category -> category.name }.toSet() }
    }

    /** What "Shuffle X" means for any category-grouped browse entry -- see
     * [CategoryShuffleMode]'s doc comment. Defaults to [CategoryShuffleMode.CATEGORIES], preserving
     * the only behavior "Shuffle Albums" etc. had before this setting existed, so upgrading the app
     * doesn't change existing behavior out from under anyone. An unrecognized stored value (e.g. a
     * downgrade after a future mode is added) falls back to that same default rather than crashing. */
    val categoryShuffleMode: Flow<CategoryShuffleMode> = dataStore.data.map { prefs ->
        prefs[CATEGORY_SHUFFLE_MODE]
            ?.let { name -> CategoryShuffleMode.entries.find { it.name == name } }
            ?: CategoryShuffleMode.CATEGORIES
    }

    suspend fun setCategoryShuffleMode(value: CategoryShuffleMode) {
        dataStore.edit { it[CATEGORY_SHUFFLE_MODE] = value.name }
    }

    private companion object {
        val HAS_COMPLETED_FIRST_RUN = booleanPreferencesKey("has_completed_first_run")
        val LAST_SCAN_TIMESTAMP_MILLIS = longPreferencesKey("last_scan_timestamp_millis")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val CATEGORY_ORDER = stringPreferencesKey("browse_category_order")
        val DISABLED_CATEGORIES = stringSetPreferencesKey("disabled_browse_categories")
        val CATEGORY_SHUFFLE_MODE = stringPreferencesKey("category_shuffle_mode")
    }
}
