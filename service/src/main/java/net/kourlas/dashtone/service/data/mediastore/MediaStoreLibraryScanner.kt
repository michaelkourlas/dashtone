package net.kourlas.dashtone.service.data.mediastore

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kourlas.dashtone.service.data.local.AlbumDao
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistDao
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.SongDao
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.prefs.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreLibraryScanner @Inject constructor(
    private val audioSource: MediaStoreAudioSource,
    private val playlistScanner: PlaylistScanner,
    private val songDao: SongDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val appPreferences: AppPreferences,
) : LibraryScanner {

    // Guards against overlapping scans triggered concurrently by a ContentObserver callback and
    // an explicit user-triggered rescan.
    private val scanMutex = Mutex()

    // A plain property, so a test can override it directly on the instance to control time in
    // startupGraceDeadlineMs's check -- no fake binding, no Robolectric. Hilt only has to satisfy
    // the constructor params above.
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    // Deleting a song the instant a scan doesn't find it is unsafe: at car ignition, MediaStore
    // can take a noticeable time to (re-)index external/USB storage after boot, so a scan run
    // soon after this scanner (and the service hosting it) was freshly created by that same
    // ignition can come back missing songs that are still genuinely there, just not yet reported.
    // Treating that as ground truth would wipe out real songs -- and, via QueueStateStore's
    // song-id resolution, most of the resumable queue along with them -- purely because the scan
    // ran too early.
    //
    // This is deliberately scoped to "soon after this scanner was created," not "any scan that
    // finds something missing, ever": the only evidenced failure is a fresh process racing
    // MediaStore right after boot, and protecting only that window -- rather than every scan for
    // the scanner's entire lifetime -- means a genuine deletion made during an ordinary,
    // already-running session is never held up waiting for a confirmation that doesn't apply to
    // it. Once the grace period has passed, a missing song is deleted immediately. [force] (an
    // explicit, deliberate user action -- tapping "Rescan Library," or having just granted the
    // permission this scan needed) skips the grace period
    // entirely regardless of how recently the scanner was created, since that action itself is
    // the user's own confirmation that now is the right time to sync.
    private var startupGraceDeadlineMs: Long? = null

    override suspend fun scanLibrary(force: Boolean): Unit = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val excludedFolders = appPreferences.excludedFolders.first()
            val rows = audioSource.queryAllAudio()
                .filter { folderKey(it.volumeName, topLevelFolderOf(it.relativePath)) !in excludedFolders }
            val existingSongIds = songDao.getAllIds().toHashSet()

            val artists = LinkedHashMap<Long, ArtistEntity>()
            val albumNames = LinkedHashMap<Long, AlbumEntity>()
            val songs = ArrayList<SongEntity>(rows.size)

            for (row in rows) {
                artists.putIfAbsent(row.artistId, ArtistEntity(id = row.artistId, name = row.artistName))
                albumNames.putIfAbsent(
                    row.albumId,
                    AlbumEntity(
                        id = row.albumId,
                        name = row.albumName,
                        artistId = row.artistId,
                        year = row.albumYear,
                    ),
                )

                val (discNumber, trackNumber) = parseDiscAndTrack(row.rawTrack)
                songs += SongEntity(
                    id = row.id,
                    title = row.title,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    durationMs = row.durationMs,
                    albumId = row.albumId,
                    artistId = row.artistId,
                    contentUri = row.contentUri,
                    dateAdded = row.dateAdded,
                    dateModified = row.dateModified,
                    genreName = row.genreName,
                    albumArtistName = row.albumArtistName,
                    composerName = row.composerName,
                    folderPath = fullFolderPathOf(row.relativePath),
                )
            }
            // Parents must be upserted before songs due to the FK constraints on SongEntity.
            artistDao.upsertAll(artists.values.toList())
            albumDao.upsertAll(albumNames.values.toList())
            songDao.upsertAll(songs)

            // See startupGraceDeadlineMs's own doc comment for the reasoning. The deadline is set
            // lazily, on this first call, rather than in the constructor: tests override
            // elapsedRealtimeMs after construction, and computing "now" here means that override
            // is always the one in effect.
            val now = elapsedRealtimeMs()
            val deadline = startupGraceDeadlineMs ?: (now + STARTUP_GRACE_PERIOD_MS).also { startupGraceDeadlineMs = it }
            val incomingSongIds = songs.mapTo(HashSet()) { it.id }
            val missingThisScan = existingSongIds - incomingSongIds
            val confirmedMissing = if (force || now >= deadline) missingThisScan else emptySet()
            if (confirmedMissing.isNotEmpty()) {
                songDao.deleteByIds(confirmedMissing.toList())
            }

            // Orphan cleanup runs on every scan, since a song can stop referencing an album or
            // artist while remaining in the library itself: retagging one onto a different album
            // keeps its MediaStore id, so the upsert above simply repoints it and the deletion
            // above finds nothing missing, yet the album it left behind now has no songs at all.
            // Albums are cleaned before artists so an album row is gone before the artist it
            // points at is considered.
            albumDao.deleteOrphans()
            artistDao.deleteOrphans()

            // Built from this same scan pass (not a DB read) so paths and the songs they resolve
            // to are always consistent, even mid-scan.
            val pathToSongId = rows.mapNotNull { row ->
                row.filePath?.let { M3uParser.normalizePath(it) to row.id }
            }.toMap()
            // Counts songs that actually left the library, rather than every song this scan
            // happened to miss: during the startup grace period a missing song stays put, and its
            // playlist entries should stay put with it. See PlaylistScanner.scanPlaylists for what
            // this decides.
            val librarySongsChanged =
                confirmedMissing.isNotEmpty() || incomingSongIds.any { it !in existingSongIds }
            playlistScanner.scanPlaylists(pathToSongId, force, librarySongsChanged)
        }
    }

    override suspend fun availableFolders(): List<LibraryFolder> = withContext(Dispatchers.IO) {
        audioSource.queryAllAudio()
            .mapNotNull { row ->
                val topLevel = topLevelFolderOf(row.relativePath) ?: return@mapNotNull null
                val volume = row.volumeName ?: return@mapNotNull null
                val key = folderKey(volume, topLevel) ?: return@mapNotNull null
                LibraryFolder(key = key, volumeName = volume, topLevelFolder = topLevel)
            }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.volumeName }, { it.topLevelFolder }))
    }

    internal companion object {
        // Comfortably longer than the ~75ms observed between rescans within a single connection
        // handshake, generous enough to cover a slow real-world MediaStore catch-up on a large
        // library. The cost of overshooting here is low -- it only ever delays cleanup during
        // this narrow post-startup window, never during the rest of the scanner's lifetime, and
        // nobody is realistically deleting songs from their car library in the exact instant they
        // start the car -- so this errs long rather than trying to guess a tight number. internal,
        // not private, so tests can drive the same constant instead of duplicating it.
        const val STARTUP_GRACE_PERIOD_MS = 60_000L
    }
}
