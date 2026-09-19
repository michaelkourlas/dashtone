package net.kourlas.dashtone.service.albumart

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** On-disk cache of extracted/decoded album art, one JPEG per album id. */
@Singleton
class AlbumArtCache @Inject constructor(@ApplicationContext context: Context) : AlbumArtInvalidator {
    private val directory = File(context.cacheDir, "album_art")

    fun file(albumId: Long): File {
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "$albumId.jpg")
    }

    /** Publishes [bytes] as the cached image for [albumId], returning the file to serve, or null
     * if it could not be written.
     *
     * The bytes land in a uniquely named temp file first and are moved into place with a rename,
     * which is atomic within a single directory: a reader opening this album's art sees either the
     * previous complete file or this one. That matters because a car host filling an album grid
     * issues many parallel openFile() calls, several of which can find the same album uncached and
     * extract it at once -- each publishes its own temp file, the last rename wins, and every
     * reader has a complete JPEG throughout. Writing straight to the served path instead would let
     * two writers interleave into a truncated image, which the exists() check would then treat as
     * a valid cache entry forever. */
    fun write(albumId: Long, bytes: ByteArray): File? {
        val target = file(albumId)
        val temp = File(directory, "$albumId-${UUID.randomUUID()}.tmp")
        return try {
            temp.writeBytes(bytes)
            if (temp.renameTo(target)) target else null
        } catch (e: IOException) {
            null
        } finally {
            temp.delete()
        }
    }

    /** Drops every cached image, so the next request for each album re-extracts it. Artwork is
     * keyed by album id, which survives a re-tag, so replacing the art on disk leaves the id
     * untouched and this whole-directory clear is what picks the new image up. */
    override fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
