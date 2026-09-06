package net.kourlas.dashtone.service.albumart

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumArtUrisTest {

    @Test
    fun `forAlbum then albumIdOrNull round-trips the album id`() {
        val uri = AlbumArtUris.forAlbum(42)
        assertEquals(42L, AlbumArtUris.albumIdOrNull(uri))
    }

    @Test
    fun `albumIdOrNull returns null for a uri from a different authority`() {
        val uri = "content://some.other.authority/42".toUri()
        assertNull(AlbumArtUris.albumIdOrNull(uri))
    }

    @Test
    fun `albumIdOrNull returns null for a non-numeric path segment`() {
        val uri = "content://${AlbumArtUris.AUTHORITY}/not-a-number".toUri()
        assertNull(AlbumArtUris.albumIdOrNull(uri))
    }
}
