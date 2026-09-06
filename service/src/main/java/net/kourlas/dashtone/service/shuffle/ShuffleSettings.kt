package net.kourlas.dashtone.service.shuffle

/**
 * The current playback ordering, modeled the same way Poweramp models it: independent toggles
 * rather than a fixed list of named modes. [grouping] selects which category type songs are
 * grouped into, and [shuffleCategories]/[shuffleSongs] independently control whether category
 * order and in-category song order are randomized. "Shuffle albums, play each album in order" is
 * just `grouping = ALBUM, shuffleCategories = true, shuffleSongs = false` -- one parameterization
 * of the general mechanism, not a special case, and the same mechanism gives artist shuffle (and
 * later folder/genre shuffle) for free.
 *
 * [shuffleAll] is a fully flat shuffle that disregards category boundaries entirely -- the next
 * song can come from any category -- which is genuinely different from shuffling both axes above
 * (which still plays one category's songs to completion before moving to the next), so it's kept
 * as a separate flag rather than forced into the grouping model where it doesn't actually fit.
 */
data class ShuffleSettings(
    val shuffleAll: Boolean = false,
    val grouping: Grouping = Grouping.ALBUM,
    val shuffleCategories: Boolean = false,
    val shuffleSongs: Boolean = false,
)
