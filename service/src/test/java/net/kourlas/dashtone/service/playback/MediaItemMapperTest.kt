package net.kourlas.dashtone.service.playback

import android.content.Context
import androidx.media.utils.MediaConstants
import androidx.test.core.app.ApplicationProvider
import net.kourlas.dashtone.service.data.local.AlbumEntity
import net.kourlas.dashtone.service.data.local.ArtistEntity
import net.kourlas.dashtone.service.data.local.PlaylistEntity
import net.kourlas.dashtone.service.data.local.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric because these mapping methods build real android.net.Uri values
 * (artwork URIs, song content URIs). */
@RunWith(RobolectricTestRunner::class)
class MediaItemMapperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mapper = MediaItemMapper(context)

    // Icon URIs name the running app's own package, so the expected authority is read from the
    // same context the mapper holds. Under Robolectric that is :service's namespace; in the app it
    // is the real applicationId, which is the value these URIs have to carry for car.media to
    // resolve them. See MediaItemMapper.iconUri.

    @Test
    fun `rootItem is browsable, not playable, and lists as LIST style`() {
        val item = mapper.rootItem()

        assertEquals(MediaIds.ROOT, item.mediaId)
        assertEquals("Dashtone", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertFalse(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun `albumsRootItem hints its children render as a grid`() {
        val extras = mapper.albumsRootItem().mediaMetadata.extras!!

        assertEquals(
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
    }

    @Test
    fun `songsRootItem hints its children render as a list`() {
        val extras = mapper.songsRootItem().mediaMetadata.extras!!

        assertEquals(
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE),
        )
    }

    @Test
    fun `root shuffle sentinels are playable, not browsable, carry no URI, and say Shuffle All`() {
        val item = mapper.shuffleAllAlbumsItem()

        assertEquals(MediaIds.SHUFFLE_ALBUMS, item.mediaId)
        assertEquals("Shuffle All Albums", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.isPlayable!!)
        assertFalse(item.mediaMetadata.isBrowsable!!)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `play-all sentinels are playable, not browsable, and carry no URI`() {
        val item = mapper.playAllAlbumsItem()

        assertEquals(MediaIds.PLAY_ALL_ALBUMS, item.mediaId)
        assertEquals("Play All Albums", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.isPlayable!!)
        assertFalse(item.mediaMetadata.isBrowsable!!)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `every root category has both a play-all and a shuffle-all sentinel with distinct media ids`() {
        val pairs = listOf(
            mapper.playAllAlbumsItem() to mapper.shuffleAllAlbumsItem(),
            mapper.playAllArtistsItem() to mapper.shuffleAllArtistsItem(),
            mapper.playAllGenresItem() to mapper.shuffleAllGenresItem(),
            mapper.playAllAlbumArtistsItem() to mapper.shuffleAllAlbumArtistsItem(),
            mapper.playAllComposersItem() to mapper.shuffleAllComposersItem(),
            mapper.playAllSongsItem() to mapper.shuffleAllItem(),
        )
        for ((playAll, shuffle) in pairs) {
            assertTrue(playAll.mediaMetadata.isPlayable!!)
            assertTrue(shuffle.mediaMetadata.isPlayable!!)
            assertTrue(playAll.mediaId != shuffle.mediaId)
        }
    }

    @Test
    fun `scoped Play Albums, Shuffle Albums, Play Songs, and Shuffle Songs sentinels wrap the parent id, playable, no URI`() {
        val parentId = MediaIds.artist(10)
        val items = listOf(
            mapper.playAlbumsItem(parentId) to "Play Albums",
            mapper.shuffleAlbumsItem(parentId) to "Shuffle Albums",
            mapper.playSongsItem(parentId) to "Play Songs",
            mapper.shuffleSongsItem(parentId) to "Shuffle Songs",
        )
        val mediaIds = mutableSetOf<String>()
        for ((item, title) in items) {
            assertEquals(title, item.mediaMetadata.title.toString())
            assertTrue(item.mediaMetadata.isPlayable!!)
            assertFalse(item.mediaMetadata.isBrowsable!!)
            assertNull(item.localConfiguration)
            mediaIds += item.mediaId
        }
        assertEquals(4, mediaIds.size)
        assertEquals(parentId, MediaIds.playAlbumsParentOrNull(mapper.playAlbumsItem(parentId).mediaId))
        assertEquals(parentId, MediaIds.shuffleAlbumsParentOrNull(mapper.shuffleAlbumsItem(parentId).mediaId))
        assertEquals(parentId, MediaIds.playSongsParentOrNull(mapper.playSongsItem(parentId).mediaId))
        assertEquals(parentId, MediaIds.shuffleSongsParentOrNull(mapper.shuffleSongsItem(parentId).mediaId))
    }

    @Test
    fun `song mapping carries title, artist, album, track info, and a playable content URI`() {
        val song = SongEntity(
            id = 42, title = "Dancing Queen", trackNumber = 3, discNumber = 1, durationMs = 200_000,
            albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/42",
            dateAdded = 0, dateModified = 0, genreName = "Pop", albumArtistName = "ABBA", composerName = "Andersson",
        )

        val item = mapper.toMediaItem(song, albumName = "ABBA Gold", artistName = "ABBA")

        assertEquals(MediaIds.song(42), item.mediaId)
        assertEquals("Dancing Queen", item.mediaMetadata.title.toString())
        assertEquals("ABBA", item.mediaMetadata.artist.toString())
        assertEquals("ABBA Gold", item.mediaMetadata.albumTitle.toString())
        assertEquals("Pop", item.mediaMetadata.genre.toString())
        assertEquals("ABBA", item.mediaMetadata.albumArtist.toString())
        assertEquals("Andersson", item.mediaMetadata.composer.toString())
        assertEquals(3, item.mediaMetadata.trackNumber)
        assertTrue(item.mediaMetadata.isPlayable!!)
        assertFalse(item.mediaMetadata.isBrowsable!!)
        assertEquals("content://media/external/audio/media/42", item.localConfiguration?.uri.toString())
    }

    // A legacy MediaBrowserCompat host (car.media) renders COMPLETION_STATUS_NOT_PLAYED as a
    // per-item "new/unplayed" dot -- the right cue for episodic content, but Dashtone reports every
    // song as not-played (it tracks no per-song listening progress), so that dot would show on
    // every song in every list. See MediaItemMapper.toMediaItem(SongEntity, ...)'s doc comment.
    @Test
    fun `song mapping carries no completion-status extras, so no host ever renders a per-song dot`() {
        val song = SongEntity(
            id = 42, title = "Dancing Queen", trackNumber = 3, discNumber = 1, durationMs = 200_000,
            albumId = 100, artistId = 10, contentUri = "content://media/external/audio/media/42",
            dateAdded = 0, dateModified = 0,
        )

        val extras = mapper.toMediaItem(song, albumName = "ABBA Gold", artistName = "ABBA").mediaMetadata.extras

        assertTrue(extras == null || !extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS))
    }

    @Test
    fun `album mapping is browsable with the artist name attached, not the album's own`() {
        val album = AlbumEntity(id = 100, name = "ABBA Gold", artistId = 10, year = 1992)

        val item = mapper.toMediaItem(album, artistName = "ABBA")

        assertEquals(MediaIds.album(100), item.mediaId)
        assertEquals("ABBA Gold", item.mediaMetadata.title.toString())
        assertEquals("ABBA", item.mediaMetadata.artist.toString())
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertFalse(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun `artist mapping is browsable with no URI`() {
        val item = mapper.toMediaItem(ArtistEntity(id = 10, name = "ABBA"))

        assertEquals(MediaIds.artist(10), item.mediaId)
        assertEquals("ABBA", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertNull(item.localConfiguration)
    }

    @Test
    fun `playlist mapping is browsable`() {
        val item = mapper.toMediaItem(PlaylistEntity(id = 5, name = "Road Trip", dateModified = 0))

        assertEquals(MediaIds.playlist(5), item.mediaId)
        assertEquals("Road Trip", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.isBrowsable!!)
    }

    @Test
    fun `genre, album artist, composer, and decade items all encode their name into the media id`() {
        assertEquals("AC/DC", MediaIds.albumArtistNameOrNull(mapper.toAlbumArtistItem("AC/DC").mediaId))
        assertEquals("Rock", MediaIds.genreNameOrNull(mapper.toGenreItem("Rock").mediaId))
        assertEquals("Hans Zimmer", MediaIds.composerNameOrNull(mapper.toComposerItem("Hans Zimmer").mediaId))
        assertEquals("1980s", MediaIds.decadeNameOrNull(mapper.toDecadeItem("1980s").mediaId))
    }

    // Opens onto the category's flat song list (see LibraryBrowseTree.children()'s all-songs
    // branches) rather than starting playback the instant it's tapped, so it must be browsable, not
    // playable -- the same shape as any other folder in the tree, just nested one level deeper.
    @Test
    fun `All X Songs items are browsable folders, titled with the category's own name, not playable sentinels`() {
        val items = listOf(
            mapper.allArtistSongsItem(10, "Kelsey Lu"),
            mapper.allGenreSongsItem("Rock"),
            mapper.allAlbumArtistSongsItem("Various Artists"),
            mapper.allComposerSongsItem("Hans Zimmer"),
            mapper.allDecadeSongsItem("1980s"),
        )
        val expectedTitles = listOf(
            "All Kelsey Lu Songs", "All Rock Songs", "All Various Artists Songs", "All Hans Zimmer Songs", "All 1980s Songs",
        )

        items.forEach { item ->
            assertTrue(item.mediaMetadata.isBrowsable!!)
            assertFalse(item.mediaMetadata.isPlayable!!)
            assertNull(item.localConfiguration)
        }
        assertEquals(expectedTitles, items.map { it.mediaMetadata.title.toString() })
        assertEquals(10L, MediaIds.allArtistSongsIdOrNull(items[0].mediaId))
        assertEquals("Rock", MediaIds.allGenreSongsNameOrNull(items[1].mediaId))
        assertEquals("Various Artists", MediaIds.allAlbumArtistSongsNameOrNull(items[2].mediaId))
        assertEquals("Hans Zimmer", MediaIds.allComposerSongsNameOrNull(items[3].mediaId))
        assertEquals("1980s", MediaIds.allDecadeSongsNameOrNull(items[4].mediaId))
    }

    @Test
    fun `every browse category root item carries a distinct android_resource icon URI`() {
        val items = listOf(
            mapper.albumsRootItem(), mapper.artistsRootItem(), mapper.genresRootItem(),
            mapper.albumArtistsRootItem(), mapper.composersRootItem(), mapper.decadesRootItem(),
            mapper.foldersRootItem(), mapper.songsRootItem(), mapper.playlistsRootItem(),
        )

        val uris = items.map { it.mediaMetadata.artworkUri }
        assertTrue(uris.all { it != null && it.scheme == "android.resource" && it.authority == context.packageName })
        // Must be the symbolic "drawable/<name>" form, not a raw packed resource int -- a raw int
        // crashed car.media's own process live (UriUtils.getDrawable resolves these by name via
        // Resources.getIdentifier(), not as an already-packed ID). See MediaItemMapper's iconUri.
        assertTrue(uris.all { it!!.pathSegments == listOf("drawable", it.lastPathSegment) && it.lastPathSegment!!.startsWith("ic_category_") })
        assertEquals("all 9 category icons must be distinct", 9, uris.toSet().size)
    }

    @Test
    fun `root item carries no icon of its own -- it's never itself shown as a tab`() {
        assertNull(mapper.rootItem().mediaMetadata.artworkUri)
    }

    // Unlike ROOT, More IS shown as a peer alongside the category tabs in the root tab strip (see
    // LibraryBrowseTree.rootChildren) whenever there are more enabled categories than fit as their
    // own tabs, so it needs an icon of its own for visual consistency with its sibling tabs.
    @Test
    fun `More root item carries its own distinct android_resource icon URI`() {
        val uri = mapper.moreRootItem().mediaMetadata.artworkUri
        assertEquals("android.resource://${context.packageName}/drawable/ic_category_more", uri.toString())
    }

    // Unlike the 12 category icons (one distinct icon each), every play-all sentinel shares one
    // icon and every shuffle sentinel shares another -- the category itself is already carried by
    // the title, so the icon only needs to distinguish "play" from "shuffle", not which category.
    @Test
    fun `play-all sentinels all share one icon URI, shuffle sentinels all share a different one`() {
        val playAllUris = listOf(
            mapper.playAllSongsItem(), mapper.playAllAlbumsItem(), mapper.playAllArtistsItem(),
            mapper.playAllGenresItem(), mapper.playAllAlbumArtistsItem(), mapper.playAllComposersItem(),
        ).map { it.mediaMetadata.artworkUri }
        val shuffleUris = listOf(
            mapper.shuffleAllItem(), mapper.shuffleAllAlbumsItem(), mapper.shuffleAllArtistsItem(),
            mapper.shuffleAllGenresItem(), mapper.shuffleAllAlbumArtistsItem(), mapper.shuffleAllComposersItem(),
        ).map { it.mediaMetadata.artworkUri }

        assertEquals(1, playAllUris.toSet().size)
        assertEquals(1, shuffleUris.toSet().size)
        assertTrue(playAllUris.all { it != null && it.scheme == "android.resource" && it.authority == context.packageName })
        assertTrue(shuffleUris.all { it != null && it.scheme == "android.resource" && it.authority == context.packageName })
        assertEquals("ic_action_play_all", playAllUris.first()!!.lastPathSegment)
        assertEquals("ic_action_shuffle", shuffleUris.first()!!.lastPathSegment)
    }

    @Test
    fun `folder item titles show only the last path segment, but the id carries the full path`() {
        val item = mapper.toFolderItem("Music/ABBA/Gold")

        assertEquals("Gold", item.mediaMetadata.title.toString())
        assertEquals("Music/ABBA/Gold", MediaIds.folderPathOrNull(item.mediaId))
        assertTrue(item.mediaMetadata.isBrowsable!!)
    }

    // Every icon here is referenced ONLY via a runtime-constructed android.resource:// URI (see
    // iconUri's doc comment), never via a compile-time R.drawable.* -- so R8's release-build
    // resource shrinker can't see them as used and strips them unless app/src/main/res/raw/
    // keep.xml's tools:keep list names them explicitly. That list has already gone stale once:
    // moreRootItem()'s icon was missed because it was built by walking BrowseCategory's 9 enum
    // values, and MORE_ROOT isn't one of them -- the gap wasn't a copy-paste slip, it was a blind
    // spot in the enumeration method itself, which any other non-enum-backed icon added later
    // would fall into the same way. That miss shipped silently: it only crashes car.media's own
    // icon-loading code in a shrunk release build, on a host willing to kill its whole process
    // over a bad icon lookup (confirmed live, see iconUri's comment) -- nothing in this app's own
    // process, Crashlytics, or a debug/emulator build would ever surface it. Deriving "every icon
    // the mapper can actually produce" directly from the mapper itself, instead of copying that
    // enumeration by hand a second time here, is what makes this test catch the both original gap
    // AND any future icon that isn't BrowseCategory-shaped either.
    @Test
    fun `every icon referenced by the mapper is protected from resource shrinking by keep xml`() {
        val allItems = listOf(
            mapper.albumsRootItem(), mapper.artistsRootItem(), mapper.genresRootItem(),
            mapper.albumArtistsRootItem(), mapper.composersRootItem(), mapper.decadesRootItem(),
            mapper.foldersRootItem(), mapper.songsRootItem(), mapper.playlistsRootItem(),
            mapper.moreRootItem(),
            mapper.shuffleAllItem(), mapper.shuffleAllAlbumsItem(), mapper.shuffleAllArtistsItem(),
            mapper.shuffleAllGenresItem(), mapper.shuffleAllAlbumArtistsItem(), mapper.shuffleAllComposersItem(),
            mapper.playAllSongsItem(), mapper.playAllAlbumsItem(), mapper.playAllArtistsItem(),
            mapper.playAllGenresItem(), mapper.playAllAlbumArtistsItem(), mapper.playAllComposersItem(),
            mapper.playAlbumsItem("parent"), mapper.shuffleAlbumsItem("parent"),
            mapper.playSongsItem("parent"), mapper.shuffleSongsItem("parent"),
        )
        val usedIconNames = allItems
            .mapNotNull { it.mediaMetadata.artworkUri }
            .filter { it.scheme == "android.resource" }
            .map { it.lastPathSegment!! }
            .toSet()
        // Sanity check on the test itself: if this ever drops to 0 (e.g. a refactor changes how
        // icons are attached), the "keep xml covers everything used" assertion below would pass
        // vacuously and stop meaning anything.
        assertTrue("expected the mapper to reference at least a dozen distinct icons", usedIconNames.size >= 12)

        val keepXml = keepXmlFile().readText()
        val keptNames = Regex("""tools:keep\s*=\s*"([^"]*)"""")
            .find(keepXml)!!.groupValues[1]
            .split(",")
            .map { it.trim().removePrefix("@drawable/") }
            .toSet()

        val missing = usedIconNames - keptNames
        assertTrue(
            "these icons are referenced by MediaItemMapper but missing from keep.xml's tools:keep " +
                "list, so a release build's resource shrinker will strip them and crash the car " +
                "host's icon loader: $missing",
            missing.isEmpty(),
        )
    }

    private fun keepXmlFile(): java.io.File {
        // service's own test working directory is the :service module root; keep.xml lives in the
        // sibling :app module since that's the module resource shrinking actually runs against.
        val fromServiceModule = java.io.File("../app/src/main/res/raw/keep.xml")
        if (fromServiceModule.exists()) return fromServiceModule
        val fromRepoRoot = java.io.File("app/src/main/res/raw/keep.xml")
        if (fromRepoRoot.exists()) return fromRepoRoot
        throw java.io.FileNotFoundException(
            "could not locate app/src/main/res/raw/keep.xml from working directory " +
                java.io.File(".").absolutePath,
        )
    }
}
