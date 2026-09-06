package net.kourlas.dashtone.service.playback

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kourlas.dashtone.service.data.local.QueueStateDao
import net.kourlas.dashtone.service.data.local.QueueStateEntity
import net.kourlas.dashtone.service.shuffle.Grouping
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(
    val songIds: List<Long>,
    val naturalSongIds: List<Long>,
    val currentIndex: Int,
    val isShuffled: Boolean,
    val grouping: Grouping?,
    val repeatModeOrdinal: Int,
)

/** Persists the single current playback queue -- see [QueueStateEntity]'s doc comment for why
 * there's only ever one, regardless of whether it came from a root sentinel, browsing into an
 * album, or a scoped "Shuffle Songs" tap. Written every time a new queue is established and on
 * every subsequent transition/repeat-mode/shuffle change; read back by
 * [DashtoneLibrarySessionCallback.onPlaybackResumption] to restore the whole thing on a fresh
 * connection, not just replay the last-played song. */
@Singleton
class QueueStateStore @Inject constructor(
    private val queueStateDao: QueueStateDao,
) {
    suspend fun save(
        songIds: List<Long>,
        naturalSongIds: List<Long>,
        currentIndex: Int,
        isShuffled: Boolean,
        grouping: Grouping?,
        repeatModeOrdinal: Int,
    ) {
        queueStateDao.upsert(
            QueueStateEntity(
                songIdsJson = encodeIds(songIds),
                naturalSongIdsJson = encodeIds(naturalSongIds),
                currentIndex = currentIndex,
                isShuffled = isShuffled,
                groupingOrdinal = grouping?.ordinal,
                repeatModeOrdinal = repeatModeOrdinal,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun load(): QueueState? {
        val entity = queueStateDao.get() ?: return null
        return QueueState(
            songIds = decodeIds(entity.songIdsJson),
            naturalSongIds = decodeIds(entity.naturalSongIdsJson),
            currentIndex = entity.currentIndex,
            isShuffled = entity.isShuffled,
            grouping = entity.groupingOrdinal?.let { Grouping.entries[it] },
            repeatModeOrdinal = entity.repeatModeOrdinal,
        )
    }

    private fun encodeIds(ids: List<Long>): String = Json.encodeToString(ids)
    private fun decodeIds(json: String): List<Long> = Json.decodeFromString(json)
}
