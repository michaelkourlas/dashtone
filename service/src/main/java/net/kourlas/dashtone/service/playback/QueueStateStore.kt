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

    /** Null when there is nothing resumable, including when the stored row can no longer be read
     * back. Every caller already treats null as "nothing to resume" and carries on, which is the
     * right outcome here too: the alternative is throwing from a fire-and-forget serviceScope
     * launch (see DashtoneMediaLibraryService.onCreate), where the CoroutineExceptionHandler
     * swallows it and resumption stops working with no other trace. [AppPreferences] degrades an
     * unreadable [net.kourlas.dashtone.service.shuffle.CategoryShuffleMode] the same way.
     *
     * A [groupingOrdinal] outside the enum degrades to a null grouping -- the queue still restores,
     * it just loses double-tap category skipping. [Grouping] is persisted by ordinal, so this is
     * what a reordered or removed constant would produce; its own doc comment asks that new
     * groupings be appended for exactly that reason. */
    suspend fun load(): QueueState? {
        val entity = queueStateDao.get() ?: return null
        return QueueState(
            songIds = decodeIds(entity.songIdsJson) ?: return null,
            naturalSongIds = decodeIds(entity.naturalSongIdsJson) ?: return null,
            currentIndex = entity.currentIndex,
            isShuffled = entity.isShuffled,
            grouping = entity.groupingOrdinal?.let { Grouping.entries.getOrNull(it) },
            repeatModeOrdinal = entity.repeatModeOrdinal,
        )
    }

    private fun encodeIds(ids: List<Long>): String = Json.encodeToString(ids)

    private fun decodeIds(json: String): List<Long>? =
        runCatching { Json.decodeFromString<List<Long>>(json) }.getOrNull()
}
