package net.kourlas.dashtone.service.shuffle

/**
 * Curated quick-access [ShuffleSettings] combinations. The engine itself supports the full
 * grouping x shuffle-categories x shuffle-songs matrix; only [SHUFFLE_ALL] is still wired
 * directly into the browse tree as a one-tap action (see LibraryBrowseTree/
 * DashtoneLibrarySessionCallback) -- the other, grouped entries ("Shuffle Albums" etc.) resolve
 * their actual [ShuffleSettings] dynamically via [ShuffleController.applyCategoryShuffle], since
 * which axis gets shuffled is a user-configurable [CategoryShuffleMode] rather than fixed per
 * category. The constants below remain as readable fixtures for tests exercising
 * [ShuffleController]'s generic apply/naturalOrderFor/currentSettings mechanics, which don't care
 * whether a given [ShuffleSettings] came from a browse-tree tap or a settings-driven combination.
 */
object ShufflePresets {
    val SHUFFLE_ALL = ShuffleSettings(shuffleAll = true)
    val SHUFFLE_ALBUMS = ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true)
    val SHUFFLE_ARTISTS = ShuffleSettings(grouping = Grouping.ARTIST, shuffleCategories = true)
    val SHUFFLE_GENRES = ShuffleSettings(grouping = Grouping.GENRE, shuffleCategories = true)
    val SHUFFLE_ALBUM_ARTISTS = ShuffleSettings(grouping = Grouping.ALBUM_ARTIST, shuffleCategories = true)
    val SHUFFLE_COMPOSERS = ShuffleSettings(grouping = Grouping.COMPOSER, shuffleCategories = true)
}
