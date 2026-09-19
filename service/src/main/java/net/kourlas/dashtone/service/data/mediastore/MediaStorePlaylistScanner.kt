package net.kourlas.dashtone.service.data.mediastore

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun scanPlaylists(
        pathToSongId: Map<String, Long>,
        force: Boolean,
        librarySongsChanged: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        val files = playlistSource.queryAllPlaylistFiles()
        // Read before the upsert below overwrites dateModified with what is on disk now.
        val stored = playlistDao.getAll()
        val existingIds = stored.mapTo(HashSet()) { it.id }
        val storedDateModified = stored.associate { it.id to it.dateModified }
        val idsWithEntries = playlistDao.idsWithEntries().toHashSet()

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
            if (canSkip(file, force, librarySongsChanged, storedDateModified, idsWithEntries)) continue
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

    /** Whether [file]'s entries can be left exactly as they are.
     *
     * Reading and re-resolving a playlist is the expensive half of a scan -- every entry of every
     * file, on a rescan that LibraryChangeObserver triggers for any file-system activity at all --
     * and a playlist is a plain copy of its backing file, so an unchanged file means unchanged
     * entries. Three things override that:
     *
     * [force] is an explicit user-requested sync, which re-reads everything by definition.
     * [librarySongsChanged] means the songs an entry could resolve to are different now, so an
     * entry that matched nothing before may match today even though the file never moved.
     * A playlist with no stored entries has nothing to keep: its file was unreadable when it was
     * last seen (see the readText call below), and re-reading is the only way it ever gets any.
     * That last check also re-reads a genuinely empty playlist file every scan, which costs one
     * read of a file with nothing in it. */
    private fun canSkip(
        file: RawPlaylistFile,
        force: Boolean,
        librarySongsChanged: Boolean,
        storedDateModified: Map<Long, Long>,
        idsWithEntries: Set<Long>,
    ): Boolean = !force &&
        !librarySongsChanged &&
        storedDateModified[file.id] == file.dateModified &&
        file.id in idsWithEntries

    internal companion object {
        const val STARTUP_GRACE_PERIOD_MS = 60_000L
    }
}
