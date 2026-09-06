package net.kourlas.dashtone.service.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Singleton row (id is always [SINGLETON_ID]) holding the entire current playback queue -- there
 * is exactly one queue at any given time, regardless of whether it came from a root "Shuffle
 * Albums" sentinel, browsing into one album, or any other queue-establishing action; this is what
 * every one of them persists to, and what [net.kourlas.dashtone.service.playback.DashtoneLibrarySessionCallback.onPlaybackResumption]
 * restores from, rather than replaying just the last-played song.
 *
 * [songIdsJson] is whatever's actually loaded right now, in whatever order (shuffled or not).
 * [naturalSongIdsJson] is the equivalent unshuffled ordering -- equal to [songIdsJson] whenever
 * [isShuffled] is false, and otherwise the only way to know what "turn shuffle off" should restore,
 * since an ad-hoc queue (one album, one playlist, a scoped "Shuffle Songs") has no
 * [net.kourlas.dashtone.service.shuffle.ShuffleSettings] combination a natural order could be
 * regenerated from the way a root shuffle preset's could. [groupingOrdinal] is null for a queue
 * with no category structure to speak of (a single album/playlist/song, a search result, a scoped
 * "Shuffle Songs" flat list). */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val songIdsJson: String,
    val naturalSongIdsJson: String,
    val currentIndex: Int,
    val isShuffled: Boolean,
    val groupingOrdinal: Int?,
    val repeatModeOrdinal: Int,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
