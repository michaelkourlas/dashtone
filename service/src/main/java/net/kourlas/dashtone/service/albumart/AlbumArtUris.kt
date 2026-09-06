package net.kourlas.dashtone.service.albumart

import android.content.ContentUris
import android.net.Uri
import androidx.core.net.toUri

/** Content URIs for [AlbumArtContentProvider], served cross-process to the car head unit. */
object AlbumArtUris {
    const val AUTHORITY = "net.kourlas.dashtone.service.albumart"
    private val BASE_URI: Uri = "content://$AUTHORITY".toUri()

    fun forAlbum(albumId: Long): Uri = ContentUris.withAppendedId(BASE_URI, albumId)

    fun albumIdOrNull(uri: Uri): Long? =
        if (uri.authority == AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null
}
