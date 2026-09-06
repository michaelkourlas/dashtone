package net.kourlas.dashtone.service.shuffle

import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import kotlin.random.Random

// Also used by LibraryBrowseTree's Genres/Album Artists tabs, so the same bucket a song falls
// into for shuffling is the same one it's filed under when browsing.
internal const val UNKNOWN_GENRE = "Unknown Genre"
internal const val UNKNOWN_ALBUM_ARTIST = "Unknown Album Artist"
internal const val UNKNOWN_COMPOSER = "Unknown Composer"

/**
 * The single mechanism behind every [ShuffleSettings] combination except [ShuffleAllQueueBuilder]:
 * groups songs by [ShuffleSettings.grouping], orders the categories (alphabetically or shuffled),
 * and orders each category's songs (by track number or shuffled) -- independently, per
 * [ShuffleSettings.shuffleCategories] and [ShuffleSettings.shuffleSongs]. The natural/"off" order
 * is just this builder with both toggles false.
 */
class GroupedQueueBuilder(private val settings: ShuffleSettings) : ShuffleQueueBuilder {

    override fun build(
        seed: Long,
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ): List<SongEntity> {
        val random = Random(seed)
        // Only Grouping.ALBUM's own categories are ever a single album's worth of songs; every
        // other grouping (artist, genre, album artist, composer) can span several albums per
        // category, so the un-shuffled within-category order needs to sort by album first --
        // see trackOrderWithin's own doc comment for why.
        val albumNameById = albums.associate { it.id to it.name }
        val trackOrder = trackOrderWithin(albumNameById)
        return when (settings.grouping) {
            Grouping.ALBUM -> buildGroups(
                songs = songs,
                naturalCategoryOrder = albums.sortedBy { it.name.lowercase() }.map { it.id },
                categoryOf = SongEntity::albumId,
                trackOrder = trackOrder,
                random = random,
            )

            Grouping.ARTIST -> buildGroups(
                songs = songs,
                naturalCategoryOrder = artists.sortedBy { it.name.lowercase() }.map { it.id },
                categoryOf = SongEntity::artistId,
                trackOrder = trackOrder,
                random = random,
            )

            // No separate Genre/AlbumArtist entity+DAO exists (see SongEntity.genreName's doc
            // comment), so these group directly by the song's own denormalized string field
            // rather than by a foreign-key id like Album/Artist do.
            Grouping.GENRE -> buildGroups(
                songs = songs,
                naturalCategoryOrder = songs.map { it.genreName ?: UNKNOWN_GENRE }.distinct().sorted(),
                categoryOf = { it.genreName ?: UNKNOWN_GENRE },
                trackOrder = trackOrder,
                random = random,
            )

            Grouping.ALBUM_ARTIST -> buildGroups(
                songs = songs,
                naturalCategoryOrder = songs.map { it.albumArtistName ?: UNKNOWN_ALBUM_ARTIST }.distinct().sorted(),
                categoryOf = { it.albumArtistName ?: UNKNOWN_ALBUM_ARTIST },
                trackOrder = trackOrder,
                random = random,
            )

            Grouping.COMPOSER -> buildGroups(
                songs = songs,
                naturalCategoryOrder = songs.map { it.composerName ?: UNKNOWN_COMPOSER }.distinct().sorted(),
                categoryOf = { it.composerName ?: UNKNOWN_COMPOSER },
                trackOrder = trackOrder,
                random = random,
            )
        }
    }

    private fun <K> buildGroups(
        songs: List<SongEntity>,
        naturalCategoryOrder: List<K>,
        categoryOf: (SongEntity) -> K,
        trackOrder: Comparator<SongEntity>,
        random: Random,
    ): List<SongEntity> {
        val songsByCategory = songs.groupBy(categoryOf)
        val orderedCategoryIds =
            if (settings.shuffleCategories) naturalCategoryOrder.shuffled(random) else naturalCategoryOrder
        return orderedCategoryIds.flatMap { categoryId ->
            val categorySongs = songsByCategory[categoryId].orEmpty()
            if (settings.shuffleSongs) categorySongs.shuffled(random) else categorySongs.sortedWith(trackOrder)
        }
    }
}

/** The un-shuffled within-category song order: sorted by album name first, then disc/track number
 * within that album. Sorting by disc/track number alone is only meaningful within a single album
 * -- for any category that can span several albums (artist, genre, album artist, composer), doing
 * that without grouping by album first interleaves every album's "track 1"s together, then every
 * "track 2"s, and so on, regardless of which album each one actually belongs to. Sorting by album
 * name first is a no-op for [Grouping.ALBUM] itself (every song in one of its categories already
 * shares the same album), so this one comparator is correct for every grouping, not just the
 * multi-album ones. */
private fun trackOrderWithin(albumNameById: Map<Long, String>): Comparator<SongEntity> =
    compareBy({ albumNameById[it.albumId]?.lowercase().orEmpty() }, { it.discNumber }, { it.trackNumber })

/** [ShuffleSettings.shuffleAll]: every song shuffled into one flat order, ignoring category
 * boundaries entirely -- genuinely different from shuffling both grouping axes, which still plays
 * one category to completion before moving to the next. */
object ShuffleAllQueueBuilder : ShuffleQueueBuilder {
    override fun build(
        seed: Long,
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ): List<SongEntity> = songs.shuffled(Random(seed))
}

fun queueBuilderFor(settings: ShuffleSettings): ShuffleQueueBuilder =
    if (settings.shuffleAll) ShuffleAllQueueBuilder else GroupedQueueBuilder(settings)
