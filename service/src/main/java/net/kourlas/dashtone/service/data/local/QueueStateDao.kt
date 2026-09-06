package net.kourlas.dashtone.service.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface QueueStateDao {
    @Query("SELECT * FROM queue_state WHERE id = 0")
    suspend fun get(): QueueStateEntity?

    @Upsert
    suspend fun upsert(state: QueueStateEntity)
}
