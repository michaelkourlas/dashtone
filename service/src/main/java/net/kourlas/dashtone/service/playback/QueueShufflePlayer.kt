package net.kourlas.dashtone.service.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import net.kourlas.dashtone.service.shuffle.CategoryShuffleMode
import net.kourlas.dashtone.service.shuffle.Grouping

/**
 * ExoPlayer's own shuffle can't express "shuffle albums but keep in-album order" (see
 * [net.kourlas.dashtone.service.shuffle.ShuffleController]), so it's kept permanently off. A host's
 * generic shuffle icon calls the standard [Player.setShuffleModeEnabled], which this wrapper
 * intercepts instead of forwarding: it reorders the current queue in place around [state]'s
 * tracked natural order, rather than engaging ExoPlayer's own shuffle order.
 */
@UnstableApi
class QueueShufflePlayer(
    player: Player,
    private val state: QueueShuffleState,
) : ForwardingPlayer(player) {

    // ExoPlayer's own availableCommands computation ties next-item availability to whether
    // there's actually somewhere to go: absent on a single-item queue unless repeat mode allows
    // wrapping, and absent on the LAST item of a multi-item queue once repeat is off. car.media
    // doesn't grey out a command it doesn't have -- it drops the button and reflows the whole
    // row, including repeat and shuffle's position, so every one of those cases visibly shuffled
    // the controls around (repeat toggling on a single-track queue; simply reaching the last song
    // in a list; a queue that's just one song to begin with). Forcing next-item to always be
    // available whenever there's at least one item in the queue -- independent of repeat mode,
    // current position, AND queue size -- keeps the row stable in every case, not just the
    // multi-item ones. This is safe to force even when there's truly nowhere to go:
    // Player.seekToNext()/seekToNextMediaItem() are documented as no-ops in that situation, so
    // advertising the command as available doesn't risk misbehaving on tap, it just makes the
    // button present-but-inert instead of vanishing -- car.media's closest equivalent to "visible
    // but disabled" for a command it doesn't natively grey out. Only a genuinely empty queue (no
    // current item at all, nothing to seek within) still drops it.
    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return if (mediaItemCount > 0) {
            base.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        } else {
            base.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
        }
    }

    // See DashtoneMediaLibraryService.onCreate's comment on the resumption-seeding block: a car
    // host's connection handshake calls prepare() unconditionally, and an EMPTY ExoPlayer (nothing
    // seeded yet -- e.g. a fresh install with no last-played song to resume) advances straight from
    // STATE_IDLE to STATE_ENDED on that call, which the legacy bridge reports as STATE_STOPPED with
    // null metadata: an active-looking mini-player bar with no title, art, or song behind it, even
    // though nothing has ever played. Seeding with a resumption item closes this for a returning
    // user, but there's nothing to seed with the very first time the app is used. Refusing to
    // forward prepare() while the queue is genuinely empty keeps the player at STATE_IDLE (-> no
    // bar) instead, and is a no-op for every real call site: every legitimate prepare() in this
    // codebase always follows a setMediaItems() call, so mediaItemCount is already nonzero by then.
    override fun prepare() {
        if (mediaItemCount == 0) return
        super.prepare()
    }

    override fun getShuffleModeEnabled(): Boolean = state.isShuffled

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == state.isShuffled) return
        val naturalOrder = state.naturalOrder
        if (naturalOrder.size < 2) {
            // Nothing meaningful to reorder (empty or single-item queue); just record the toggle
            // so a later, larger queue picks up the right starting state.
            state.isShuffled = shuffleModeEnabled
            return
        }
        val (reordered, startIndex) = reorderForShuffleToggle(
            naturalOrder,
            currentMediaItem?.mediaId,
            shuffleModeEnabled,
            state.currentGrouping,
            state.categoryShuffleMode,
        )
        state.isShuffled = shuffleModeEnabled
        setMediaItems(reordered, startIndex, currentPosition)
    }

    // Independent timing/state per direction -- a forward double-tap and a backward double-tap
    // must never be paired against each other.
    private val nextDoubleTap = DoubleTapNavigation()
    private val previousDoubleTap = DoubleTapNavigation()

    override fun seekToNext() {
        if (handlePossibleDoubleTap(forward = true)) return
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (handlePossibleDoubleTap(forward = true)) return
        super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (handlePossibleDoubleTap(forward = false)) return
        super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (handlePossibleDoubleTap(forward = false)) return
        super.seekToPreviousMediaItem()
    }

    // Jumps to the start of the next/previous category (album, artist, ...) within the current
    // queue, rather than just the next/previous song -- only meaningful for a queue built from a
    // grouped shuffle preset (state.currentGrouping), where "category" has an actual meaning. The
    // double-tap timing/state decision itself lives in DoubleTapNavigation (see its doc comment
    // for why); this is just the thin Player-facing glue around it.
    private fun handlePossibleDoubleTap(forward: Boolean): Boolean {
        val doubleTap = if (forward) nextDoubleTap else previousDoubleTap
        val outcome = doubleTap.onTap(SystemClock.elapsedRealtime(), currentMediaItemIndex)
        val originIndex = (outcome as? DoubleTapNavigation.Outcome.DoubleTap)?.originIndex ?: return false
        // Second tap: always consumed here, never falls through to a plain seek -- the first
        // tap's single-song skip is already the correct full result for the ungrouped case (no
        // grouping means no further jump to make), and for the grouped case the seekTo below
        // replaces it with the correct one.
        val grouping = state.currentGrouping ?: return true
        if (originIndex !in 0 until mediaItemCount) return true
        val items = (0 until mediaItemCount).map { getMediaItemAt(it) }
        val targetIndex = QueueGroupNavigation.adjacentGroupStartIndex(items, originIndex, grouping, forward) ?: return true
        seekTo(targetIndex, C.TIME_UNSET)
        return true
    }
}

/** The pure reordering decision behind [QueueShufflePlayer.setShuffleModeEnabled], kept free of
 * Player/ForwardingPlayer so it's directly unit-testable. Returns the new queue plus the index
 * within it the currently playing item ([currentMediaId]) should resume at.
 *
 * Turning shuffle ON always returns the playing item at index 0: rebuilding the shuffle pool and
 * merely locating wherever the playing item landed would leave every track that landed BEFORE it
 * permanently unreachable, since playback only ever advances forward from the current index. When
 * [grouping] is non-null, the playing item's own category (its album, artist, ...) leads too, not
 * just the single item -- the rest of that category follows it immediately (in track order, or
 * shuffled per [categoryShuffleMode], exactly like every other category), the same way resuming a
 * partway-through album normally would, rather than scattering the album's remaining tracks
 * wherever its category's turn happens to land among the other, unrelated categories. The other
 * categories then follow, ordered/shuffled the exact same way
 * [net.kourlas.dashtone.service.shuffle.GroupedQueueBuilder] would for a "Shuffle Albums"-style
 * preset -- this is what lets the generic shuffle toggle (which, unlike a preset action, has no
 * direct route to the user's configured mode) still honor it. [grouping] is null for a queue with
 * no category structure to speak of (a single album/playlist/song, a search result), where a flat
 * shuffle of everything but the playing item is the only sensible behavior regardless of
 * [categoryShuffleMode].
 *
 * Turning shuffle OFF just restores [naturalOrder], resuming wherever the playing item naturally
 * falls in it -- unlike the ON case, there's no "unreachable tracks" concern once the queue is
 * back in its real, browsable order. */
internal fun reorderForShuffleToggle(
    naturalOrder: List<MediaItem>,
    currentMediaId: String?,
    shuffleModeEnabled: Boolean,
    grouping: Grouping? = null,
    categoryShuffleMode: CategoryShuffleMode = CategoryShuffleMode.CATEGORIES,
): Pair<List<MediaItem>, Int> {
    if (!shuffleModeEnabled) {
        return naturalOrder to naturalOrder.indexOfFirst { it.mediaId == currentMediaId }.coerceAtLeast(0)
    }
    val pool = if (grouping != null) {
        groupedShuffleLeadingWithCurrent(naturalOrder, grouping, categoryShuffleMode, currentMediaId)
    } else {
        val current = naturalOrder.firstOrNull { it.mediaId == currentMediaId }
        val rest = naturalOrder.filterNot { it.mediaId == currentMediaId }.shuffled()
        if (current != null) listOf(current) + rest else rest
    }
    return pool to 0
}

/** Rebuilds [naturalOrder] the same way
 * [net.kourlas.dashtone.service.shuffle.GroupedQueueBuilder] would (category order and/or
 * in-category order randomized per [mode], grouped by [grouping] via the same
 * [QueueGroupNavigation.groupKeyOf] a double-tap skip uses to find category boundaries), except
 * the playing item's own category is always placed first, led by the playing item itself, so that
 * category's remaining tracks aren't scattered away from the one already playing. Natural category
 * order (for the other categories) is each category's first appearance in [naturalOrder] --
 * already the real order (alphabetical by name, etc.) the browse tree built the queue in, so
 * there's no need to re-derive it from a separate source here. */
private fun groupedShuffleLeadingWithCurrent(
    naturalOrder: List<MediaItem>,
    grouping: Grouping,
    mode: CategoryShuffleMode,
    currentMediaId: String?,
): List<MediaItem> {
    val byCategory = naturalOrder.groupBy { QueueGroupNavigation.groupKeyOf(it, grouping) }
    val naturalCategoryOrder = naturalOrder.map { QueueGroupNavigation.groupKeyOf(it, grouping) }.distinct()
    val currentItem = naturalOrder.firstOrNull { it.mediaId == currentMediaId }
    val currentCategoryKey = currentItem?.let { QueueGroupNavigation.groupKeyOf(it, grouping) }

    val otherCategories = naturalCategoryOrder.filterNot { it == currentCategoryKey }
    val orderedOtherCategories =
        if (mode.shuffleCategories) otherCategories.shuffled() else otherCategories
    val orderedCategories =
        if (currentCategoryKey != null) listOf(currentCategoryKey) + orderedOtherCategories else orderedOtherCategories

    return orderedCategories.flatMap { key ->
        val categoryItems = byCategory[key].orEmpty()
        if (key == currentCategoryKey && currentItem != null) {
            val rest = categoryItems.filterNot { it.mediaId == currentMediaId }
            listOf(currentItem) + if (mode.shuffleSongs) rest.shuffled() else rest
        } else {
            if (mode.shuffleSongs) categoryItems.shuffled() else categoryItems
        }
    }
}
