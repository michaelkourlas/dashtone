package net.kourlas.dashtone.service.albumart

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumArtCacheTest {

    private lateinit var cache: AlbumArtCache

    @Before
    fun setUp() {
        cache = AlbumArtCache(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `file path is derived from the album id`() {
        assertEquals("42.jpg", cache.file(42).name)
    }

    @Test
    fun `different albums get different cache files`() {
        assertFalse(cache.file(1).absolutePath == cache.file(2).absolutePath)
    }

    @Test
    fun `writing to the returned file makes it exist and be readable back`() {
        val file = cache.file(7)
        assertFalse(file.exists())

        file.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(cache.file(7).exists())
        assertEquals(3, cache.file(7).readBytes().size)
    }
}
