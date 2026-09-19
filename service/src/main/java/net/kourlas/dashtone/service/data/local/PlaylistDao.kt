package net.kourlas.dashtone.service.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_entries ON playlist_entries.songId = songs.id
        WHERE playlist_entries.playlistId = :playlistId
        ORDER BY playlist_entries.position ASC
        """,
    )
    fun observeSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT id FROM playlists")
    suspend fun getAllIds(): List<Long>

    /** Every playlist row, so a scan can compare stored [PlaylistEntity.dateModified] against what
     * is on disk before deciding whether to re-read the file. */
    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<PlaylistEntity>

    /** Playlists that already have resolved entries. A playlist whose file could not be read comes
     * out of a scan with none, and re-reading it is the only way it ever gets any. */
    @Query("SELECT DISTINCT playlistId FROM playlist_entries")
    suspend fun idsWithEntries(): List<Long>

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    /** Replaces a playlist's whole track list atomically, since file-backed order can't be
     * diffed/merged meaningfully -- every scan just re-derives it from scratch. */
    @Transaction
    suspend fun replaceEntries(playlistId: Long, entries: List<PlaylistEntryEntity>) {
        deleteEntries(playlistId)
        insertEntries(entries)
    }
}
