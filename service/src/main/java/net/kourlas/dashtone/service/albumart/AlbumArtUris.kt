package net.kourlas.dashtone.service.albumart

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri

/** Content URIs for [AlbumArtContentProvider], served cross-process to the car head unit. */
object AlbumArtUris {

    /** Derived from the running app's own package rather than written out, so it keeps matching
     * the manifest -- which declares this provider as `${applicationId}.albumart`, resolved when
     * :service's manifest is merged into the app's. A literal here would be a second copy of the
     * applicationId to keep in step, and would collide on install the moment a variant carried an
     * applicationIdSuffix, since two apps cannot share a provider authority. */
    fun authority(context: Context): String = "${context.packageName}.albumart"

    fun forAlbum(context: Context, albumId: Long): Uri =
        ContentUris.withAppendedId("content://${authority(context)}".toUri(), albumId)

    fun albumIdOrNull(context: Context, uri: Uri): Long? =
        if (uri.authority == authority(context)) uri.lastPathSegment?.toLongOrNull() else null
}
