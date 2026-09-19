package net.kourlas.dashtone.service.albumart

/**
 * The one piece of [AlbumArtCache] a library re-sync needs: dropping derived artwork so the next
 * request re-extracts it. Split out as its own interface, the same way [AlbumArtExtractor] is, so
 * [net.kourlas.dashtone.service.data.repository.LibraryRepository] depends on the capability alone
 * and its tests stay plain-JVM, free of the Android context a real cache directory needs.
 */
fun interface AlbumArtInvalidator {
    fun clear()
}
