package net.kourlas.dashtone.service.playback

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import net.kourlas.dashtone.service.data.mediastore.AudioPermissionMonitor
import net.kourlas.dashtone.service.shuffle.Grouping
import net.kourlas.dashtone.service.shuffle.ShuffleController
import net.kourlas.dashtone.service.shuffle.ShuffleSettings
import net.kourlas.dashtone.service.shuffle.ShufflePresets

@UnstableApi
class DashtoneLibrarySessionCallback(
    private val browseTree: LibraryBrowseTree,
    private val shuffleController: ShuffleController,
    private val playbackResumptionProvider: PlaybackResumptionProvider,
    private val queueShuffleState: QueueShuffleState,
    private val audioPermissionMonitor: AudioPermissionMonitor,
    private val serviceScope: CoroutineScope,
) : MediaLibrarySession.Callback {

    // The legacy MediaController.playFromMediaId() path (see the comment on onAddMediaItems)
    // carries no information about which browsable list the tap came from -- just the bare song
    // id -- so there's no protocol-level way to know "the rest of this album" or "the rest of
    // this playlist" at that point. Remembering the most recently browsed song list is a practical
    // stand-in: a tap is always preceded by browsing into the list it came from, so whichever song
    // list was fetched last is, in practice, the one the tap belongs to. @Volatile since browsing
    // and playback requests can arrive from different session-callback threads.
    @Volatile
    private var lastBrowsedSongs: List<MediaItem> = emptyList()

    // This override grants every connecting controller full library (browsing) session commands,
    // since this app has no restricted/premium content. The inherited default doesn't grant these,
    // so without this override, connecting controllers would never actually get to call
    // onGetLibraryRoot/onGetChildren.
    // The custom repeat/shuffle session commands (see PlaybackCommandButtons) must be explicitly
    // granted here too, or the legacy bridge's isButtonCommandAvailable() check silently drops
    // their custom-layout buttons even though onCustomCommand is implemented.
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
        SessionCommands.Builder()
            .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands)
            .add(PlaybackCommandButtons.CYCLE_REPEAT_MODE)
            .add(PlaybackCommandButtons.TOGGLE_SHUFFLE)
            .build(),
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
    )

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = session.player
        when (customCommand.customAction) {
            PlaybackCommandButtons.CYCLE_REPEAT_MODE.customAction -> {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            PlaybackCommandButtons.TOGGLE_SHUFFLE.customAction -> {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
        // Android raises no callback when a runtime permission changes, so a fresh connection --
        // which is what "opening Dashtone" amounts to on AAOS -- is the moment to look again. The
        // user may have granted audio access from the system settings app rather than from
        // Dashtone's own permission row, which is the only other thing that calls refresh().
        // DashtoneMediaLibraryService's isGranted collector clears the session's playback error
        // as soon as this flips, so nothing further is needed here.
        audioPermissionMonitor.refresh()
        LibraryResult.ofItem(browseTree.root(), params)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
        val children = browseTree.children(parentId)
        if (children == null) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            val songs = children.filter { MediaIds.songIdOrNull(it.mediaId) != null }
            if (songs.isNotEmpty()) {
                lastBrowsedSongs = songs
            }
            // Must actually honor page/pageSize, not just the full, unsliced list: a host that
            // pages (e.g. requesting page 1 once the user scrolls past page 0) would otherwise get
            // that same full list back a second time, starting from its first entries again --
            // the "Play All"/"Shuffle All" sentinels included. Re-inserted at the host's current
            // scroll offset, that's what shows up as the shuffle icon appearing to replace a
            // regular row's icon partway down the list.
            val paged = children.pageSlice(page, pageSize)
            LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
        val item = browseTree.item(mediaId)
        if (item == null) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            LibraryResult.ofItem(item, null)
        }
    }

    // Resolves the shuffle browse-tree sentinels (which carry no playable URI of their own) into
    // the real, freshly-built-and-persisted shuffle queue. Ordinary items reach this callback in
    // two different shapes depending on the calling client: a modern Media3 controller passes
    // back the full MediaItem it already fetched from getChildren() (URI included), but car
    // hosts driving playback through the legacy framework MediaController.playFromMediaId() API
    // (confirmed live against the AAOS emulator's car.media) deliver a bare MediaItem with only
    // a mediaId -- no URI, no metadata. Passing that straight to the player crashes ExoPlayer's
    // media source factory with a NullPointerException on the missing URI, so anything without a
    // URI must be re-resolved via resolveItem() first -- which also expands a single tapped song
    // into the rest of the list it was browsed from (see lastBrowsedSongs), rather than queuing
    // just that one song.
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future {
        resolveQueue(mediaItems).toMutableList()
    }

    // The default onSetMediaItems (which is what the legacy playFromMediaId path above actually
    // goes through) just forwards onAddMediaItems' resolved list along with the CALLER's original
    // startIndex unchanged -- it never searches the resolved list for the item that was actually
    // requested. Since resolveQueue() can expand one requested song into a whole album/playlist,
    // leaving that unset would silently start playback at index 0 (e.g. tapping the third track
    // of an album would start playing the first track instead). Overriding this lets us search the
    // resolved list ourselves and start at the song that was actually tapped.
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
        val resolved = resolveQueue(mediaItems)
        val requestedMediaId = mediaItems.singleOrNull()?.mediaId
        val resolvedStartIndex = when {
            startIndex != C.INDEX_UNSET -> startIndex
            requestedMediaId != null -> resolved.indexOfFirst { it.mediaId == requestedMediaId }.coerceAtLeast(0)
            else -> 0
        }
        MediaSession.MediaItemsWithStartPosition(resolved, resolvedStartIndex, startPositionMs)
    }

    private suspend fun resolveQueue(mediaItems: List<MediaItem>): List<MediaItem> {
        val resolved = mutableListOf<MediaItem>()
        var shufflePreset: ShuffleSettings? = null
        // Non-null for a root "Play All X" queue or a scoped "Play Albums" queue (see
        // MediaItemMapper.playAlbumsItem) -- see QueueShuffleState.setNatural's doc comment for
        // why that's the case that still needs a grouping recorded despite being unshuffled.
        var playAllGrouping: Grouping? = null
        // Set only by a scoped "Shuffle Albums"/"Shuffle Songs" tap (LibraryBrowseTree.
        // albumsQueueFor/songsQueueFor) -- the natural-order fallback the live shuffle toggle
        // needs, mirroring what shufflePreset's branch below does for a root shuffle preset. A
        // scoped shuffle has no ShuffleSettings of its own naturalOrderFor could regenerate this
        // from (it's an ad-hoc song subset, not a library-wide grouping), so it's computed directly
        // alongside the shuffled queue instead.
        var scopedShuffleNatural: List<MediaItem>? = null
        var scopedShuffleGrouping: Grouping? = null
        for (item in mediaItems) {
            val playAlbumsParent = MediaIds.playAlbumsParentOrNull(item.mediaId)
            if (playAlbumsParent != null) {
                resolved += browseTree.albumsQueueFor(playAlbumsParent, shuffled = false).orEmpty()
                playAllGrouping = Grouping.ALBUM
                continue
            }
            val shuffleAlbumsParent = MediaIds.shuffleAlbumsParentOrNull(item.mediaId)
            if (shuffleAlbumsParent != null) {
                resolved += browseTree.albumsQueueFor(shuffleAlbumsParent, shuffled = true).orEmpty()
                scopedShuffleNatural = browseTree.albumsQueueFor(shuffleAlbumsParent, shuffled = false).orEmpty()
                scopedShuffleGrouping = Grouping.ALBUM
                continue
            }
            val playSongsParent = MediaIds.playSongsParentOrNull(item.mediaId)
            if (playSongsParent != null) {
                resolved += browseTree.songsQueueFor(playSongsParent, shuffled = false).orEmpty()
                continue
            }
            val shuffleSongsParent = MediaIds.shuffleSongsParentOrNull(item.mediaId)
            if (shuffleSongsParent != null) {
                resolved += browseTree.songsQueueFor(shuffleSongsParent, shuffled = true).orEmpty()
                scopedShuffleNatural = browseTree.songsQueueFor(shuffleSongsParent, shuffled = false).orEmpty()
                continue
            }
            when (item.mediaId) {
                MediaIds.SHUFFLE_ALL -> {
                    resolved += queueMediaItems(ShufflePresets.SHUFFLE_ALL)
                    shufflePreset = ShufflePresets.SHUFFLE_ALL
                }
                MediaIds.SHUFFLE_ALBUMS -> {
                    val (items, settings) = categoryShuffleQueue(Grouping.ALBUM)
                    resolved += items
                    shufflePreset = settings
                }
                MediaIds.SHUFFLE_ARTISTS -> {
                    val (items, settings) = categoryShuffleQueue(Grouping.ARTIST)
                    resolved += items
                    shufflePreset = settings
                }
                MediaIds.SHUFFLE_GENRES -> {
                    val (items, settings) = categoryShuffleQueue(Grouping.GENRE)
                    resolved += items
                    shufflePreset = settings
                }
                MediaIds.SHUFFLE_ALBUM_ARTISTS -> {
                    val (items, settings) = categoryShuffleQueue(Grouping.ALBUM_ARTIST)
                    resolved += items
                    shufflePreset = settings
                }
                MediaIds.SHUFFLE_COMPOSERS -> {
                    val (items, settings) = categoryShuffleQueue(Grouping.COMPOSER)
                    resolved += items
                    shufflePreset = settings
                }
                MediaIds.PLAY_ALL_SONGS -> resolved += playAllQueue(grouping = null)
                MediaIds.PLAY_ALL_ALBUMS -> {
                    resolved += playAllQueue(Grouping.ALBUM)
                    playAllGrouping = Grouping.ALBUM
                }
                MediaIds.PLAY_ALL_ARTISTS -> {
                    resolved += playAllQueue(Grouping.ARTIST)
                    playAllGrouping = Grouping.ARTIST
                }
                MediaIds.PLAY_ALL_GENRES -> {
                    resolved += playAllQueue(Grouping.GENRE)
                    playAllGrouping = Grouping.GENRE
                }
                MediaIds.PLAY_ALL_ALBUM_ARTISTS -> {
                    resolved += playAllQueue(Grouping.ALBUM_ARTIST)
                    playAllGrouping = Grouping.ALBUM_ARTIST
                }
                MediaIds.PLAY_ALL_COMPOSERS -> {
                    resolved += playAllQueue(Grouping.COMPOSER)
                    playAllGrouping = Grouping.COMPOSER
                }
                else -> resolved += resolveItem(item)
            }
        }
        // Tracks the natural (unshuffled) order of whatever queue just got established, so a
        // later live shuffle toggle (QueueShufflePlayer) has a correct fallback: a shuffle-preset
        // queue's fallback is the equivalent unshuffled ordering, not the preset's own shuffled
        // list; a scoped shuffle's fallback is the natural ordering computed alongside it above;
        // any other queue (album/playlist/song browsing, "Play All X"/"Play Albums"/"Play Songs",
        // already unshuffled) is its own fallback.
        val preset = shufflePreset
        when {
            preset != null -> {
                val grouping = preset.grouping.takeUnless { preset.shuffleAll }
                queueShuffleState.setShuffled(browseTree.mapSongs(shuffleController.naturalOrderFor(preset)), grouping)
            }
            scopedShuffleNatural != null -> queueShuffleState.setShuffled(scopedShuffleNatural, scopedShuffleGrouping)
            else -> queueShuffleState.setNatural(resolved, playAllGrouping)
        }
        return resolved
    }

    // "Shuffle Albums"/"Shuffle Artists"/etc.: grouping is fixed by which entry was tapped, but
    // which axis actually gets randomized comes from the user's CategoryShuffleMode setting (see
    // ShuffleController.applyCategoryShuffle). Returning the resolved ShuffleSettings alongside the
    // mapped items lets the caller reuse the exact same downstream natural-order-fallback logic a
    // hardcoded preset would have used, without needing to know which mode was actually in effect.
    private suspend fun categoryShuffleQueue(grouping: Grouping): Pair<List<MediaItem>, ShuffleSettings> {
        val queue = shuffleController.applyCategoryShuffle(grouping)
        return browseTree.mapSongs(queue.songs) to queue.settings
    }

    // "Play All X": both axes explicitly unshuffled, regardless of the CategoryShuffleMode
    // setting (that setting only governs what the SHUFFLE_* entries do). [grouping] null means
    // "Play All Songs" -- a flat list with no category structure, same as ShuffleSettings.shuffleAll.
    private suspend fun playAllQueue(grouping: Grouping?): List<MediaItem> {
        val settings = if (grouping == null) ShuffleSettings(shuffleAll = true) else ShuffleSettings(grouping = grouping)
        return browseTree.mapSongs(shuffleController.naturalOrderFor(settings))
    }

    // Expanding to the containing list (rather than just the tapped song) means skip-previous/
    // skip-next and the live shuffle toggle all have a real, correctly-ordered queue to work
    // with -- matching how tapping a track in Albums/Artists/Music apps generally behaves.
    private suspend fun resolveItem(item: MediaItem): List<MediaItem> {
        if (item.localConfiguration != null) return listOf(item)
        // Voice actions (Google Assistant, "play some jazz") reach onPlayFromSearch on the legacy
        // bridge, which Media3 forwards here as a bare MediaItem with DEFAULT_MEDIA_ID carrying a
        // search query and/or extras instead of a real mediaId -- see
        // https://developer.android.com/training/cars/media/voice-actions. Checking mediaId alone
        // (not also requiring a non-blank query) matters: a fully generic "play music" reaches here
        // with an empty query and no extras, and still needs a sensible fallback, not a no-op.
        if (item.mediaId == MediaItem.DEFAULT_MEDIA_ID) {
            return resolveVoiceRequest(item.requestMetadata.searchQuery, item.requestMetadata.extras)
        }
        val containingList = lastBrowsedSongs.takeIf { list -> list.any { it.mediaId == item.mediaId } }
        if (containingList != null) return containingList
        return listOf(browseTree.item(item.mediaId) ?: item)
    }

    // Assistant/voice search delivers a structured category hint (EXTRA_MEDIA_FOCUS + the matching
    // EXTRA_MEDIA_ARTIST/ALBUM/GENRE/PLAYLIST/TITLE) whenever the query was specific enough to
    // classify -- e.g. "play some jazz" arrives as GENRE focus + genre="jazz", not just raw text.
    // Preferring that structured hint over plain-text search resolves ambiguity a substring search
    // alone couldn't (an artist and a song can share a name). Falls through to plain-text search,
    // and finally to Shuffle All for a fully generic "play music" with no query and no usable extras.
    internal suspend fun resolveVoiceRequest(query: String?, extras: Bundle?): List<MediaItem> {
        val focusResult = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_GENRE)?.let { resolveGenreByName(it) }
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.let { resolveArtistByName(it) }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.let { resolveAlbumByName(it) }
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)?.let { resolvePlaylistByName(it) }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE ->
                extras.getString(MediaStore.EXTRA_MEDIA_TITLE)?.let { resolveSongByTitle(it) }
            else -> null
        }
        if (focusResult != null) return focusResult

        val trimmedQuery = query?.trim().orEmpty()
        if (trimmedQuery.isEmpty()) return queueMediaItems(ShufflePresets.SHUFFLE_ALL)
        return resolveTextSearch(trimmedQuery)
    }

    // The top search hit determines what plays: a song plays alone (there's no natural "queue"
    // implied by a bare voice search the way there is for an album/playlist), an artist hit plays
    // all their songs (not their album list -- see songsForArtist's doc comment), and an album or
    // playlist hit expands to everything inside it, same as tapping into one by hand.
    private suspend fun resolveTextSearch(query: String): List<MediaItem> {
        val topResult = browseTree.search(query).firstOrNull() ?: return emptyList()
        val mediaId = topResult.mediaId
        // Every non-song category is resolved to its flat song list explicitly, never via a bare
        // children(mediaId): that now leads with "Play Albums"/"Shuffle Albums"/"Play Songs"/
        // "Shuffle Songs" sentinels (see LibraryBrowseTree.children()), none of which belong in a
        // resolved playback queue.
        MediaIds.songIdOrNull(mediaId)?.let { return listOf(topResult) }
        MediaIds.artistIdOrNull(mediaId)?.let { return browseTree.songsForArtist(it) }
        MediaIds.genreNameOrNull(mediaId)?.let { return browseTree.songsForGenre(it) }
        MediaIds.albumArtistNameOrNull(mediaId)?.let { return browseTree.songsForAlbumArtist(it) }
        MediaIds.composerNameOrNull(mediaId)?.let { return browseTree.songsForComposer(it) }
        MediaIds.albumIdOrNull(mediaId)?.let { return browseTree.songsQueueFor(mediaId, shuffled = false) ?: listOf(topResult) }
        MediaIds.playlistIdOrNull(mediaId)?.let { return browseTree.songsQueueFor(mediaId, shuffled = false) ?: listOf(topResult) }
        return listOf(topResult)
    }

    private suspend fun resolveGenreByName(name: String): List<MediaItem>? {
        val hit = browseTree.search(name).firstOrNull { MediaIds.genreNameOrNull(it.mediaId) != null } ?: return null
        return browseTree.songsForGenre(MediaIds.genreNameOrNull(hit.mediaId) ?: return null)
    }

    private suspend fun resolveArtistByName(name: String): List<MediaItem>? {
        val hit = browseTree.search(name).firstOrNull { MediaIds.artistIdOrNull(it.mediaId) != null } ?: return null
        return browseTree.songsForArtist(MediaIds.artistIdOrNull(hit.mediaId) ?: return null)
    }

    private suspend fun resolveAlbumByName(name: String): List<MediaItem>? {
        val hit = browseTree.search(name).firstOrNull { MediaIds.albumIdOrNull(it.mediaId) != null } ?: return null
        return browseTree.songsQueueFor(hit.mediaId, shuffled = false)
    }

    private suspend fun resolvePlaylistByName(name: String): List<MediaItem>? {
        val hit = browseTree.search(name).firstOrNull { MediaIds.playlistIdOrNull(it.mediaId) != null } ?: return null
        return browseTree.songsQueueFor(hit.mediaId, shuffled = false)
    }

    private suspend fun resolveSongByTitle(title: String): List<MediaItem>? {
        val hit = browseTree.search(title).firstOrNull { MediaIds.songIdOrNull(it.mediaId) != null } ?: return null
        return listOf(hit)
    }

    // Lets the car offer a "resume" card for the whole last queue -- not just the one track that
    // was playing -- without the user needing to browse back to it (e.g. after a reboot or
    // relaunch). When there's nothing to resume (fresh install, nothing ever played), the future
    // must FAIL rather than succeed with an empty list -- the framework's own default
    // implementation does exactly that (Futures.immediateFailedFuture(UnsupportedOperationException)).
    // Returning a successful-but-empty result instead made the car host still render a "Now
    // Playing" bar, just a blank one.
    //
    // The 3-arg overload, not the deprecated 2-arg one: isForPlayback distinguishes an actual
    // resume (the returned queue is about to be loaded into the player) from a metadata-only query
    // (a controller asking "what would resume" for display, with no intention of loading it) --
    // queueShuffleState must only be primed in the former case, since priming it for a query that
    // never reaches the player would leave it describing a queue that was never actually loaded.
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
        val resumption = playbackResumptionProvider.resumptionState() ?: throw UnsupportedOperationException()
        if (isForPlayback) {
            if (resumption.isShuffled) {
                queueShuffleState.setShuffled(resumption.naturalItems, resumption.grouping)
            } else {
                queueShuffleState.setNatural(resumption.items, resumption.grouping)
            }
        }
        MediaSession.MediaItemsWithStartPosition(resumption.items, resumption.startIndex, 0L)
    }

    // Not chained onto onSearch's result: onGetSearchResult can be called on its own (e.g. from
    // the legacy MediaBrowserCompat.search() path), so it must be able to compute results from the
    // query alone rather than relying on a prior onSearch call having cached anything.
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = serviceScope.future {
        val results = browseTree.search(query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
        val results = browseTree.search(query).pageSlice(page, pageSize)
        LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
    }

    private suspend fun queueMediaItems(settings: ShuffleSettings): List<MediaItem> {
        val queue = shuffleController.apply(settings)
        return browseTree.mapSongs(queue.songs)
    }
}

/** The [page]-th slice of [pageSize] items, clamped to what the list actually holds. Kept free of
 * Media3 types so it's directly unit-testable, the same way [reorderForShuffleToggle] is.
 *
 * The offset is computed in Long because a host is free to ask for an unbounded page size, and
 * Int.MAX_VALUE is exactly what Media3's legacy bridge passes when a browser sets no page options
 * at all. An Int multiply overflows to a negative offset for any page past the first, and
 * [List.drop] rejects a negative count outright -- which surfaces as the whole browse request
 * failing rather than as an empty page. A non-positive [pageSize] is empty for the same reason. */
internal fun <T> List<T>.pageSlice(page: Int, pageSize: Int): List<T> {
    if (pageSize <= 0) return emptyList()
    val offset = (page.toLong() * pageSize).coerceIn(0L, size.toLong()).toInt()
    return drop(offset).take(pageSize)
}
