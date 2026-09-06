package net.kourlas.dashtone.service.shuffle

import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongEntity

/** Pure queue-ordering strategy; deliberately free of Android/Room types so it's trivially
 * unit-testable. */
fun interface ShuffleQueueBuilder {
    fun build(
        seed: Long,
        songs: List<SongEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ): List<SongEntity>
}
