package net.kourlas.dashtone.service.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT id FROM artists")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun count(): Int

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: Long): ArtistEntity?

    @Upsert
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Removes artists left with no referencing songs, e.g. after [SongDao.deleteByIds]. */
    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM songs)")
    suspend fun deleteOrphans()
}
