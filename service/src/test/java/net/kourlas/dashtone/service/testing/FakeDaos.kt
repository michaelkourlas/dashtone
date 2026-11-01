package net.kourlas.dashtone.service.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.kourlas.dashtone.service.data.local.AlbumDao
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistDao
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistDao
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntryEntity
import net.kourlas.dashtone.service.data.local.QueueStateDao
import net.kourlas.dashtone.service.data.local.QueueStateEntity
import net.kourlas.dashtone.service.data.local.SongDao
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.local.SongIdAndVolume

/**
 * In-memory fakes standing in for Room's generated DAOs. They intentionally don't replicate exact
 * SQL ordering (that's covered by [net.kourlas.dashtone.service.data.local.DashtoneDatabaseTest]
 * against a real database) -- only the upsert/delete/id-lookup semantics that
 * [net.kourlas.dashtone.service.data.mediastore.MediaStoreLibraryScanner] and
 * [net.kourlas.dashtone.service.data.repository.LibraryRepository] depend on.
 */
class FakeSongDao : SongDao {
    private val state = MutableStateFlow<Map<Long, SongEntity>>(emptyMap())
    val current: Map<Long, SongEntity> get() = state.value

    override fun observeAll(): Flow<List<SongEntity>> =
        state.map { it.values.sortedBy { song -> song.title.lowercase() } }

    override fun observeForAlbum(albumId: Long): Flow<List<SongEntity>> =
        state.map { it.values.filter { song -> song.albumId == albumId } }

    override fun observeForArtist(artistId: Long): Flow<List<SongEntity>> =
        state.map { it.values.filter { song -> song.artistId == artistId } }

    override suspend fun getByIds(ids: List<Long>): List<SongEntity> =
        ids.mapNotNull { state.value[it] }

    override suspend fun getById(id: Long): SongEntity? = state.value[id]

    override suspend fun getAnyForAlbum(albumId: Long): SongEntity? =
        state.value.values.firstOrNull { it.albumId == albumId }

    override suspend fun getAllIds(): List<Long> = state.value.keys.toList()

    override suspend fun getAllIdsAndVolumes(): List<SongIdAndVolume> =
        state.value.values.map { SongIdAndVolume(it.id, it.volumeName) }

    override suspend fun count(): Int = state.value.size

    override suspend fun distinctGenreNames(): List<String?> = state.value.values.map { it.genreName }.distinct()

    override suspend fun distinctAlbumArtistNames(): List<String?> =
        state.value.values.map { it.albumArtistName }.distinct()

    override suspend fun distinctComposerNames(): List<String?> = state.value.values.map { it.composerName }.distinct()

    override suspend fun distinctFolderPaths(): List<String> = state.value.values.mapNotNull { it.folderPath }.distinct()

    override suspend fun countInFolder(folderPath: String): Int =
        state.value.values.count { it.folderPath == folderPath }

    // Mirrors the real query's INNER JOIN onto albums: a song whose album row is absent drops out
    // rather than contributing a null year. [albumById] is wired up by [FakeAlbumDao].
    override suspend fun distinctAlbumYears(): List<Int?> =
        state.value.values.mapNotNull { albumById(it.albumId) }.map { it.year }.distinct()

    /** Set by [FakeAlbumDao]'s constructor so [distinctAlbumYears] can resolve the join. */
    internal var albumById: (Long) -> AlbumEntity? = { null }

    override suspend fun upsertAll(songs: List<SongEntity>) {
        state.value = state.value + songs.associateBy { it.id }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        state.value = state.value - ids.toSet()
    }
}

class FakeAlbumDao(private val songDao: FakeSongDao) : AlbumDao {
    private val state = MutableStateFlow<Map<Long, AlbumEntity>>(emptyMap())
    val current: Map<Long, AlbumEntity> get() = state.value

    init {
        songDao.albumById = { id -> state.value[id] }
    }

    override suspend fun count(): Int = state.value.size

    override fun observeAll(): Flow<List<AlbumEntity>> = state.map { it.values.toList() }

    override fun observeForArtist(artistId: Long): Flow<List<AlbumEntity>> =
        state.map { it.values.filter { album -> album.artistId == artistId } }

    override suspend fun getAllIds(): List<Long> = state.value.keys.toList()

    override suspend fun getById(id: Long): AlbumEntity? = state.value[id]

    override suspend fun upsertAll(albums: List<AlbumEntity>) {
        state.value = state.value + albums.associateBy { it.id }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        state.value = state.value - ids.toSet()
    }

    override suspend fun deleteOrphans() {
        val referencedAlbumIds = songDao.current.values.mapTo(HashSet()) { it.albumId }
        state.value = state.value.filterKeys { it in referencedAlbumIds }
    }
}

class FakeArtistDao(private val songDao: FakeSongDao) : ArtistDao {
    private val state = MutableStateFlow<Map<Long, ArtistEntity>>(emptyMap())
    val current: Map<Long, ArtistEntity> get() = state.value

    override fun observeAll(): Flow<List<ArtistEntity>> = state.map { it.values.toList() }

    override suspend fun getAllIds(): List<Long> = state.value.keys.toList()

    override suspend fun getById(id: Long): ArtistEntity? = state.value[id]

    override suspend fun count(): Int = state.value.size

    override suspend fun upsertAll(artists: List<ArtistEntity>) {
        state.value = state.value + artists.associateBy { it.id }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        state.value = state.value - ids.toSet()
    }

    override suspend fun deleteOrphans() {
        val referencedArtistIds = songDao.current.values.mapTo(HashSet()) { it.artistId }
        state.value = state.value.filterKeys { it in referencedArtistIds }
    }
}

class FakeQueueStateDao : QueueStateDao {
    private val state = MutableStateFlow<QueueStateEntity?>(null)

    override suspend fun get(): QueueStateEntity? = state.value

    override suspend fun upsert(state: QueueStateEntity) {
        this.state.value = state
    }
}

class FakePlaylistDao(private val songDao: FakeSongDao) : PlaylistDao {
    private val state = MutableStateFlow<Map<Long, PlaylistEntity>>(emptyMap())
    private val entriesState = MutableStateFlow<Map<Long, List<PlaylistEntryEntity>>>(emptyMap())
    val current: Map<Long, PlaylistEntity> get() = state.value
    val currentEntries: Map<Long, List<PlaylistEntryEntity>> get() = entriesState.value

    override fun observeAll(): Flow<List<PlaylistEntity>> =
        state.map { it.values.sortedBy { playlist -> playlist.name.lowercase() } }

    override fun observeSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>> =
        entriesState.map { entries ->
            entries[playlistId].orEmpty().sortedBy { it.position }.mapNotNull { songDao.current[it.songId] }
        }

    override suspend fun getById(id: Long): PlaylistEntity? = state.value[id]

    override suspend fun getAllIds(): List<Long> = state.value.keys.toList()

    override suspend fun getAll(): List<PlaylistEntity> = state.value.values.toList()

    override suspend fun idsWithEntries(): List<Long> =
        entriesState.value.filterValues { it.isNotEmpty() }.keys.toList()

    override suspend fun count(): Int = state.value.size

    override suspend fun upsertAll(playlists: List<PlaylistEntity>) {
        state.value = state.value + playlists.associateBy { it.id }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        state.value = state.value - ids.toSet()
        entriesState.value = entriesState.value - ids.toSet()
    }

    override suspend fun deleteEntries(playlistId: Long) {
        entriesState.value = entriesState.value - playlistId
    }

    override suspend fun insertEntries(entries: List<PlaylistEntryEntity>) {
        val byPlaylist = entries.groupBy { it.playlistId }
        entriesState.value = byPlaylist.entries.fold(entriesState.value) { acc, (playlistId, newEntries) ->
            acc + (playlistId to (acc[playlistId].orEmpty() + newEntries))
        }
    }
}
