package net.kourlas.dashtone.service.playback

import java.util.Base64

/** Structured media id scheme for the browse tree; keeps parsing centralized in one place. */
internal object MediaIds {
    const val ROOT = "root"
    const val ALBUMS_ROOT = "root/albums"
    const val ARTISTS_ROOT = "root/artists"
    const val GENRES_ROOT = "root/genres"
    const val ALBUM_ARTISTS_ROOT = "root/album_artists"
    const val COMPOSERS_ROOT = "root/composers"
    const val DECADES_ROOT = "root/decades"
    const val FOLDERS_ROOT = "root/folders"
    const val SONGS_ROOT = "root/songs"
    const val PLAYLISTS_ROOT = "root/playlists"

    /** Overflow tab for categories beyond the first three enabled ones -- see
     * [net.kourlas.dashtone.service.playback.LibraryBrowseTree]'s doc comment on the car UI's
     * fixed, non-scrolling tab strip. */
    const val MORE_ROOT = "root/more"

    /** Playable sentinels: not real browsable nodes, resolved into a materialized shuffle queue
     * (via one of [net.kourlas.dashtone.service.shuffle.ShufflePresets], or -- for the grouped ones
     * below -- [net.kourlas.dashtone.service.shuffle.ShuffleController.applyCategoryShuffle]) by
     * [net.kourlas.dashtone.service.playback.DashtoneLibrarySessionCallback.onAddMediaItems]. */
    const val SHUFFLE_ALL = "shuffle/all"
    const val SHUFFLE_ALBUMS = "shuffle/albums"
    const val SHUFFLE_ARTISTS = "shuffle/artists"
    const val SHUFFLE_GENRES = "shuffle/genres"
    const val SHUFFLE_ALBUM_ARTISTS = "shuffle/album_artists"
    const val SHUFFLE_COMPOSERS = "shuffle/composers"

    /** The always-natural-order counterpart to the shuffle sentinels above: category order AND
     * each category's own song order both stay unshuffled, regardless of the
     * [net.kourlas.dashtone.service.shuffle.CategoryShuffleMode] setting (which only governs what
     * the SHUFFLE_* entries do). */
    const val PLAY_ALL_SONGS = "play_all/songs"
    const val PLAY_ALL_ALBUMS = "play_all/albums"
    const val PLAY_ALL_ARTISTS = "play_all/artists"
    const val PLAY_ALL_GENRES = "play_all/genres"
    const val PLAY_ALL_ALBUM_ARTISTS = "play_all/album_artists"
    const val PLAY_ALL_COMPOSERS = "play_all/composers"

    private const val ALBUM_PREFIX = "album/"
    private const val ARTIST_PREFIX = "artist/"
    private const val SONG_PREFIX = "song/"
    private const val PLAYLIST_PREFIX = "playlist/"
    private const val GENRE_PREFIX = "genre/"
    private const val ALBUM_ARTIST_PREFIX = "album_artist/"
    private const val COMPOSER_PREFIX = "composer/"
    private const val DECADE_PREFIX = "decade/"
    private const val FOLDER_PREFIX = "folder/"
    private const val ALL_ARTIST_SONGS_PREFIX = "all_songs/artist/"
    private const val ALL_GENRE_SONGS_PREFIX = "all_songs/genre/"
    private const val ALL_ALBUM_ARTIST_SONGS_PREFIX = "all_songs/album_artist/"
    private const val ALL_COMPOSER_SONGS_PREFIX = "all_songs/composer/"
    private const val ALL_DECADE_SONGS_PREFIX = "all_songs/decade/"

    fun album(id: Long) = "$ALBUM_PREFIX$id"
    fun artist(id: Long) = "$ARTIST_PREFIX$id"
    fun song(id: Long) = "$SONG_PREFIX$id"
    fun playlist(id: Long) = "$PLAYLIST_PREFIX$id"

    // Genre/album artist have no numeric id like the other categories -- they're plain strings
    // read straight off SongEntity (see its doc comment) -- so the name itself, safely encoded,
    // IS the id. Base64 rather than raw/URI-escaped text since names can contain "/" (e.g. the
    // artist "AC/DC"), which would otherwise collide with this scheme's own "/"-delimited prefixes.
    fun genre(name: String) = "$GENRE_PREFIX${encode(name)}"
    fun albumArtist(name: String) = "$ALBUM_ARTIST_PREFIX${encode(name)}"
    fun composer(name: String) = "$COMPOSER_PREFIX${encode(name)}"
    fun decade(name: String) = "$DECADE_PREFIX${encode(name)}"

    /** [path] is the full folder path (e.g. "Music/ABBA/Gold"), same encoding rationale as
     * [genre]/[albumArtist]/etc -- a folder name itself could contain "/", on top of the scheme
     * already using "/" as a path separator between segments. */
    fun folder(path: String) = "$FOLDER_PREFIX${encode(path)}"

    // Playable-onward sentinels nested inside an artist/genre/album artist/composer/decade node --
    // see MediaItemMapper's allGenreSongsItem/etc and LibraryBrowseTree.children()'s artist/genre/
    // album artist/composer/decade branches, which list albums for hand-browsing but need a way to
    // queue every song in the category too, the same way SHUFFLE_ALL/PLAY_ALL_SONGS do at
    // SONGS_ROOT. A separate prefix from [artist]/[genre]/[albumArtist]/etc (rather than reusing
    // that same id for both the folder and its own "play everything" entry) keeps a tap on either
    // unambiguous: one always browses, the other always queues. Artist uses its own numeric-id
    // encoding ([idAfterPrefixOrNull], not [decodedAfterPrefixOrNull]) since -- unlike genre/album
    // artist/composer/decade, which have no backing entity and so use their own name as the id --
    // an artist already has a real id, and two different artists could share a display name.
    fun allArtistSongs(id: Long) = "$ALL_ARTIST_SONGS_PREFIX$id"
    fun allGenreSongs(name: String) = "$ALL_GENRE_SONGS_PREFIX${encode(name)}"
    fun allAlbumArtistSongs(name: String) = "$ALL_ALBUM_ARTIST_SONGS_PREFIX${encode(name)}"
    fun allComposerSongs(name: String) = "$ALL_COMPOSER_SONGS_PREFIX${encode(name)}"
    fun allDecadeSongs(name: String) = "$ALL_DECADE_SONGS_PREFIX${encode(name)}"

    fun albumIdOrNull(mediaId: String): Long? = mediaId.idAfterPrefixOrNull(ALBUM_PREFIX)
    fun artistIdOrNull(mediaId: String): Long? = mediaId.idAfterPrefixOrNull(ARTIST_PREFIX)
    fun songIdOrNull(mediaId: String): Long? = mediaId.idAfterPrefixOrNull(SONG_PREFIX)
    fun playlistIdOrNull(mediaId: String): Long? = mediaId.idAfterPrefixOrNull(PLAYLIST_PREFIX)
    fun genreNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(GENRE_PREFIX)
    fun albumArtistNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(ALBUM_ARTIST_PREFIX)
    fun composerNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(COMPOSER_PREFIX)
    fun decadeNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(DECADE_PREFIX)
    fun folderPathOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(FOLDER_PREFIX)
    fun allArtistSongsIdOrNull(mediaId: String): Long? = mediaId.idAfterPrefixOrNull(ALL_ARTIST_SONGS_PREFIX)
    fun allGenreSongsNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(ALL_GENRE_SONGS_PREFIX)
    fun allAlbumArtistSongsNameOrNull(mediaId: String): String? =
        mediaId.decodedAfterPrefixOrNull(ALL_ALBUM_ARTIST_SONGS_PREFIX)
    fun allComposerSongsNameOrNull(mediaId: String): String? =
        mediaId.decodedAfterPrefixOrNull(ALL_COMPOSER_SONGS_PREFIX)
    fun allDecadeSongsNameOrNull(mediaId: String): String? = mediaId.decodedAfterPrefixOrNull(ALL_DECADE_SONGS_PREFIX)

    // Generic playable sentinels wrapping an EXISTING node's own id (an artist/genre/album
    // artist/composer/decade id for the album pair, an album/playlist/all-songs id for the song
    // pair) rather than each getting their own per-category encoding the way allGenreSongs/etc.
    // above do -- there's no per-category name to embed in the title this time (see
    // MediaItemMapper.playAlbumsItem's doc comment), so there's nothing this scheme needs beyond
    // "which node does this queue?", and the node's own id already answers that.
    private const val PLAY_ALBUMS_PREFIX = "play_albums/"
    private const val SHUFFLE_ALBUMS_PREFIX = "shuffle_albums/"
    private const val PLAY_SONGS_PREFIX = "play_songs/"
    private const val SHUFFLE_SONGS_PREFIX = "shuffle_songs/"

    fun playAlbumsFor(parentId: String) = "$PLAY_ALBUMS_PREFIX$parentId"
    fun shuffleAlbumsFor(parentId: String) = "$SHUFFLE_ALBUMS_PREFIX$parentId"
    fun playSongsFor(parentId: String) = "$PLAY_SONGS_PREFIX$parentId"
    fun shuffleSongsFor(parentId: String) = "$SHUFFLE_SONGS_PREFIX$parentId"

    fun playAlbumsParentOrNull(mediaId: String): String? = mediaId.suffixAfterPrefixOrNull(PLAY_ALBUMS_PREFIX)
    fun shuffleAlbumsParentOrNull(mediaId: String): String? = mediaId.suffixAfterPrefixOrNull(SHUFFLE_ALBUMS_PREFIX)
    fun playSongsParentOrNull(mediaId: String): String? = mediaId.suffixAfterPrefixOrNull(PLAY_SONGS_PREFIX)
    fun shuffleSongsParentOrNull(mediaId: String): String? = mediaId.suffixAfterPrefixOrNull(SHUFFLE_SONGS_PREFIX)

    private fun String.suffixAfterPrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) substring(prefix.length) else null

    private fun String.idAfterPrefixOrNull(prefix: String): Long? =
        if (startsWith(prefix)) substring(prefix.length).toLongOrNull() else null

    private fun String.decodedAfterPrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) decode(substring(prefix.length)) else null

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }
}
