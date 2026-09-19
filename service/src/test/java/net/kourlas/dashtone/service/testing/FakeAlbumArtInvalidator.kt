package net.kourlas.dashtone.service.testing

import net.kourlas.dashtone.service.albumart.AlbumArtInvalidator

/** Records how often the artwork cache was dropped, so tests can pin down which scans invalidate
 * it. See LibraryRepository.rescan for the rule being exercised. */
class FakeAlbumArtInvalidator : AlbumArtInvalidator {
    var clearCount = 0
        private set

    override fun clear() {
        clearCount++
    }
}
