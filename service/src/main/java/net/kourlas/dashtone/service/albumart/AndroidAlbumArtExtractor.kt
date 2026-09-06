package net.kourlas.dashtone.service.albumart

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Two-step lookup: MediaStore's own thumbnail first (fast, works for most tagged files), falling
 * back to extracting the embedded picture directly from the file (needed for formats MediaStore
 * doesn't reliably index art for, e.g. some FLAC files). Whatever is found is downscaled and
 * re-encoded to a bounded-size JPEG so cached files stay small and consistent regardless of
 * source.
 */
class AndroidAlbumArtExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlbumArtExtractor {

    override fun extract(albumId: Long, representativeSongUri: Uri): ByteArray? {
        val bitmap = loadFromMediaStoreThumbnail(albumId) ?: loadFromEmbeddedPicture(representativeSongUri)
        return bitmap?.let { encodeJpeg(it.scaledDownTo(MAX_DIMENSION_PX)) }
    }

    private fun loadFromMediaStoreThumbnail(albumId: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val albumArtUri = ContentUris.withAppendedId(
            "content://media/external/audio/albumart".toUri(),
            albumId,
        )
        return try {
            context.contentResolver.loadThumbnail(albumArtUri, Size(MAX_DIMENSION_PX, MAX_DIMENSION_PX), null)
        } catch (e: Exception) {
            // Best-effort: no thumbnail indexed for this album, or the legacy URI isn't backed by
            // a real row. Either way, fall through to embedded-picture extraction.
            null
        }
    }

    private fun loadFromEmbeddedPicture(songUri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, songUri)
            val raw = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (e: Exception) {
            // Best-effort: unreadable file, no embedded picture, or an unsupported codec.
            null
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.scaledDownTo(maxDimensionPx: Int): Bitmap {
        if (width <= maxDimensionPx && height <= maxDimensionPx) return this
        val scale = maxDimensionPx.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }

    private companion object {
        const val MAX_DIMENSION_PX = 512
        const val JPEG_QUALITY = 90
    }
}
