package net.kourlas.dashtone.service.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY name COLLATE NOCASE ASC")
    fun observeForArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query("SELECT id FROM albums")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun count(): Int

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): AlbumEntity?

    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Removes albums left with no referencing songs, e.g. after [SongDao.deleteByIds]. */
    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM songs)")
    suspend fun deleteOrphans()
}
