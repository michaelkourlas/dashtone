package net.kourlas.dashtone.service.testing

import net.kourlas.dashtone.service.data.mediastore.MediaStorePlaylistSource
import net.kourlas.dashtone.service.data.mediastore.RawPlaylistFile

class FakeMediaStorePlaylistSource(
    var files: List<RawPlaylistFile> = emptyList(),
    var textByContentUri: Map<String, String> = emptyMap(),
) : MediaStorePlaylistSource {
    /** Content URIs actually read, in order, so a test can tell a skipped playlist from a
     * re-parsed one -- avoiding the read is the whole point of the dateModified check. */
    val reads = mutableListOf<String>()

    override fun queryAllPlaylistFiles(): List<RawPlaylistFile> = files

    override fun readText(contentUri: String): String? {
        reads += contentUri
        return textByContentUri[contentUri]
    }
}

fun rawPlaylistFile(
    id: Long,
    name: String,
    filePath: String,
    dateModified: Long = 0,
) = RawPlaylistFile(
    id = id,
    name = name,
    contentUri = "content://media/external/file/$id",
    filePath = filePath,
    dateModified = dateModified,
)
