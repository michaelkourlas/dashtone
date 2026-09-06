package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import net.kourlas.dashtone.service.shuffle.Grouping

/**
 * Pure group-boundary logic behind [QueueShufflePlayer]'s double-tap category skip, kept separate
 * so it's directly unit-testable against a plain list of [MediaItem]s rather than a real [Player].
 */
internal object QueueGroupNavigation {

    /** The index of the first item of the next group (forward) or the first item of the previous
     * group (backward), relative to [currentIndex] in [items]. Null if there's no such group --
     * [currentIndex] is already in the last/first group, or there are too few items. */
    fun adjacentGroupStartIndex(
        items: List<MediaItem>,
        currentIndex: Int,
        grouping: Grouping,
        forward: Boolean,
    ): Int? {
        if (items.size <= 1 || currentIndex !in items.indices) return null
        val currentKey = groupKeyOf(items[currentIndex], grouping)
        if (forward) {
            return (currentIndex + 1 until items.size).firstOrNull { groupKeyOf(items[it], grouping) != currentKey }
        }
        // Walk back past the whole current group first, then find where THAT group starts.
        var i = currentIndex - 1
        while (i >= 0 && groupKeyOf(items[i], grouping) == currentKey) i--
        if (i < 0) return null
        val previousKey = groupKeyOf(items[i], grouping)
        while (i > 0 && groupKeyOf(items[i - 1], grouping) == previousKey) i--
        return i
    }

    /** Also used by [reorderForShuffleToggle] to group a queue's items by category when
     * (re)shuffling it -- the same notion of "category" a double-tap skip jumps between. */
    fun groupKeyOf(item: MediaItem, grouping: Grouping): String? = when (grouping) {
        Grouping.ALBUM -> item.mediaMetadata.albumTitle?.toString()
        Grouping.ARTIST -> item.mediaMetadata.artist?.toString()
        Grouping.GENRE -> item.mediaMetadata.genre?.toString()
        Grouping.ALBUM_ARTIST -> item.mediaMetadata.albumArtist?.toString()
        Grouping.COMPOSER -> item.mediaMetadata.composer?.toString()
    }
}
