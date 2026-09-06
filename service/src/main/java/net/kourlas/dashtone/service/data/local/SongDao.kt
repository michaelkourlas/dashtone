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

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
