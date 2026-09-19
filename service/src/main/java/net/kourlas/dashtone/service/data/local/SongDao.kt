package net.kourlas.dashtone.service.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber ASC, trackNumber ASC")
    fun observeForAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artistId = :artistId ORDER BY title COLLATE NOCASE ASC")
    fun observeForArtist(artistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    /** Any one song from the album, used as the source file for embedded-artwork extraction. */
    @Query("SELECT * FROM songs WHERE albumId = :albumId LIMIT 1")
    suspend fun getAnyForAlbum(albumId: Long): SongEntity?

    @Query("SELECT id FROM songs")
    suspend fun getAllIds(): List<Long>

    /** Counting in SQL keeps the browse tree from loading every row just to report a list size --
     * see [net.kourlas.dashtone.service.playback.LibraryBrowseTree.childCount]. */
    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    /** The distinct values of the denormalized category fields (see [SongEntity.genreName]), which
     * build the Genres/Album Artists/Composers lists and their counts. NULL arrives as a value of
     * its own and the caller folds it into the "unknown" bucket, matching the full-row path. */
    @Query("SELECT DISTINCT genreName FROM songs")
    suspend fun distinctGenreNames(): List<String?>

    @Query("SELECT DISTINCT albumArtistName FROM songs")
    suspend fun distinctAlbumArtistNames(): List<String?>

    @Query("SELECT DISTINCT composerName FROM songs")
    suspend fun distinctComposerNames(): List<String?>

    @Query("SELECT DISTINCT folderPath FROM songs WHERE folderPath IS NOT NULL")
    suspend fun distinctFolderPaths(): List<String>

    /** Songs filed directly at [folderPath], rather than in one of its subfolders. */
    @Query("SELECT COUNT(*) FROM songs WHERE folderPath = :folderPath")
    suspend fun countInFolder(folderPath: String): Int

    /** Album years reachable from a song, which is what the Decades list is built from -- an album
     * with no songs contributes no decade. */
    @Query("SELECT DISTINCT albums.year FROM songs INNER JOIN albums ON songs.albumId = albums.id")
    suspend fun distinctAlbumYears(): List<Int?>

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
