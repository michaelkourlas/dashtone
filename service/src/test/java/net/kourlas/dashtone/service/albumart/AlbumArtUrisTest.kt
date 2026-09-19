package net.kourlas.dashtone.service.albumart

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumArtUrisTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `forAlbum then albumIdOrNull round-trips the album id`() {
        val uri = AlbumArtUris.forAlbum(context, 42)
        assertEquals(42L, AlbumArtUris.albumIdOrNull(context, uri))
    }

    @Test
    fun `albumIdOrNull returns null for a uri from a different authority`() {
        val uri = "content://some.other.authority/42".toUri()
        assertNull(AlbumArtUris.albumIdOrNull(context, uri))
    }

    @Test
    fun `albumIdOrNull returns null for a non-numeric path segment`() {
        val uri = "content://${AlbumArtUris.authority(context)}/not-a-number".toUri()
        assertNull(AlbumArtUris.albumIdOrNull(context, uri))
    }

    // The manifest declares this provider as "${applicationId}.albumart", so the authority has to
    // track the running app's package to keep matching it after a merge into any applicationId.
    @Test
    fun `the authority is the running package plus the album art suffix`() {
        assertEquals("${context.packageName}.albumart", AlbumArtUris.authority(context))
        assertTrue(AlbumArtUris.forAlbum(context, 1).toString().startsWith("content://${context.packageName}.albumart"))
    }
}
