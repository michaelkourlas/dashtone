package net.kourlas.dashtone.service.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import net.kourlas.dashtone.service.R
import net.kourlas.dashtone.service.albumart.AlbumArtUris
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import net.kourlas.dashtone.service.shuffle.UNKNOWN_ALBUM_ARTIST
import net.kourlas.dashtone.service.shuffle.UNKNOWN_COMPOSER
import net.kourlas.dashtone.service.shuffle.UNKNOWN_GENRE
import javax.inject.Inject

/** Maps library entities to Media3 [MediaItem]s for the browse tree and playback queue. */
class MediaItemMapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Search-result subtitles (see toMediaItem(ArtistEntity, ...)'s own doc comment for why they
    // exist) -- exposed here, not as string literals at LibraryBrowseTree.search()'s call sites,
    // since this class is the sole owner of resource/Context access in the browse-tree layer.
    val artistSubtitle: String get() = context.getString(R.string.subtitle_artist)
    val playlistSubtitle: String get() = context.getString(R.string.subtitle_playlist)
    val genreSubtitle: String get() = context.getString(R.string.subtitle_genre)
    val albumArtistSubtitle: String get() = context.getString(R.string.subtitle_album_artist)
    val composerSubtitle: String get() = context.getString(R.string.subtitle_composer)

    // Every node explicitly hints how ITS OWN children should render (grid vs list), rather than
    // relying on a single tree-wide default: album lists read best as a grid (cover art), while
    // menus and track lists read best as a list.
    fun rootItem(): MediaItem = folder(
        MediaIds.ROOT,
        context.getString(R.string.app_name),
        browsableChildStyle = LIST,
        playableChildStyle = LIST,
    )

    fun albumsRootItem(): MediaItem = folder(
        MediaIds.ALBUMS_ROOT, context.getString(R.string.category_albums),
        browsableChildStyle = GRID, iconName = "ic_category_albums",
    )
    fun artistsRootItem(): MediaItem = folder(
        MediaIds.ARTISTS_ROOT, context.getString(R.string.category_artists),
        browsableChildStyle = LIST, iconName = "ic_category_artists",
    )
    fun genresRootItem(): MediaItem = folder(
        MediaIds.GENRES_ROOT, context.getString(R.string.category_genres),
        browsableChildStyle = LIST, iconName = "ic_category_genres",
    )
    fun albumArtistsRootItem(): MediaItem = folder(
        MediaIds.ALBUM_ARTISTS_ROOT, context.getString(R.string.category_album_artists),
        browsableChildStyle = LIST, iconName = "ic_category_album_artists",
    )
    fun composersRootItem(): MediaItem = folder(
        MediaIds.COMPOSERS_ROOT, context.getString(R.string.category_composers),
        browsableChildStyle = LIST, iconName = "ic_category_composers",
    )
    fun decadesRootItem(): MediaItem = folder(
        MediaIds.DECADES_ROOT, context.getString(R.string.category_decades),
        browsableChildStyle = LIST, iconName = "ic_category_decades",
    )
    fun foldersRootItem(): MediaItem = folder(
        MediaIds.FOLDERS_ROOT, context.getString(R.string.category_folders),
        browsableChildStyle = LIST, playableChildStyle = LIST, iconName = "ic_category_folders",
    )
    fun songsRootItem(): MediaItem = folder(
        MediaIds.SONGS_ROOT, context.getString(R.string.category_songs),
        playableChildStyle = LIST, iconName = "ic_category_songs",
    )
    fun playlistsRootItem(): MediaItem = folder(
        MediaIds.PLAYLISTS_ROOT, context.getString(R.string.category_playlists),
        browsableChildStyle = LIST, iconName = "ic_category_playlists",
    )
    fun moreRootItem(): MediaItem = folder(
        MediaIds.MORE_ROOT, context.getString(R.string.category_more),
        browsableChildStyle = LIST, iconName = "ic_category_more",
    )

    fun shuffleAllItem(): MediaItem =
        playableSentinel(MediaIds.SHUFFLE_ALL, context.getString(R.string.shuffle_all_songs), SHUFFLE_ICON)
    fun shuffleAllAlbumsItem(): MediaItem =
        playableSentinel(MediaIds.SHUFFLE_ALBUMS, context.getString(R.string.shuffle_all_albums), SHUFFLE_ICON)
    fun shuffleAllArtistsItem(): MediaItem =
        playableSentinel(MediaIds.SHUFFLE_ARTISTS, context.getString(R.string.shuffle_all_artists), SHUFFLE_ICON)
    fun shuffleAllGenresItem(): MediaItem =
        playableSentinel(MediaIds.SHUFFLE_GENRES, context.getString(R.string.shuffle_all_genres), SHUFFLE_ICON)
    fun shuffleAllAlbumArtistsItem(): MediaItem = playableSentinel(
        MediaIds.SHUFFLE_ALBUM_ARTISTS, context.getString(R.string.shuffle_all_album_artists), SHUFFLE_ICON,
    )
    fun shuffleAllComposersItem(): MediaItem = playableSentinel(
        MediaIds.SHUFFLE_COMPOSERS, context.getString(R.string.shuffle_all_composers), SHUFFLE_ICON,
    )

    fun playAllSongsItem(): MediaItem =
        playableSentinel(MediaIds.PLAY_ALL_SONGS, context.getString(R.string.play_all_songs), PLAY_ALL_ICON)
    fun playAllAlbumsItem(): MediaItem =
        playableSentinel(MediaIds.PLAY_ALL_ALBUMS, context.getString(R.string.play_all_albums), PLAY_ALL_ICON)
    fun playAllArtistsItem(): MediaItem =
        playableSentinel(MediaIds.PLAY_ALL_ARTISTS, context.getString(R.string.play_all_artists), PLAY_ALL_ICON)
    fun playAllGenresItem(): MediaItem =
        playableSentinel(MediaIds.PLAY_ALL_GENRES, context.getString(R.string.play_all_genres), PLAY_ALL_ICON)
    fun playAllAlbumArtistsItem(): MediaItem = playableSentinel(
        MediaIds.PLAY_ALL_ALBUM_ARTISTS, context.getString(R.string.play_all_album_artists), PLAY_ALL_ICON,
    )
    fun playAllComposersItem(): MediaItem = playableSentinel(
        MediaIds.PLAY_ALL_COMPOSERS, context.getString(R.string.play_all_composers), PLAY_ALL_ICON,
    )

    // Nested inside a specific artist/genre/album artist/composer/decade node, alongside its
    // albums -- titled with the category's own name (there's no enclosing "Artists"-style screen
    // title to lean on here the way ARTISTS_ROOT/etc have) so it reads as "All Rock Songs" rather
    // than a bare, contextless "All Songs". Browsable, not playable: it opens onto the flat song
    // list for the category (see LibraryBrowseTree.children()'s all-songs branches), the same way
    // tapping into an album opens its track list, rather than starting playback the instant it's
    // tapped. allArtistSongsItem takes the id separately from the display name (unlike the other
    // four, where the name IS the id) -- see MediaIds.allArtistSongs's doc comment for why.
    fun allArtistSongsItem(artistId: Long, name: String): MediaItem =
        folder(MediaIds.allArtistSongs(artistId), allSongsTitle(name), playableChildStyle = LIST)
    fun allGenreSongsItem(name: String): MediaItem =
        folder(MediaIds.allGenreSongs(name), allSongsTitle(name), playableChildStyle = LIST)
    fun allAlbumArtistSongsItem(name: String): MediaItem =
        folder(MediaIds.allAlbumArtistSongs(name), allSongsTitle(name), playableChildStyle = LIST)
    fun allComposerSongsItem(name: String): MediaItem =
        folder(MediaIds.allComposerSongs(name), allSongsTitle(name), playableChildStyle = LIST)
    fun allDecadeSongsItem(name: String): MediaItem =
        folder(MediaIds.allDecadeSongs(name), allSongsTitle(name), playableChildStyle = LIST)

    private fun allSongsTitle(name: String): String = context.getString(R.string.all_name_songs, name)

    // Nested in every album-list view below the root (an artist's albums, or a genre/album
    // artist/composer/decade's albums) -- generic, fixed titles rather than "Play Rock Albums"-
    // style category names: unlike allGenreSongsItem/etc above, the same pair appears verbatim in
    // several different kinds of screen (an artist screen, a genre screen, ...), so there's no
    // single category-name style that would fit all of them, and the screen's own title already
    // supplies that context. [parentId] is the album-list node's own id (MediaIds.artist(id),
    // MediaIds.genre(name), etc.) -- see MediaIds.playAlbumsFor's doc comment for why that's
    // reused directly rather than this scheme inventing its own per-category encoding.
    fun playAlbumsItem(parentId: String): MediaItem =
        playableSentinel(MediaIds.playAlbumsFor(parentId), context.getString(R.string.play_albums), PLAY_ALL_ICON)
    fun shuffleAlbumsItem(parentId: String): MediaItem = playableSentinel(
        MediaIds.shuffleAlbumsFor(parentId), context.getString(R.string.shuffle_albums), SHUFFLE_ICON,
    )

    // Nested in every song-list view below the root (an album's or playlist's tracks, or a genre/
    // album artist/composer/decade's "All <name> Songs" list) -- same generic-title reasoning as
    // playAlbumsItem/shuffleAlbumsItem above.
    fun playSongsItem(parentId: String): MediaItem =
        playableSentinel(MediaIds.playSongsFor(parentId), context.getString(R.string.play_songs), PLAY_ALL_ICON)
    fun shuffleSongsItem(parentId: String): MediaItem = playableSentinel(
        MediaIds.shuffleSongsFor(parentId), context.getString(R.string.shuffle_songs), SHUFFLE_ICON,
    )

    fun toMediaItem(album: AlbumEntity, artistName: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(album.name)
            .setArtist(artistName)
            .setArtworkUri(albumArtUri(album.id))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the track list
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.album(album.id))
            .setMediaMetadata(metadata)
            .build()
    }

    // subtitle is unused by ordinary browsing (a screen titled "Artists" already tells you what
    // you're looking at) -- it exists for LibraryBrowseTree.search(), whose flat, interleaved
    // result list has no such screen title to lean on. Classical recordings routinely tag artist,
    // album artist, and composer identically (confirmed against this project's own test library:
    // "Frédéric Chopin" in all three ID3 frames), so a search for that name legitimately produces
    // three distinct, correctly-functioning hits with the exact same title; the subtitle is what
    // lets a search result list show them as "Frédéric Chopin -- Artist" / "-- Album Artist" /
    // "-- Composer" instead of three seemingly-duplicate rows.
    fun toMediaItem(artist: ArtistEntity, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(artist.name)
            .apply { subtitle?.let { setArtist(it) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(childStyleExtras(browsableChildStyle = GRID)) // its children are that artist's albums
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.artist(artist.id))
            .setMediaMetadata(metadata)
            .build()
    }

    // Genre/album artist have no backing entity (see SongEntity's doc comment on genreName), so
    // there's no toMediaItem(GenreEntity) overload -- these build a browsable folder straight from
    // the group's name string, the same one SongEntity.genreName/albumArtistName carries.
    //
    // [name] doubles as the id-encoding source (MediaIds.genre(name)/etc, matched back against
    // songs missing the tag by ShuffleQueueBuilders.UNKNOWN_GENRE/etc -- see those constants' own
    // doc comment) and, ordinarily, the display title. The two need to agree for every real
    // genre/album artist/composer, but not for the "missing tag" bucket: its id must stay the
    // fixed internal sentinel for that lookup to keep working, while its title should still be a
    // real, localized string rather than that sentinel's literal English text. Substituting the
    // resource string only for the title, right here, keeps that distinction local to the one
    // place that renders these for display, without asking the grouping/lookup layer to know
    // anything about locales.
    fun toGenreItem(name: String, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (name == UNKNOWN_GENRE) context.getString(R.string.unknown_genre) else name)
            .apply { subtitle?.let { setArtist(it) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the song list
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.genre(name)).setMediaMetadata(metadata).build()
    }

    fun toAlbumArtistItem(name: String, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (name == UNKNOWN_ALBUM_ARTIST) context.getString(R.string.unknown_album_artist) else name)
            .apply { subtitle?.let { setArtist(it) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the song list
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.albumArtist(name)).setMediaMetadata(metadata).build()
    }

    fun toComposerItem(name: String, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (name == UNKNOWN_COMPOSER) context.getString(R.string.unknown_composer) else name)
            .apply { subtitle?.let { setArtist(it) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the song list
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.composer(name)).setMediaMetadata(metadata).build()
    }

    // Decades are a plain browse category (no matching Grouping/shuffle preset -- see
    // LibraryBrowseTree's DECADES_ROOT comment for why), so unlike genre/album artist/composer
    // there's no corresponding shuffleDecadesItem().
    fun toDecadeItem(name: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(if (name == UNKNOWN_DECADE) context.getString(R.string.unknown_decade) else name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the song list
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.decade(name)).setMediaMetadata(metadata).build()
    }

    // A folder's children can be a mix of subfolders and songs directly inside it (both content
    // style hints set), unlike genre/album artist/composer/decade whose children are always just a
    // flat song list. Title shows only the last path segment ("Gold", not "Music/ABBA/Gold") --
    // the full path is only meaningful as an id, not something to show the user browsing folder by
    // folder, one level at a time.
    fun toFolderItem(fullPath: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(fullPath.substringAfterLast('/'))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(childStyleExtras(browsableChildStyle = LIST, playableChildStyle = LIST))
            .build()
        return MediaItem.Builder().setMediaId(MediaIds.folder(fullPath)).setMediaMetadata(metadata).build()
    }

    fun toMediaItem(playlist: PlaylistEntity, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(playlist.name)
            .apply { subtitle?.let { setArtist(it) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(childStyleExtras(playableChildStyle = LIST)) // its children are the track list, in file order
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.playlist(playlist.id))
            .setMediaMetadata(metadata)
            .build()
    }

    fun toMediaItem(song: SongEntity, albumName: String, artistName: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(artistName)
            .setAlbumTitle(albumName)
            .setArtworkUri(albumArtUri(song.albumId))
            .setTrackNumber(song.trackNumber)
            .setDiscNumber(song.discNumber)
            .setGenre(song.genreName)
            .setAlbumArtist(song.albumArtistName)
            .setComposer(song.composerName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.song(song.id))
            .setUri(song.contentUri.toUri())
            .setMediaMetadata(metadata)
            .build()
    }

    // Deliberately not setting DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS here. A legacy
    // MediaBrowserCompat host (car.media) renders COMPLETION_STATUS_NOT_PLAYED as a per-item "new"
    // dot -- the right cue for episodic content (podcasts, audiobooks), but on a music library it
    // shows on every single track, since Dashtone has no per-song listening-progress feature:
    // QueueStateStore remembers the whole current queue for resuming Dashtone itself, but never a
    // per-song position. A previous attempt reported NOT_PLAYED/0.0 unconditionally
    // to keep a just-finished or skipped-away-from track from showing a stale host-cached progress
    // bar, but that traded a rare stale-progress edge case for a permanent, unwanted dot on every
    // song in every list -- the worse tradeoff for a music app.

    // No URI: selecting this triggers DashtoneLibrarySessionCallback.onAddMediaItems, which
    // resolves it into the real materialized queue rather than playing it directly. iconName is
    // shared across every "Play All X"/"Shuffle X" entry (PLAY_ALL_ICON/SHUFFLE_ICON below) rather
    // than per-category: the category is already carried by the title and by which folder the
    // entry sits in, so a generic play/shuffle glyph pairs the two sentinel rows the same way most
    // music apps pair a "play" and "shuffle" action, rather than duplicating the 12 per-category
    // icons for entries that aren't actually about the category's own identity.
    private fun playableSentinel(id: String, title: String, iconName: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply { iconName?.let { setArtworkUri(iconUri(it)) } }
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun folder(
        id: String,
        title: String,
        browsableChildStyle: Int? = null,
        playableChildStyle: Int? = null,
        iconName: String? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setExtras(childStyleExtras(browsableChildStyle, playableChildStyle))
            .apply { iconName?.let { setArtworkUri(iconUri(it)) } }
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    // android.resource:// URIs are resolved cross-process via PackageManager, so no content
    // provider is needed for these static per-category icons (unlike album art). MUST use the
    // symbolic "package/type/name" form, not "package/<raw packed resource int>" -- confirmed via
    // a live car.media crash: passing the raw int (R.drawable.ic_category_albums's compiled
    // value) killed car.media's whole process with Resources$NotFoundException: Resource ID #0x0,
    // because com.android.car.apps.common.UriUtils.getDrawable() resolves these by looking up the
    // name segment through Resources.getIdentifier(), not by treating it as an already-packed ID
    // (which also sidesteps :service's resource IDs being reassigned when merged into :app's final
    // table at build time -- name-based lookup doesn't care what the numeric ID ends up being).
    // The package segment is read from the running app rather than written out: :service's
    // resources are merged into :app's final resource table at build time and exist only under the
    // app's real applicationId at runtime, which this module's own namespace
    // ("net.kourlas.dashtone.service") is not, and which a variant carrying an applicationIdSuffix
    // would change out from under a literal.
    private fun iconUri(name: String): Uri = "android.resource://${context.packageName}/drawable/$name".toUri()

    private fun childStyleExtras(browsableChildStyle: Int? = null, playableChildStyle: Int? = null): Bundle =
        Bundle().apply {
            browsableChildStyle?.let { putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
            playableChildStyle?.let { putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
        }

    private fun albumArtUri(albumId: Long): Uri = AlbumArtUris.forAlbum(context, albumId)

    private companion object {
        const val LIST = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        const val GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        const val PLAY_ALL_ICON = "ic_action_play_all"
        const val SHUFFLE_ICON = "ic_action_shuffle"
    }
}
