package net.kourlas.dashtone.service.testing

import net.kourlas.dashtone.service.data.mediastore.PlaylistScanner

/** No-op stand-in for tests exercising song/album/artist scanning only. */
class FakePlaylistScanner : PlaylistScanner {
    var lastPathToSongId: Map<String, Long>? = null
        private set
    var lastLibrarySongsChanged: Boolean? = null
        private set

    override suspend fun scanPlaylists(
        pathToSongId: Map<String, Long>,
        force: Boolean,
        librarySongsChanged: Boolean,
    ) {
        lastPathToSongId = pathToSongId
        lastLibrarySongsChanged = librarySongsChanged
    }
}
