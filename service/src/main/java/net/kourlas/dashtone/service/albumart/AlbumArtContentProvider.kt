package net.kourlas.dashtone.service.albumart

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.kourlas.dashtone.service.data.local.SongDao

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AlbumArtProviderEntryPoint {
    fun albumArtCache(): AlbumArtCache
    fun albumArtExtractor(): AlbumArtExtractor
    fun songDao(): SongDao
}

/**
 * Serves cached album art across process boundaries -- the car head unit's Media Center runs as a
 * separate app/process and can only load [android.media.MediaMetadata.artworkUri] via a real
 * content:// provider, not a raw file path or in-memory bitmap.
 *
 * Not a standard @AndroidEntryPoint target: ContentProviders can be created before the Hilt
 * Application component has finished attaching, so dependencies are pulled via
 * [EntryPointAccessors] in [onCreate] instead of field injection.
 */
class AlbumArtContentProvider : ContentProvider() {

    private lateinit var cache: AlbumArtCache
    private lateinit var extractor: AlbumArtExtractor
    private lateinit var songDao: SongDao

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val entryPoint = EntryPointAccessors.fromApplication(appContext, AlbumArtProviderEntryPoint::class.java)
        cache = entryPoint.albumArtCache()
        extractor = entryPoint.albumArtExtractor()
        songDao = entryPoint.songDao()
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val albumId = AlbumArtUris.albumIdOrNull(uri) ?: return null
        val file = cache.file(albumId)
        if (!file.exists()) {
            val bytes = runBlocking {
                val song = songDao.getAnyForAlbum(albumId) ?: return@runBlocking null
                extractor.extract(albumId, song.contentUri.toUri())
            } ?: return null
            file.writeBytes(bytes)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    // Pure file-serving provider: browsing/CRUD via a Cursor is not supported.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
