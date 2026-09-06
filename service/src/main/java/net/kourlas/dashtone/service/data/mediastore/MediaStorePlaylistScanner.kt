package net.kourlas.dashtone.service.data.mediastore

import android.os.SystemClock
import net.kourlas.dashtone.service.data.local.PlaylistDao
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStorePlaylistScanner @Inject constructor(
    private val playlistSource: MediaStorePlaylistSource,
    private val playlistDao: PlaylistDao,
) : PlaylistScanner {

    // Not a real constructor dependency -- see MediaStoreLibraryScanner.elapsedRealtimeMs's own
    // comment for why this stays a plain, test-overridable property instead.
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    // See MediaStoreLibraryScanner.startupGraceDeadlineMs's own doc comment for the full
    // reasoning: a playlist missing from a scan run soon after this scanner was created (right
    // after car ignition, most notably) isn't deleted, since MediaStore may simply not have
    // finished indexing yet; the same scan run later in an already-running session deletes
    // immediately, exactly as before this fix existed.
    private var startupGraceDeadlineMs: Long? = null

    override suspend fun scanPlaylists(pathToSongId: Map<String, Long>, force: Boolean) {
        val files = playlistSource.queryAllPlaylistFiles()
        val existingIds = playlistDao.getAllIds().toHashSet()

        // Playlist rows must exist before their entries are inserted (FK constraint), so upsert
        // the playlists themselves in one pass before touching any entries.
        val playlists = files.map { PlaylistEntity(id = it.id, name = it.name, dateModified = it.dateModified) }
        playlistDao.upsertAll(playlists)
        val now = elapsedRealtimeMs()
        val deadline = startupGraceDeadlineMs ?: (now + STARTUP_GRACE_PERIOD_MS).also { startupGraceDeadlineMs = it }
        val missingThisScan = existingIds - playlists.mapTo(HashSet()) { it.id }
        val confirmedMissing = if (force || now >= deadline) missingThisScan else emptySet()
        if (confirmedMissing.isNotEmpty()) {
            playlistDao.deleteByIds(confirmedMissing.toList())
        }

        for (file in files) {
            val baseDir = file.filePath.substringBeforeLast('/', "")
            val entries = playlistSource.readText(file.contentUri)
                ?.let(M3uParser::parseEntries)
                ?.map { M3uParser.resolveAbsolutePath(it, baseDir) }
                ?.mapNotNull { pathToSongId[it] }
                ?.mapIndexed { index, songId -> PlaylistEntryEntity(playlistId = file.id, position = index, songId) }
                ?: continue
            playlistDao.replaceEntries(file.id, entries)
        }
    }

    internal companion object {
        const val STARTUP_GRACE_PERIOD_MS = 60_000L
    }
}
