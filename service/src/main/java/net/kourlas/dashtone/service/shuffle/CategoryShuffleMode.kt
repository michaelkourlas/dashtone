package net.kourlas.dashtone.service.shuffle

/**
 * User-configurable meaning of "Shuffle X" for any category-grouped browse entry (Shuffle
 * Albums/Artists/Genres/Album Artists/Composers) -- set once in Settings, applied uniformly to
 * every one of those entries, since exposing all four [ShuffleSettings.shuffleCategories] x
 * [ShuffleSettings.shuffleSongs] combinations as separate browse-tree entries per category would
 * be five categories x four entries each, far too much for a car UI's browse tree. The fully
 * unshuffled case (both axes off) isn't a mode here -- that's what the separate, always-present
 * "Play All X" entry already is (see LibraryBrowseTree), not a state "Shuffle X" can be in.
 *
 * Ordinal is persisted directly (see [net.kourlas.dashtone.service.data.prefs.AppPreferences]), so
 * append new modes at the end rather than reordering.
 */
enum class CategoryShuffleMode(val shuffleCategories: Boolean, val shuffleSongs: Boolean) {
    /** Category order is randomized; each category's own songs still play in track order. This
     * was "Shuffle Albums" etc.'s only behavior before this setting existed. */
    CATEGORIES(shuffleCategories = true, shuffleSongs = false),

    /** Categories play in their natural order; within each one, songs are shuffled. */
    SONGS(shuffleCategories = false, shuffleSongs = true),

    /** Both axes randomized: category order AND each category's internal song order. */
    BOTH(shuffleCategories = true, shuffleSongs = true),
}
