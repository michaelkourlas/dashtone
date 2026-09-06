package net.kourlas.dashtone.service.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

class ContentResolverMediaStorePlaylistSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStorePlaylistSource {

    override fun queryAllPlaylistFiles(): List<RawPlaylistFile> {
        if (!AudioPermission.isGranted(context)) return emptyList()

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // Matched by extension rather than MIME_TYPE: the platform's MIME sniffing for m3u/m3u8
        // files is inconsistent across OEM MediaProvider builds (sometimes audio/mpegurl,
        // sometimes application/octet-stream), while the extension is unambiguous.
        val selection = EXTENSIONS.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = EXTENSIONS.map { "%$it" }.toTypedArray()

        val files = mutableListOf<RawPlaylistFile>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: continue
                val filePath = cursor.getString(dataCol) ?: continue
                files += RawPlaylistFile(
                    id = id,
                    name = displayName.substringBeforeLast('.'),
                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    filePath = filePath,
                    // MediaStore stores DATE_MODIFIED in epoch seconds.
                    dateModified = cursor.getLong(dateModifiedCol) * 1000L,
                )
            }
        }
        return files
    }

    override fun readText(contentUri: String): String? = try {
        context.contentResolver.openInputStream(contentUri.toUri())?.bufferedReader()?.use { it.readText() }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        val EXTENSIONS = listOf(".m3u", ".m3u8")
    }
}
