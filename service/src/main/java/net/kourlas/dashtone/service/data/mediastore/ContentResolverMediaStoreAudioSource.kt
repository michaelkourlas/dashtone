package net.kourlas.dashtone.service.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import net.kourlas.dashtone.service.R
import javax.inject.Inject

class ContentResolverMediaStoreAudioSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStoreAudioSource {

    // RELATIVE_PATH was introduced in API 29 (scoped storage) -- the column doesn't exist in the
    // underlying MediaStore database on API 28, so including it in the projection there would
    // throw at query time, not just produce a null value.
    private val supportsRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // GENRE became a directly queryable column on Audio.Media in API 30; before that, genre
    // membership only existed via the separate Audio.Genres/Genres.Members join tables.
    // ALBUM_ARTIST has been part of AudioColumns since API 1, so it needs no such gating.
    private val supportsGenreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun queryAllAudio(): List<RawAudioRow> {
        Log.w("DashtoneDiag", "queryAllAudio: permission=${AudioPermission.PERMISSION} granted=${AudioPermission.isGranted(context)}")
        if (!AudioPermission.isGranted(context)) return emptyList()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.ALBUM_ARTIST)
            add(MediaStore.Audio.Media.COMPOSER)
            if (supportsRelativePath) add(MediaStore.Audio.Media.RELATIVE_PATH)
            if (supportsRelativePath) add(MediaStore.Audio.Media.VOLUME_NAME)
            if (supportsGenreColumn) add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.IS_MUSIC)
        }.toTypedArray()

        val rows = mutableListOf<RawAudioRow>()
        val cursorResult = try {
            context.contentResolver.query(collection, projection, null, null, null)
        } catch (e: Exception) {
            Log.e("DashtoneDiag", "queryAllAudio: query() threw", e)
            throw e
        }
        Log.w("DashtoneDiag", "queryAllAudio: cursor=${cursorResult} count=${cursorResult?.count}")
        cursorResult?.use { cursor ->
            // _ID and TITLE are the two columns every MediaStore audio row is fundamentally
            // defined by (present since API 1 on every Android device, stock or OEM-forked) --
            // everything else below uses getColumnIndex (-1 on a missing column) rather than
            // getColumnIndexOrThrow, the same defensive pattern already used for the API-gated
            // columns further down. A car head unit's MediaStore is frequently a heavily
            // customized OEM fork, not stock AOSP, and isn't guaranteed to expose every column
            // this projection asks for (COMPOSER and ALBUM_ARTIST are the least certain) -- a
            // provider that trims one of them should degrade that one field to null, not take
            // down the entire scan.
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val composerCol = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
            val relativePathCol = if (supportsRelativePath) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            // VOLUME_NAME was introduced alongside RELATIVE_PATH in API 29 -- same gating.
            val volumeNameCol = if (supportsRelativePath) {
                cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
            } else {
                -1
            }
            val genreCol = if (supportsGenreColumn) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }
            val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)

            while (cursor.moveToNext()) {
                // Filtered here in Kotlin, not via a "IS_MUSIC != 0" SQL selection string: passing
                // that to ContentResolver.query() against a real device (not the shell's own
                // content query tool, which isn't bound by the same restriction) silently returned
                // zero rows -- MediaProvider's public query path evidently doesn't execute
                // selection text the same way for a third-party app's selection argument as it
                // does for a privileged shell-issued query, which the exact same selection string
                // succeeding via `adb shell content query` but failing from here confirmed live.
                // IS_MUSIC also starts out NULL immediately after a file is first scanned, only
                // becoming 0/1 once MediaStore's separate asynchronous metadata-extraction pass
                // gets around to classifying it (also confirmed live -- a freshly seeded emulator
                // reports IS_MUSIC and DURATION both NULL, with only MIME_TYPE populated, for files
                // that were genuinely just added), so treating NULL/missing as "assume music" here
                // is required, not just a convenience -- this query already only reaches
                // Audio.Media, which is audio-only by definition, so there's nothing unsafe about
                // including a not-yet-classified row.
                if (isMusicCol >= 0 && !cursor.isNull(isMusicCol) && cursor.getInt(isMusicCol) == 0) continue
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: continue
                val year = cursor.getIntOrNull(yearCol)?.takeIf { it > 0 }
                val contentUri = ContentUris.withAppendedId(collection, id).toString()
                rows += RawAudioRow(
                    id = id,
                    title = title,
                    artistId = cursor.getLongOrNull(artistIdCol) ?: 0L,
                    artistName = cursor.getStringOrNull(artistCol) ?: unknownArtist,
                    albumId = cursor.getLongOrNull(albumIdCol) ?: 0L,
                    albumName = cursor.getStringOrNull(albumCol) ?: unknownAlbum,
                    albumYear = year,
                    rawTrack = cursor.getIntOrNull(trackCol) ?: 0,
                    durationMs = cursor.getLongOrNull(durationCol) ?: 0L,
                    // MediaStore stores DATE_ADDED/DATE_MODIFIED in epoch seconds.
                    dateAdded = (cursor.getLongOrNull(dateAddedCol) ?: 0L) * 1000L,
                    dateModified = (cursor.getLongOrNull(dateModifiedCol) ?: 0L) * 1000L,
                    contentUri = contentUri,
                    filePath = cursor.getStringOrNull(dataCol),
                    relativePath = cursor.getStringOrNull(relativePathCol),
                    volumeName = cursor.getStringOrNull(volumeNameCol),
                    genreName = cursor.getStringOrNull(genreCol),
                    albumArtistName = cursor.getStringOrNull(albumArtistCol),
                    composerName = cursor.getStringOrNull(composerCol),
                )
            }
        }
        Log.w("DashtoneDiag", "queryAllAudio: returning ${rows.size} rows")
        return rows
    }

    private fun Cursor.getStringOrNull(columnIndex: Int): String? =
        if (columnIndex < 0 || isNull(columnIndex)) null else getString(columnIndex)

    private fun Cursor.getLongOrNull(columnIndex: Int): Long? =
        if (columnIndex < 0 || isNull(columnIndex)) null else getLong(columnIndex)

    private fun Cursor.getIntOrNull(columnIndex: Int): Int? =
        if (columnIndex < 0 || isNull(columnIndex)) null else getInt(columnIndex)

    private val unknownArtist get() = context.getString(R.string.unknown_artist)
    private val unknownAlbum get() = context.getString(R.string.unknown_album)
}
