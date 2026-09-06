package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import net.kourlas.dashtone.service.shuffle.Grouping

/**
 * Shared, session-lifetime (never persisted) state tracking the natural order of whatever queue
 * is currently on the player, so [QueueShufflePlayer] can shuffle/restore it in place when a host
 * invokes the standard Player.setShuffleModeEnabled() command. Written by
 * [DashtoneLibrarySessionCallback] every time a new queue is established (regular browsing, a
 * shuffle-preset queue, or playback resumption); read and updated by [QueueShufflePlayer] on each
 * toggle. Scoped to the current playback session rather than persisted across process death:
 * unlike shuffle *settings* (owned by [net.kourlas.dashtone.service.shuffle.ShuffleController]),
 * which item order the live toggle started from is inherently a property of the current session,
 * not something that should survive a restart.
 *
 * [onShuffleChanged] fires whenever [isShuffled] actually changes, no matter which setter caused
 * it. A real ExoPlayer shuffle-flag change never fires its own event (see [QueueShufflePlayer]'s
 * class doc), so refreshing the shuffle command button's icon has no other trigger to piggyback
 * on -- putting that refresh here, rather than leaving each call site responsible for remembering
 * to also invoke a callback, is what keeps [setShuffled] (a queue-establishing write, not a
 * toggle) from silently leaving a stale icon the way it originally did.
 */
class QueueShuffleState(private val onShuffleChanged: (Boolean) -> Unit = {}) {
    var naturalOrder: List<MediaItem> = emptyList()
        private set
    var isShuffled: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (changed) onShuffleChanged(value)
        }

    /** The grouping the current queue was built with (Shuffle Albums/Artists), if any -- lets
     * [QueueShufflePlayer] find category boundaries for a double-tap skip, and group the queue by
     * category when the live shuffle toggle (re)shuffles it. Null for any queue without that
     * structure: a single album/playlist/song, a search result, or a flat
     * [net.kourlas.dashtone.service.shuffle.ShuffleSettings.shuffleAll] queue. */
    var currentGrouping: Grouping? = null
        private set

    /** Live mirror of [net.kourlas.dashtone.service.data.prefs.AppPreferences.categoryShuffleMode],
     * kept current by a collector in DashtoneMediaLibraryService.onCreate() the same way
     * audioPermissionMonitor.isGranted is. [QueueShufflePlayer.setShuffleModeEnabled] overrides a
     * plain (non-suspend) Player method, so it can't itself await a DataStore read -- this field is
     * what lets it apply the user's configured mode synchronously instead of always falling back to
     * a flat, ungrouped shuffle. */
    var categoryShuffleMode: CategoryShuffleMode = CategoryShuffleMode.CATEGORIES

    /** A freshly browsed queue (album, playlist, single song, resumption): starts unshuffled by
     * definition -- it's already in the order the browse tree or resumption gave it. [grouping] is
     * non-null only for a "Play All X" queue (see LibraryBrowseTree): it's unshuffled too, but
     * still has real category structure a double-tap should be able to skip across, unlike every
     * other caller of this method (a single album/playlist/song/search result has no such
     * structure to speak of). */
    fun setNatural(items: List<MediaItem>, grouping: Grouping? = null) {
        naturalOrder = items
        isShuffled = false
        currentGrouping = grouping
    }

    /** A shuffle-preset queue: the player already has the shuffled list (the framework applies
     * whatever [DashtoneLibrarySessionCallback.onAddMediaItems] returns); [natural] is the
     * equivalent unshuffled ordering the live toggle falls back to if turned off. [grouping] is
     * null for [net.kourlas.dashtone.service.shuffle.ShuffleSettings.shuffleAll] (flat, no category
     * structure to jump between). */
    fun setShuffled(natural: List<MediaItem>, grouping: Grouping?) {
        naturalOrder = natural
        isShuffled = true
        currentGrouping = grouping
    }
}
