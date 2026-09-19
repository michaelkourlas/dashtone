package net.kourlas.dashtone.service.albumart

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `write publishes the bytes at the served path`() {
        val published = cache.write(7, byteArrayOf(1, 2, 3))

        assertEquals(cache.file(7).absolutePath, published?.absolutePath)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.file(7).readBytes())
    }

    // The temp file write() stages through has to be gone once the rename completes, or a
    // directory full of abandoned .tmp files accumulates alongside the real artwork.
    @Test
    fun `write leaves no temporary files behind`() {
        cache.write(7, byteArrayOf(1, 2, 3))

        val strays = cache.file(0).parentFile?.listFiles()?.filter { it.name.endsWith(".tmp") }
        assertEquals(emptyList<Any>(), strays)
    }

    // Mirrors a car host requesting the same uncached album from several threads at once: each
    // call stages its own temp file, so the served path only ever holds a complete image.
    @Test
    fun `concurrent writes for one album leave a complete image`() {
        val payload = ByteArray(64) { 9 }

        val threads = (1..8).map { Thread { cache.write(7, payload) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertArrayEquals(payload, cache.file(7).readBytes())
    }

    @Test
    fun `clear drops cached images so they are re-extracted`() {
        cache.write(1, byteArrayOf(1))
        cache.write(2, byteArrayOf(2))
        assertTrue(cache.file(1).exists())

        cache.clear()

        assertFalse(cache.file(1).exists())
        assertFalse(cache.file(2).exists())
    }

    @Test
    fun `clear on an empty cache is harmless`() {
        cache.clear()

        assertFalse(cache.file(1).exists())
    }
}
