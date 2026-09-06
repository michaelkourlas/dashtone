package net.kourlas.dashtone.service.shuffle

import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun song(
    id: Long,
    albumId: Long,
    artistId: Long = 1,
    track: Int,
    disc: Int = 1,
    genreName: String? = null,
    albumArtistName: String? = null,
) = SongEntity(
    id = id,
    title = "Song $id",
    trackNumber = track,
    discNumber = disc,
    durationMs = 200_000,
    albumId = albumId,
    artistId = artistId,
    contentUri = "content://media/external/audio/media/$id",
    dateAdded = 0,
    dateModified = 0,
    genreName = genreName,
    albumArtistName = albumArtistName,
)

private fun album(id: Long, name: String, artistId: Long = 1) =
    AlbumEntity(id = id, name = name, artistId = artistId, year = null, trackCount = 0)

private fun artist(id: Long, name: String) = ArtistEntity(id = id, name = name)

class GroupedQueueBuilderTest {

    @Test
    fun `off (no shuffling) orders albums alphabetically and songs by disc then track`() {
        val albums = listOf(album(2, "Zebra"), album(1, "Apple"))
        val songs = listOf(
            song(id = 10, albumId = 1, track = 2),
            song(id = 11, albumId = 1, track = 1),
            song(id = 20, albumId = 2, track = 1),
        )

        val result = GroupedQueueBuilder(ShuffleSettings()).build(0, songs, albums, emptyList())

        assertEquals(listOf(11L, 10L, 20L), result.map { it.id })
    }

    @Test
    fun `shuffleCategories=true keeps each album's tracks in order but randomizes album order`() {
        val albums = listOf(album(1, "A"), album(2, "B"), album(3, "C"))
        val songs = listOf(
            song(id = 10, albumId = 1, track = 1), song(id = 11, albumId = 1, track = 2),
            song(id = 20, albumId = 2, track = 1), song(id = 21, albumId = 2, track = 2),
            song(id = 30, albumId = 3, track = 1), song(id = 31, albumId = 3, track = 2),
        )
        val settings = ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true)

        val result = GroupedQueueBuilder(settings).build(3, songs, albums, emptyList())

        val positions = result.mapIndexed { index, s -> s.id to index }.toMap()
        for (albumId in listOf(1L, 2L, 3L)) {
            val first = songs.first { it.albumId == albumId && it.trackNumber == 1 }.id
            val second = songs.first { it.albumId == albumId && it.trackNumber == 2 }.id
            assertEquals(positions.getValue(first) + 1, positions.getValue(second))
        }
        assertEquals(6, result.size)
    }

    @Test
    fun `shuffleSongs=true keeps category order but randomizes songs within each category`() {
        val albums = listOf(album(1, "A"), album(2, "B"))
        val songs = (1..20L).map { song(id = it, albumId = if (it <= 10) 1 else 2, track = it.toInt()) }
        val settings = ShuffleSettings(grouping = Grouping.ALBUM, shuffleSongs = true)

        val result = GroupedQueueBuilder(settings).build(1, songs, albums, emptyList())

        // Category order preserved: all of album 1's songs come before album 2's.
        val albumIdSequence = result.map { it.albumId }
        assertEquals(listOf(1L, 2L), albumIdSequence.distinct())
        // But within each category, order should not just be the natural track order.
        val album1Order = result.filter { it.albumId == 1L }.map { it.id }
        assertNotEquals((1..10L).toList(), album1Order)
        assertEquals((1..10L).toSet(), album1Order.toSet())
    }

    @Test
    fun `grouping by artist works the same way grouping by album does`() {
        val artists = listOf(artist(1, "Zeta"), artist(2, "Alpha"))
        val songs = listOf(
            song(id = 10, albumId = 1, artistId = 1, track = 1),
            song(id = 20, albumId = 2, artistId = 2, track = 1),
        )
        val result = GroupedQueueBuilder(ShuffleSettings(grouping = Grouping.ARTIST)).build(0, songs, emptyList(), artists)

        // "Alpha" (artist 2) sorts before "Zeta" (artist 1) alphabetically when not shuffled.
        assertEquals(listOf(20L, 10L), result.map { it.id })
    }

    @Test
    fun `an artist's songs across several albums are grouped album by album, not interleaved by track number`() {
        // Real bug: sorting an artist's whole catalog by (disc, track) alone -- without grouping
        // by album first -- clustered every album's "track 1" together, then every "track 2",
        // regardless of which album each one actually came from.
        val albums = listOf(album(1, "Zebra Album", artistId = 1), album(2, "Apple Album", artistId = 1))
        val artists = listOf(artist(1, "The Artist"))
        val songs = listOf(
            song(id = 10, albumId = 1, artistId = 1, track = 1),
            song(id = 11, albumId = 1, artistId = 1, track = 2),
            song(id = 20, albumId = 2, artistId = 1, track = 1),
            song(id = 21, albumId = 2, artistId = 1, track = 2),
        )

        val result = GroupedQueueBuilder(ShuffleSettings(grouping = Grouping.ARTIST)).build(0, songs, albums, artists)

        // "Apple Album" sorts before "Zebra Album"; each album's two tracks stay together and in
        // track order, rather than the old (10, 20, 11, 21) track-number-first interleaving.
        assertEquals(listOf(20L, 21L, 10L, 11L), result.map { it.id })
    }

    @Test
    fun `covers every song exactly once regardless of settings`() {
        val albums = (1..10L).map { album(it, "Album $it") }
        val songs = albums.flatMap { a ->
            listOf(song(id = a.id * 10, albumId = a.id, track = 1), song(id = a.id * 10 + 1, albumId = a.id, track = 2))
        }
        val settings = ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true, shuffleSongs = true)

        val result = GroupedQueueBuilder(settings).build(99, songs, albums, emptyList())

        assertEquals(songs.map { it.id }.toSet(), result.map { it.id }.toSet())
        assertEquals(songs.size, result.size)
    }

    @Test
    fun `same seed produces the same order`() {
        val albums = listOf(album(1, "A"), album(2, "B"), album(3, "C"), album(4, "D"))
        val songs = albums.map { song(id = it.id, albumId = it.id, track = 1) }
        val settings = ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true)

        val first = GroupedQueueBuilder(settings).build(5, songs, albums, emptyList())
        val second = GroupedQueueBuilder(settings).build(5, songs, albums, emptyList())

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `empty library produces an empty queue`() {
        assertTrue(GroupedQueueBuilder(ShuffleSettings()).build(0, emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `single album single song edge case`() {
        val albums = listOf(album(1, "Solo"))
        val songs = listOf(song(id = 1, albumId = 1, track = 1))
        val settings = ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = true, shuffleSongs = true)

        val result = GroupedQueueBuilder(settings).build(0, songs, albums, emptyList())

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `grouping by genre groups songs by their own genre name, alphabetically when off`() {
        val songs = listOf(
            song(id = 10, albumId = 1, track = 1, genreName = "Rock"),
            song(id = 11, albumId = 1, track = 2, genreName = "Jazz"),
            song(id = 12, albumId = 1, track = 3, genreName = "Rock"),
        )
        val result = GroupedQueueBuilder(ShuffleSettings(grouping = Grouping.GENRE)).build(0, songs, emptyList(), emptyList())

        // "Jazz" sorts before "Rock"; within "Rock", natural (disc, track) order is kept.
        assertEquals(listOf(11L, 10L, 12L), result.map { it.id })
    }

    @Test
    fun `songs with no genre are grouped together rather than dropped`() {
        val songs = listOf(
            song(id = 1, albumId = 1, track = 1, genreName = null),
            song(id = 2, albumId = 1, track = 2, genreName = "Rock"),
            song(id = 3, albumId = 1, track = 3, genreName = null),
        )
        val result = GroupedQueueBuilder(ShuffleSettings(grouping = Grouping.GENRE)).build(0, songs, emptyList(), emptyList())

        assertEquals(setOf(1L, 2L, 3L), result.map { it.id }.toSet())
    }

    @Test
    fun `grouping by album artist groups songs by that field rather than the track artist`() {
        val songs = listOf(
            song(id = 10, albumId = 1, artistId = 100, track = 1, albumArtistName = "Various Artists"),
            song(id = 11, albumId = 1, artistId = 200, track = 2, albumArtistName = "Various Artists"),
            song(id = 20, albumId = 2, artistId = 300, track = 1, albumArtistName = "A Real Band"),
        )
        val settings = ShuffleSettings(grouping = Grouping.ALBUM_ARTIST)

        val result = GroupedQueueBuilder(settings).build(0, songs, emptyList(), emptyList())

        // "A Real Band" sorts before "Various Artists"; the two "Various Artists" songs (with
        // different track artists) stay grouped together despite that.
        assertEquals(listOf(20L, 10L, 11L), result.map { it.id })
    }
}

class ShuffleAllQueueBuilderTest {

    @Test
    fun `covers every song exactly once, ignoring category boundaries`() {
        val songs = (1..50L).map { song(id = it, albumId = (it % 5) + 1, track = it.toInt()) }
        val result = ShuffleAllQueueBuilder.build(42, songs, emptyList(), emptyList())
        assertEquals(songs.map { it.id }.toSet(), result.map { it.id }.toSet())
        assertEquals(songs.size, result.size)
    }

    @Test
    fun `same seed produces the same order`() {
        val songs = (1..20L).map { song(id = it, albumId = 1, track = it.toInt()) }
        val first = ShuffleAllQueueBuilder.build(7, songs, emptyList(), emptyList())
        val second = ShuffleAllQueueBuilder.build(7, songs, emptyList(), emptyList())
        assertEquals(first.map { it.id }, second.map { it.id })
    }
}

class QueueBuilderForTest {

    @Test
    fun `shuffleAll selects the flat builder regardless of grouping`() {
        assertEquals(ShuffleAllQueueBuilder, queueBuilderFor(ShuffleSettings(shuffleAll = true)))
    }

    @Test
    fun `non-shuffleAll settings select the grouped builder`() {
        assertTrue(queueBuilderFor(ShuffleSettings()) is GroupedQueueBuilder)
    }
}
