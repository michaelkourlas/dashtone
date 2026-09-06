package net.kourlas.dashtone.service.albumart

import android.net.Uri

/** Extracts album art as encoded JPEG bytes, or null if none could be found. */
interface AlbumArtExtractor {
    fun extract(albumId: Long, representativeSongUri: Uri): ByteArray?
}
