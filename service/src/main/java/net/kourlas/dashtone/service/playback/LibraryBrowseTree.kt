package net.kourlas.dashtone.service.playback

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.first
import net.kourlas.dashtone.service.data.BrowseCategory
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.data.repository.LibraryRepository
import net.kourlas.dashtone.service.shuffle.GroupedQueueBuilder
import net.kourlas.dashtone.service.shuffle.Grouping
import net.kourlas.dashtone.service.shuffle.ShuffleSettings
import net.kourlas.dashtone.service.shuffle.UNKNOWN_ALBUM_ARTIST
import net.kourlas.dashtone.service.shuffle.UNKNOWN_COMPOSER
import net.kourlas.dashtone.service.shuffle.UNKNOWN_GENRE
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.random.Random

/**
 * Builds the browse tree's [MediaItem]s from the library repository. Kept free of Media3
 * session/ListenableFuture types so it's directly unit-testable; [DashtoneLibrarySessionCallback]
 * is the thin Media3-specific adapter over this class.
 */
class LibraryBrowseTree @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val mediaItemMapper: MediaItemMapper,
) {
    // The car host fetches root's direct children (the tab strip) exactly once per connection and
    // caches them on its own side for that connection's whole lifetime -- confirmed by five
    // separate failed live-refresh attempts (see DashtoneMediaLibraryService's comment) and
    // independently corroborated by multiple still-open upstream bugs against Google's own Media3
    // host implementations (github.com/androidx/media/issues/644, /561, /3253). That staleness
    // can't be fixed from app code, but it MUST be made safe: enabledCategoriesInOrder() used to
    // re-read categoryOrder/disabledCategories live on every call, so a settings change mid-
    // connection could make the (live, uncached) "More" list disagree with the (host-cached,
    // stale) tab strip -- e.g. a category just moved out of the tabs into "More" would briefly
    // appear in both places at once. Snapshotting the enabled/ordered category list inside root()
    // -- the same call a host makes when it (re)builds its tab strip -- and reusing that exact
    // snapshot for every children() call until the next root() call guarantees the tab strip and
    // "More" always describe the identical settings state, even if that state is one change behind
    // the latest. That is what "changes apply the next time you open Dashtone" concretely means:
    // not a hope that the host will refresh, but a structural guarantee that it can never show two
    // different answers for the same category at once.
    private val categorySnapshot = AtomicReference<List<BrowseCategory>?>(null)

    suspend fun root(): MediaItem {
        // [LibraryChangeObserver] only catches changes while DashtoneMediaLibraryService is alive
        // to run it -- on AAOS that's not guaranteed to span the gap between two car connections
        // (the service can be torn down while idle), so a file added/removed in between would
        // otherwise sit unnoticed until the user manually taps "Rescan Library." Each fresh
        // connection's root() call is the natural place to catch up: if ensureScanned() didn't
        // already scan (first run / empty-table recovery), rescan now.
        if (!libraryRepository.ensureScanned()) {
            libraryRepository.rescan()
        }
        categorySnapshot.set(computeEnabledCategoriesInOrder())
        return mediaItemMapper.rootItem()
    }

    // Shuffle entries are nested as the first item within their relevant category rather than
    // placed at the root: this AAOS host's tab-based root UI only renders browsable root
    // children, silently dropping playable-only ones -- confirmed live, and content-style hints
    // don't change that. Nesting them is also a common, more universally-compatible pattern (the
    // same one Spotify/YouTube Music use) since it only relies on browsable-list rendering, which
    // every host already needs to support.
    suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        MediaIds.ROOT -> rootChildren()

        MediaIds.MORE_ROOT -> overflowCategories().map { it.toRootItem() }

        MediaIds.ALBUMS_ROOT -> {
            val artistNamesById = libraryRepository.artists.first().associate { it.id to it.name }
            val albums = libraryRepository.albums.first().map { album ->
                mediaItemMapper.toMediaItem(album, artistNamesById[album.artistId].orEmpty())
            }
            listOf(mediaItemMapper.playAllAlbumsItem(), mediaItemMapper.shuffleAllAlbumsItem()) + albums
        }

        MediaIds.ARTISTS_ROOT -> {
            val artists = libraryRepository.artists.first().map { mediaItemMapper.toMediaItem(it) }
            listOf(mediaItemMapper.playAllArtistsItem(), mediaItemMapper.shuffleAllArtistsItem()) + artists
        }

        // Genre/album artist have no backing entity (see SongEntity's doc comment), so their
        // "categories" are computed here by distinct-ing the denormalized string field straight off
        // the current song list, the same source ShuffleQueueBuilders groups by.
        MediaIds.GENRES_ROOT -> {
            val genres = libraryRepository.songs.first().map { it.genreName ?: UNKNOWN_GENRE }
                .distinct().sorted().map { mediaItemMapper.toGenreItem(it) }
            listOf(mediaItemMapper.playAllGenresItem(), mediaItemMapper.shuffleAllGenresItem()) + genres
        }

        MediaIds.ALBUM_ARTISTS_ROOT -> {
            val albumArtists = libraryRepository.songs.first().map { it.albumArtistName ?: UNKNOWN_ALBUM_ARTIST }
                .distinct().sorted().map { mediaItemMapper.toAlbumArtistItem(it) }
            listOf(mediaItemMapper.playAllAlbumArtistsItem(), mediaItemMapper.shuffleAllAlbumArtistsItem()) + albumArtists
        }

        MediaIds.COMPOSERS_ROOT -> {
            val composers = libraryRepository.songs.first().map { it.composerName ?: UNKNOWN_COMPOSER }
                .distinct().sorted().map { mediaItemMapper.toComposerItem(it) }
            listOf(mediaItemMapper.playAllComposersItem(), mediaItemMapper.shuffleAllComposersItem()) + composers
        }

        // Decades group ALBUMS (an album's MediaStore YEAR), not a per-song field like genre/album
        // artist/composer -- there's no natural "song grouping" here for the shuffle engine to hook
        // into the way Grouping does for the others, so this stays a plain browse category (list of
        // decades -> their songs) with no matching "Shuffle Decades" quick action or double-tap
        // group-skip support. Not worth a denormalized per-song year field just for that parity.
        MediaIds.DECADES_ROOT -> {
            val albumDecadeById = libraryRepository.albums.first().associate { it.id to decadeOf(it.year) }
            libraryRepository.songs.first().map { albumDecadeById[it.albumId] ?: UNKNOWN_DECADE }
                .distinct().sortedWith(decadeDisplayOrder).map { mediaItemMapper.toDecadeItem(it) }
        }

        // Root of the real on-disk folder hierarchy (not just the flat top-level list Settings'
        // exclusion picker uses) -- an empty-string prefix means "directly under the scanned
        // root," matching folderChildren's own convention.
        MediaIds.FOLDERS_ROOT -> folderChildren("")

        MediaIds.SONGS_ROOT -> listOf(mediaItemMapper.playAllSongsItem(), mediaItemMapper.shuffleAllItem()) +
            mapSongs(libraryRepository.songs.first())

        // No shuffle sentinel here: playlists are user-curated file order, not a shuffleable
        // library-wide category like the other roots.
        MediaIds.PLAYLISTS_ROOT -> libraryRepository.playlists.first().map { mediaItemMapper.toMediaItem(it) }

        else -> loadContainerChildren(parentId)
    }

    // The car UI's toolbar tab strip is a plain, non-scrolling row that fits exactly 4 tabs and
    // silently drops anything beyond that -- confirmed live via uiautomator: adding a 5th/6th root
    // tab made two existing ones (Songs, Playlists) vanish from the strip entirely, with no
    // scroll/overflow affordance at all. So at most 3 user-chosen categories get a tab of their
    // own; everything else the user has enabled -- however many that is -- lives one level deeper,
    // behind a 4th "More" tab, keeping the total at or under what the host can actually render.
    private suspend fun rootChildren(): List<MediaItem> {
        val enabled = enabledCategoriesInOrder()
        val tabs = enabled.take(MAX_ROOT_TABS - 1).map { it.toRootItem() }
        return if (enabled.size > MAX_ROOT_TABS - 1) tabs + mediaItemMapper.moreRootItem() else tabs
    }

    private suspend fun overflowCategories(): List<BrowseCategory> =
        enabledCategoriesInOrder().drop(MAX_ROOT_TABS - 1)

    // Defensive fallback for callers that reach here before root() has ever run (e.g. a unit test
    // exercising children() directly) -- computes and caches on demand rather than returning an
    // empty/stale list.
    private suspend fun enabledCategoriesInOrder(): List<BrowseCategory> =
        categorySnapshot.get() ?: computeEnabledCategoriesInOrder().also { categorySnapshot.set(it) }

    private suspend fun computeEnabledCategoriesInOrder(): List<BrowseCategory> {
        val order = libraryRepository.categoryOrder.first()
        val disabled = libraryRepository.disabledCategories.first()
        return order.filter { it !in disabled }
    }

    private fun BrowseCategory.toRootItem(): MediaItem = when (this) {
        BrowseCategory.ALBUMS -> mediaItemMapper.albumsRootItem()
        BrowseCategory.ARTISTS -> mediaItemMapper.artistsRootItem()
        BrowseCategory.GENRES -> mediaItemMapper.genresRootItem()
        BrowseCategory.ALBUM_ARTISTS -> mediaItemMapper.albumArtistsRootItem()
        BrowseCategory.COMPOSERS -> mediaItemMapper.composersRootItem()
        BrowseCategory.DECADES -> mediaItemMapper.decadesRootItem()
        BrowseCategory.FOLDERS -> mediaItemMapper.foldersRootItem()
        BrowseCategory.SONGS -> mediaItemMapper.songsRootItem()
        BrowseCategory.PLAYLISTS -> mediaItemMapper.playlistsRootItem()
    }

    /** "1980s"/"2010s" for a known MediaStore year, bucketed by decade rather than exact year to
     * keep the browse list short; [UNKNOWN_DECADE] for an album with no year tag. */
    private fun decadeOf(year: Int?): String = if (year == null) UNKNOWN_DECADE else "${(year / 10) * 10}s"

    // Most-recent-decade-first reads better for browsing than alphabetical (which would put
    // "1980s" before "2020s"); UNKNOWN_DECADE is a catch-all bucket, not a real decade, so it's
    // pinned last regardless of sort direction rather than sorting wherever "Unknown..." happens to
    // fall lexicographically (first, ahead of "2020s", under a naive descending string sort).
    private val decadeDisplayOrder: Comparator<String> =
        compareByDescending<String> { it != UNKNOWN_DECADE }.thenByDescending { it }

    /** Case-insensitive substring match across songs, albums, artists, playlists, genres, album
     * artists, and composers, grouped by type in that order. Decades are deliberately not included
     * -- "the 2010s" isn't a meaningful free-text search term the way a name is. Recomputed fresh
     * from the repository on every call rather than cached: results only need to be "good enough
     * for a quick lookup," not exhaustively indexed. */
    suspend fun search(query: String): List<MediaItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val allSongs = libraryRepository.songs.first()
        val albumNamesById = libraryRepository.albums.first().associate { it.id to it.name }
        val artistNamesById = libraryRepository.artists.first().associate { it.id to it.name }

        val songs = allSongs
            .filter { it.title.contains(trimmed, ignoreCase = true) }
            .map { song ->
                mediaItemMapper.toMediaItem(
                    song,
                    albumNamesById[song.albumId].orEmpty(),
                    artistNamesById[song.artistId].orEmpty(),
                )
            }
        val albums = libraryRepository.albums.first()
            .filter { it.name.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toMediaItem(it, artistNamesById[it.artistId].orEmpty()) }
        val artists = libraryRepository.artists.first()
            .filter { it.name.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toMediaItem(it, subtitle = mediaItemMapper.artistSubtitle) }
        val playlists = libraryRepository.playlists.first()
            .filter { it.name.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toMediaItem(it, subtitle = mediaItemMapper.playlistSubtitle) }
        val genres = allSongs.map { it.genreName ?: UNKNOWN_GENRE }.distinct()
            .filter { it.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toGenreItem(it, subtitle = mediaItemMapper.genreSubtitle) }
        val albumArtists = allSongs.map { it.albumArtistName ?: UNKNOWN_ALBUM_ARTIST }.distinct()
            .filter { it.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toAlbumArtistItem(it, subtitle = mediaItemMapper.albumArtistSubtitle) }
        val composers = allSongs.map { it.composerName ?: UNKNOWN_COMPOSER }.distinct()
            .filter { it.contains(trimmed, ignoreCase = true) }
            .map { mediaItemMapper.toComposerItem(it, subtitle = mediaItemMapper.composerSubtitle) }

        return songs + albums + artists + playlists + genres + albumArtists + composers
    }

    /** All songs by an artist, flattened across albums -- unlike [children] on an artist id, which
     * returns their albums (correct for hand-browsing, but not useful for "play artist X," which
     * needs an actual playable song queue). */
    suspend fun songsForArtist(artistId: Long): List<MediaItem> =
        mapSongs(libraryRepository.songsForArtist(artistId).first())

    /** All songs in a genre/album artist/composer/decade, flattened across albums, title-sorted --
     * the same "hand-browsing sees albums, playing needs a flat queue" split as [songsForArtist],
     * and what its "All <name> Songs" node (see [MediaItemMapper.allGenreSongsItem]) shows when
     * browsed into. Used by voice/search resolution ([DashtoneLibrarySessionCallback.
     * resolveGenreByName]/[DashtoneLibrarySessionCallback.resolveTextSearch]); [songsQueueFor]
     * covers the "Play Songs"/"Shuffle Songs" sentinels nested in the node itself. */
    suspend fun songsForGenre(name: String): List<MediaItem> = mapSongs(matchingSongsForGenre(name).sortedByTitle())
    suspend fun songsForAlbumArtist(name: String): List<MediaItem> =
        mapSongs(matchingSongsForAlbumArtist(name).sortedByTitle())
    suspend fun songsForComposer(name: String): List<MediaItem> =
        mapSongs(matchingSongsForComposer(name).sortedByTitle())
    suspend fun songsForDecade(name: String): List<MediaItem> = mapSongs(matchingSongsForDecade(name).sortedByTitle())

    /** The flat song queue for a "Play Albums"/"Shuffle Albums" tap on [parentId] (an artist/
     * genre/album artist/composer/decade node) -- unshuffled groups by album the same way that
     * node's own [children] lists them (album name order, track order within each), so "Play
     * Albums" always matches what hand-browsing would play in order. Shuffled honors the same
     * [net.kourlas.dashtone.service.shuffle.CategoryShuffleMode] preference the root "Shuffle
     * Albums" sentinel does (see [net.kourlas.dashtone.service.shuffle.ShuffleController.
     * applyCategoryShuffle]) -- "Shuffle Albums" means the same thing everywhere it appears, just
     * scoped to a smaller song set here instead of the whole library. Null if [parentId] isn't a
     * valid album-list container. */
    suspend fun albumsQueueFor(parentId: String, shuffled: Boolean): List<MediaItem>? {
        val songs = songsForAlbumsView(parentId) ?: return null
        val albums = libraryRepository.albums.first()
        val artists = libraryRepository.artists.first()
        val settings = if (shuffled) {
            val mode = libraryRepository.categoryShuffleMode.first()
            ShuffleSettings(grouping = Grouping.ALBUM, shuffleCategories = mode.shuffleCategories, shuffleSongs = mode.shuffleSongs)
        } else {
            ShuffleSettings(grouping = Grouping.ALBUM)
        }
        val seed = if (shuffled) Random.nextLong() else 0L
        return mapSongs(GroupedQueueBuilder(settings).build(seed, songs, albums, artists))
    }

    /** The flat song queue for a "Play Songs"/"Shuffle Songs" tap on [parentId] (an album/
     * playlist/"All <name> Songs" node) -- unshuffled is exactly what that node's own [children]
     * already shows (track order, file order, or title order respectively); shuffled randomizes it
     * flatly, since there's no category structure left to group by once songs, not albums, are
     * what's being browsed. Null if [parentId] isn't a valid song-list container. */
    suspend fun songsQueueFor(parentId: String, shuffled: Boolean): List<MediaItem>? {
        val songs = songsForSongsView(parentId) ?: return null
        return mapSongs(if (shuffled) songs.shuffled() else songs)
    }

    /** [albumsQueueFor]'s half of the container-type dispatch -- deliberately a separate function
     * from [songsForSongsView] rather than one shared lookup, so each stays strict about which
     * container types it accepts: an album/playlist/"All <name> Songs" id has never been a valid
     * "Play Albums"/"Shuffle Albums" target, and shouldn't silently resolve as if it were. */
    private suspend fun songsForAlbumsView(parentId: String): List<SongEntity>? {
        MediaIds.artistIdOrNull(parentId)?.let { artistId ->
            libraryRepository.artistById(artistId) ?: return null
            return libraryRepository.songsForArtist(artistId).first()
        }
        MediaIds.genreNameOrNull(parentId)?.let { return matchingSongsForGenre(it) }
        MediaIds.albumArtistNameOrNull(parentId)?.let { return matchingSongsForAlbumArtist(it) }
        MediaIds.composerNameOrNull(parentId)?.let { return matchingSongsForComposer(it) }
        MediaIds.decadeNameOrNull(parentId)?.let { return matchingSongsForDecade(it) }
        return null
    }

    /** [songsQueueFor]'s half of the container-type dispatch -- see [songsForAlbumsView]'s doc
     * comment for why this is kept separate rather than shared. Each container's own natural order
     * is what's returned (irrelevant when [songsQueueFor] is asked to shuffle; load-bearing for its
     * unshuffled case, which must match what that container's own [children] already shows). */
    private suspend fun songsForSongsView(parentId: String): List<SongEntity>? {
        MediaIds.albumIdOrNull(parentId)?.let { albumId ->
            libraryRepository.albumById(albumId) ?: return null
            return libraryRepository.songsForAlbum(albumId).first()
        }
        MediaIds.playlistIdOrNull(parentId)?.let { playlistId ->
            libraryRepository.playlistById(playlistId) ?: return null
            return libraryRepository.songsForPlaylist(playlistId).first()
        }
        MediaIds.allArtistSongsIdOrNull(parentId)?.let { artistId ->
            libraryRepository.artistById(artistId) ?: return null
            // Already title-sorted at the SQL level (SongDao.observeForArtist) -- no separate
            // sortedByTitle() needed the way the name-keyed categories below require, since those
            // are filtered from the full song list in Kotlin rather than queried directly.
            return libraryRepository.songsForArtist(artistId).first()
        }
        MediaIds.allGenreSongsNameOrNull(parentId)?.let { return matchingSongsForGenre(it).sortedByTitle() }
        MediaIds.allAlbumArtistSongsNameOrNull(parentId)?.let { return matchingSongsForAlbumArtist(it).sortedByTitle() }
        MediaIds.allComposerSongsNameOrNull(parentId)?.let { return matchingSongsForComposer(it).sortedByTitle() }
        MediaIds.allDecadeSongsNameOrNull(parentId)?.let { return matchingSongsForDecade(it).sortedByTitle() }
        return null
    }

    private fun List<SongEntity>.sortedByTitle(): List<SongEntity> = sortedBy { it.title.lowercase() }

    // Resolves ANY valid media id in the tree, not just songs: the default
    // MediaLibrarySession.Callback.onSubscribe() calls onGetItem(parentId) to confirm the parent is
    // browsable before registering a subscription, and silently drops the subscription if that
    // lookup fails (confirmed against Media3 1.10.1's own MediaLibrarySessionImpl.
    // onSubscribeOnHandler source: "Remove subscription in case of an error"). This used to only
    // handle song ids, so subscribing to any category tab (root/albums, root/artists, an album's
    // track list, etc.) was silently rejected -- notifyChildrenChanged for those ids was
    // permanently a no-op as a result, which is what made content only ever refresh after
    // navigating away from a tab and back (a fresh subscribe), never in place.
    suspend fun item(mediaId: String): MediaItem? {
        MediaIds.songIdOrNull(mediaId)?.let { songId ->
            val song = libraryRepository.songById(songId) ?: return null
            val album = libraryRepository.albumById(song.albumId)
            val artist = libraryRepository.artistById(song.artistId)
            return mediaItemMapper.toMediaItem(song, album?.name.orEmpty(), artist?.name.orEmpty())
        }
        MediaIds.albumIdOrNull(mediaId)?.let { albumId ->
            val album = libraryRepository.albumById(albumId) ?: return null
            val artist = libraryRepository.artistById(album.artistId)
            return mediaItemMapper.toMediaItem(album, artist?.name.orEmpty())
        }
        MediaIds.artistIdOrNull(mediaId)?.let { artistId ->
            val artist = libraryRepository.artistById(artistId) ?: return null
            return mediaItemMapper.toMediaItem(artist)
        }
        MediaIds.playlistIdOrNull(mediaId)?.let { playlistId ->
            val playlist = libraryRepository.playlistById(playlistId) ?: return null
            return mediaItemMapper.toMediaItem(playlist)
        }
        MediaIds.genreNameOrNull(mediaId)?.let { return mediaItemMapper.toGenreItem(it) }
        MediaIds.albumArtistNameOrNull(mediaId)?.let { return mediaItemMapper.toAlbumArtistItem(it) }
        MediaIds.composerNameOrNull(mediaId)?.let { return mediaItemMapper.toComposerItem(it) }
        MediaIds.decadeNameOrNull(mediaId)?.let { return mediaItemMapper.toDecadeItem(it) }
        MediaIds.folderPathOrNull(mediaId)?.let { return mediaItemMapper.toFolderItem(it) }
        MediaIds.allArtistSongsIdOrNull(mediaId)?.let { artistId ->
            val artist = libraryRepository.artistById(artistId) ?: return null
            return mediaItemMapper.allArtistSongsItem(artistId, artist.name)
        }
        MediaIds.allGenreSongsNameOrNull(mediaId)?.let { return mediaItemMapper.allGenreSongsItem(it) }
        MediaIds.allAlbumArtistSongsNameOrNull(mediaId)?.let { return mediaItemMapper.allAlbumArtistSongsItem(it) }
        MediaIds.allComposerSongsNameOrNull(mediaId)?.let { return mediaItemMapper.allComposerSongsItem(it) }
        MediaIds.allDecadeSongsNameOrNull(mediaId)?.let { return mediaItemMapper.allDecadeSongsItem(it) }
        return categoryRootItem(mediaId)
    }

    private fun categoryRootItem(mediaId: String): MediaItem? = when (mediaId) {
        MediaIds.ROOT -> mediaItemMapper.rootItem()
        MediaIds.MORE_ROOT -> mediaItemMapper.moreRootItem()
        MediaIds.ALBUMS_ROOT -> mediaItemMapper.albumsRootItem()
        MediaIds.ARTISTS_ROOT -> mediaItemMapper.artistsRootItem()
        MediaIds.GENRES_ROOT -> mediaItemMapper.genresRootItem()
        MediaIds.ALBUM_ARTISTS_ROOT -> mediaItemMapper.albumArtistsRootItem()
        MediaIds.COMPOSERS_ROOT -> mediaItemMapper.composersRootItem()
        MediaIds.DECADES_ROOT -> mediaItemMapper.decadesRootItem()
        MediaIds.FOLDERS_ROOT -> mediaItemMapper.foldersRootItem()
        MediaIds.SONGS_ROOT -> mediaItemMapper.songsRootItem()
        MediaIds.PLAYLISTS_ROOT -> mediaItemMapper.playlistsRootItem()
        else -> null
    }

    private suspend fun loadContainerChildren(parentId: String): List<MediaItem>? {
        MediaIds.albumIdOrNull(parentId)?.let { albumId ->
            val album = libraryRepository.albumById(albumId) ?: return null
            val artist = libraryRepository.artistById(album.artistId)
            val songs = libraryRepository.songsForAlbum(albumId).first().map { song ->
                mediaItemMapper.toMediaItem(song, album.name, artist?.name.orEmpty())
            }
            return songPlaybackSentinels(parentId) + songs
        }
        MediaIds.artistIdOrNull(parentId)?.let { artistId ->
            val artist = libraryRepository.artistById(artistId) ?: return null
            val albums = libraryRepository.albumsForArtist(artistId).first().map { album ->
                mediaItemMapper.toMediaItem(album, artist.name)
            }
            return albumPlaybackSentinels(parentId) + mediaItemMapper.allArtistSongsItem(artistId, artist.name) + albums
        }
        MediaIds.playlistIdOrNull(parentId)?.let { playlistId ->
            libraryRepository.playlistById(playlistId) ?: return null
            val songs = mapSongs(libraryRepository.songsForPlaylist(playlistId).first())
            return songPlaybackSentinels(parentId) + songs
        }
        // A flat song list doesn't scale to a real classical/soundtrack library where a genre or
        // composer can span dozens of albums -- browsing "Composer" would otherwise mean scrolling
        // past every movement of every symphony to find one. Leading with the "All <name> Songs"
        // folder (see MediaItemMapper.allGenreSongsItem's doc comment) keeps that flat list one tap
        // away, opened rather than played, without giving up hand-browsing by album.
        MediaIds.genreNameOrNull(parentId)?.let { genre ->
            return albumPlaybackSentinels(parentId) + mediaItemMapper.allGenreSongsItem(genre) +
                albumsForSongs(matchingSongsForGenre(genre))
        }
        MediaIds.albumArtistNameOrNull(parentId)?.let { albumArtist ->
            return albumPlaybackSentinels(parentId) + mediaItemMapper.allAlbumArtistSongsItem(albumArtist) +
                albumsForSongs(matchingSongsForAlbumArtist(albumArtist))
        }
        MediaIds.composerNameOrNull(parentId)?.let { composer ->
            return albumPlaybackSentinels(parentId) + mediaItemMapper.allComposerSongsItem(composer) +
                albumsForSongs(matchingSongsForComposer(composer))
        }
        MediaIds.decadeNameOrNull(parentId)?.let { decade ->
            return albumPlaybackSentinels(parentId) + mediaItemMapper.allDecadeSongsItem(decade) +
                albumsForSongs(matchingSongsForDecade(decade))
        }
        // The "All <name> Songs" folder's own children -- the flat list it opens onto.
        MediaIds.allArtistSongsIdOrNull(parentId)?.let {
            return songPlaybackSentinels(parentId) + songsForArtist(it)
        }
        MediaIds.allGenreSongsNameOrNull(parentId)?.let { return songPlaybackSentinels(parentId) + songsForGenre(it) }
        MediaIds.allAlbumArtistSongsNameOrNull(parentId)?.let {
            return songPlaybackSentinels(parentId) + songsForAlbumArtist(it)
        }
        MediaIds.allComposerSongsNameOrNull(parentId)?.let {
            return songPlaybackSentinels(parentId) + songsForComposer(it)
        }
        MediaIds.allDecadeSongsNameOrNull(parentId)?.let {
            return songPlaybackSentinels(parentId) + songsForDecade(it)
        }
        MediaIds.folderPathOrNull(parentId)?.let { path ->
            return folderChildren(path)
        }
        return null
    }

    private fun albumPlaybackSentinels(parentId: String): List<MediaItem> =
        listOf(mediaItemMapper.playAlbumsItem(parentId), mediaItemMapper.shuffleAlbumsItem(parentId))

    private fun songPlaybackSentinels(parentId: String): List<MediaItem> =
        listOf(mediaItemMapper.playSongsItem(parentId), mediaItemMapper.shuffleSongsItem(parentId))

    private suspend fun matchingSongsForGenre(name: String): List<SongEntity> =
        libraryRepository.songs.first().filter { (it.genreName ?: UNKNOWN_GENRE) == name }

    private suspend fun matchingSongsForAlbumArtist(name: String): List<SongEntity> =
        libraryRepository.songs.first().filter { (it.albumArtistName ?: UNKNOWN_ALBUM_ARTIST) == name }

    private suspend fun matchingSongsForComposer(name: String): List<SongEntity> =
        libraryRepository.songs.first().filter { (it.composerName ?: UNKNOWN_COMPOSER) == name }

    private suspend fun matchingSongsForDecade(name: String): List<SongEntity> {
        val albumDecadeById = libraryRepository.albums.first().associate { it.id to decadeOf(it.year) }
        return libraryRepository.songs.first().filter { (albumDecadeById[it.albumId] ?: UNKNOWN_DECADE) == name }
    }

    /** The distinct albums [songs] belong to, sorted by name. A genre/album artist/composer/decade
     * groups individual SONGS (see the doc comment on [MediaIds.genre]'s call site), so an album
     * with even one matching song qualifies whole -- browsing into it still shows every track on
     * that album, same as browsing into an artist's albums already does. */
    private suspend fun albumsForSongs(songs: List<SongEntity>): List<MediaItem> {
        val albumsById = libraryRepository.albums.first().associateBy { it.id }
        val artistNamesById = libraryRepository.artists.first().associate { it.id to it.name }
        return songs.mapNotNullTo(LinkedHashSet()) { albumsById[it.albumId] }
            .sortedBy { it.name.lowercase() }
            .map { album -> mediaItemMapper.toMediaItem(album, artistNamesById[album.artistId].orEmpty()) }
    }

    /** [prefix] is the full path of the folder being browsed into, or "" for the folders root
     * (directly under the scanned volume root). Children are that folder's immediate subfolders
     * (as browsable [MediaItem]s), followed by any songs whose folder is exactly [prefix] -- a
     * real file-browser-style listing, unlike the flat top-level-only list Settings' folder
     * exclusion picker shows. Songs with no folder path at all (API < 29, no RELATIVE_PATH column)
     * never appear here; [BrowseCategory.FOLDERS] simply isn't a useful category on those devices. */
    private suspend fun folderChildren(prefix: String): List<MediaItem> {
        val allSongs = libraryRepository.songs.first()
        val prefixDepth = if (prefix.isEmpty()) 0 else prefix.count { it == '/' } + 1
        val subfolders = allSongs.mapNotNull { it.folderPath }
            .filter { it != prefix && (prefix.isEmpty() || it.startsWith("$prefix/")) }
            .map { it.split('/').subList(0, prefixDepth + 1).joinToString("/") }
            .distinct()
            .sorted()
            .map { mediaItemMapper.toFolderItem(it) }
        val songsHere = mapSongs(
            allSongs.filter { it.folderPath == prefix }.sortedBy { it.title.lowercase() },
        )
        return subfolders + songsHere
    }

    /** Also used by [DashtoneLibrarySessionCallback.onAddMediaItems] to map a shuffle-built
     * queue of [SongEntity]s into fully-populated [MediaItem]s. */
    suspend fun mapSongs(songs: List<SongEntity>): List<MediaItem> {
        if (songs.isEmpty()) return emptyList()
        val albumNamesById = libraryRepository.albums.first().associate { it.id to it.name }
        val artistNamesById = libraryRepository.artists.first().associate { it.id to it.name }
        return songs.map { song ->
            mediaItemMapper.toMediaItem(
                song = song,
                albumName = albumNamesById[song.albumId].orEmpty(),
                artistName = artistNamesById[song.artistId].orEmpty(),
            )
        }
    }

    /** Resolves a persisted ordered list of song ids (see [QueueStateStore]) back into
     * [MediaItem]s, in the same order, silently dropping any id no longer in the library -- e.g. a
     * file deleted from disk since the queue was saved. Used only by
     * [DashtoneLibrarySessionCallback.onPlaybackResumption] to restore the whole queue on a fresh
     * connection, not just the one song [songsForArtist]/etc. resolve for an immediate play/shuffle
     * tap. */
    suspend fun songsByIds(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val songsById = libraryRepository.songs.first().associateBy { it.id }
        return mapSongs(ids.mapNotNull { songsById[it] })
    }

    private companion object {
        const val MAX_ROOT_TABS = 4
    }
}

// Internal grouping/lookup sentinel for an album with no year tag -- not user-facing here (see
// matchingSongsForDecade's use of it as a plain comparison key); MediaItemMapper.toDecadeItem
// substitutes a localized display string for it at render time. internal, not private, so
// MediaItemMapper (same module, different package) can reference the identical sentinel value.
internal const val UNKNOWN_DECADE = "Unknown Decade"
