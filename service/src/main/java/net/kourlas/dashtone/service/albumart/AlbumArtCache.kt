package net.kourlas.dashtone.service.albumart

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** On-disk cache of extracted/decoded album art, one JPEG per album id. */
@Singleton
class AlbumArtCache @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.cacheDir, "album_art")

    fun file(albumId: Long): File {
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "$albumId.jpg")
    }
}
