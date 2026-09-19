package net.kourlas.dashtone.service.testing

import net.kourlas.dashtone.service.data.mediastore.MediaStoreAudioSource
import net.kourlas.dashtone.service.data.mediastore.RawAudioRow

class FakeMediaStoreAudioSource(var rows: List<RawAudioRow> = emptyList()) : MediaStoreAudioSource {
    override fun queryAllAudio(): List<RawAudioRow> = rows
}

fun rawAudioRow(
    id: Long,
    title: String,
    artistId: Long,
    artistName: String,
    albumId: Long,
    albumName: String,
    rawTrack: Int = 0,
    albumYear: Int? = null,
    durationMs: Long = 200_000,
    dateAdded: Long = 0,
    dateModified: Long = 0,
    filePath: String? = null,
    relativePath: String? = null,
    // Defaults to a real device's actual MediaStore volume name for internal shared storage, so
    // tests that only care about folder names (not multi-volume behavior) don't all need to pass
    // this explicitly -- pass a different value to specifically exercise multi-volume scenarios.
    volumeName: String? = if (relativePath != null) "external_primary" else null,
    genreName: String? = null,
    albumArtistName: String? = null,
    composerName: String? = null,
) = RawAudioRow(
    id = id,
    title = title,
    artistId = artistId,
    artistName = artistName,
    albumId = albumId,
    albumName = albumName,
    albumYear = albumYear,
    rawTrack = rawTrack,
    durationMs = durationMs,
    dateAdded = dateAdded,
    dateModified = dateModified,
    contentUri = "content://media/external/audio/media/$id",
    filePath = filePath,
    relativePath = relativePath,
    volumeName = volumeName,
    genreName = genreName,
    albumArtistName = albumArtistName,
    composerName = composerName,
)
