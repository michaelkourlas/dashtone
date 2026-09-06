package net.kourlas.dashtone.service.shuffle

import kotlinx.coroutines.flow.first
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class ShuffleQueue(val settings: ShuffleSettings, val songs: List<SongEntity>)

/**
 * Computes ordered song queues for [ShuffleSettings] combinations. Purely a computation utility --
 * there's no persisted "shuffle state" of its own to own: the single current playback queue
 * (whatever its origin -- a preset here, or an ad-hoc browsed/scoped queue built elsewhere) is
 * persisted uniformly by [net.kourlas.dashtone.service.playback.QueueStateStore], regardless of
 * which mechanism built it.
 */
@Singleton
class ShuffleController @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    suspend fun apply(settings: ShuffleSettings): ShuffleQueue {
        val songs = libraryRepository.songs.first()
        val albums = libraryRepository.albums.first()
        val artists = libraryRepository.artists.first()
        val ordered = queueBuilderFor(settings).build(Random.nextLong(), songs, albums, artists)
        return ShuffleQueue(settings, ordered)
    }

    /** What tapping "Shuffle Albums"/"Shuffle Artists"/etc. actually does: [grouping] is fixed by
     * which browse-tree entry was tapped, but WHICH axis (category order, in-category song order,
     * or both) gets randomized is a user preference applying uniformly across every one of those
     * entries -- see [CategoryShuffleMode]'s doc comment for why that's a single setting rather
     * than a separate browse-tree entry per combination. */
    suspend fun applyCategoryShuffle(grouping: Grouping): ShuffleQueue {
        val mode = libraryRepository.categoryShuffleMode.first()
        return apply(ShuffleSettings(grouping = grouping, shuffleCategories = mode.shuffleCategories, shuffleSongs = mode.shuffleSongs))
    }

    /** The unshuffled counterpart of [settings] (natural album/track order, or alphabetical for
     * [ShuffleSettings.shuffleAll]) -- used as the live shuffle toggle's off-state fallback for a
     * shuffle-preset queue. */
    suspend fun naturalOrderFor(settings: ShuffleSettings): List<SongEntity> {
        val songs = libraryRepository.songs.first()
        if (settings.shuffleAll) return songs
        val albums = libraryRepository.albums.first()
        val artists = libraryRepository.artists.first()
        val naturalSettings = settings.copy(shuffleCategories = false, shuffleSongs = false)
        return GroupedQueueBuilder(naturalSettings).build(seed = 0, songs, albums, artists)
    }
}
