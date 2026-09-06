package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import net.kourlas.dashtone.service.shuffle.Grouping
import javax.inject.Inject

data class ResumptionState(
    /** The queue to actually load, in whatever order it was in (shuffled or not). */
    val items: List<MediaItem>,
    /** [QueueShuffleState.naturalOrder]'s restored value -- equal to [items] when [isShuffled] is
     * false; the live shuffle toggle's off-state fallback otherwise. */
    val naturalItems: List<MediaItem>,
    val startIndex: Int,
    val isShuffled: Boolean,
    val grouping: Grouping?,
    val repeatModeOrdinal: Int,
)

/** Resolves the persisted [QueueStateStore] queue into a resumable [ResumptionState] --
 * [DashtoneLibrarySessionCallback.onPlaybackResumption] and [DashtoneMediaLibraryService]'s
 * proactive queue-seeding both restore the whole queue this way (see [QueueStateEntity]'s doc
 * comment for why there's only ever one), not just the single last-played song. Resumes from the
 * start of the current track rather than a remembered mid-song position: playback position isn't
 * persisted. */
class PlaybackResumptionProvider @Inject constructor(
    private val browseTree: LibraryBrowseTree,
    private val queueStateStore: QueueStateStore,
) {
    suspend fun resumptionState(): ResumptionState? {
        val state = queueStateStore.load() ?: return null
        val items = browseTree.songsByIds(state.songIds)
        if (items.isEmpty()) return null
        // A song deleted since the queue was saved shifts everything after it back by one, same
        // shortfall the old ShuffleController.reconcile() accepted -- clamping into the surviving
        // list's bounds is an approximation, not an attempt to relocate exactly which song was
        // actually playing.
        val startIndex = state.currentIndex.coerceIn(0, items.size - 1)
        val naturalItems = if (state.isShuffled) browseTree.songsByIds(state.naturalSongIds) else items
        return ResumptionState(items, naturalItems, startIndex, state.isShuffled, state.grouping, state.repeatModeOrdinal)
    }
}
