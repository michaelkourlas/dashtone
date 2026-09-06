package net.kourlas.dashtone.service.data

import androidx.annotation.StringRes
import net.kourlas.dashtone.service.R

/**
 * A top-level browsable category. Declaration order is the default ordering used when a user has
 * no stored preference yet (see [net.kourlas.dashtone.service.data.prefs.AppPreferences.categoryOrder])
 * and also where any newly added category lands until the user moves it -- so append new
 * categories at the end. Lives at the top of the `data` package, rather than in `playback`
 * alongside [net.kourlas.dashtone.service.playback.LibraryBrowseTree] which is its main consumer,
 * because [net.kourlas.dashtone.service.data.prefs.AppPreferences] (lower in the dependency graph)
 * needs it too, to persist the user's chosen order/enabled set.
 */
enum class BrowseCategory(@StringRes val displayNameRes: Int) {
    ALBUMS(R.string.category_albums),
    ARTISTS(R.string.category_artists),
    SONGS(R.string.category_songs),
    PLAYLISTS(R.string.category_playlists),
    GENRES(R.string.category_genres),
    ALBUM_ARTISTS(R.string.category_album_artists),
    COMPOSERS(R.string.category_composers),
    DECADES(R.string.category_decades),
    FOLDERS(R.string.category_folders),
}
