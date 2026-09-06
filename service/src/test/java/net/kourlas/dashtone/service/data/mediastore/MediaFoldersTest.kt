package net.kourlas.dashtone.service.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFoldersTest {

    @Test
    fun `takes the first path segment`() {
        assertEquals("Music", topLevelFolderOf("Music/ABBA/Gold/"))
        assertEquals("Podcasts", topLevelFolderOf("Podcasts/Show/Episode 1/"))
    }

    @Test
    fun `tolerates a missing trailing or leading slash`() {
        assertEquals("Music", topLevelFolderOf("Music"))
        assertEquals("Music", topLevelFolderOf("/Music/Album/"))
    }

    @Test
    fun `returns null for a null or blank relative path`() {
        assertNull(topLevelFolderOf(null))
        assertNull(topLevelFolderOf(""))
        assertNull(topLevelFolderOf("/"))
    }

    @Test
    fun `fullFolderPathOf keeps the whole path, trimmed of leading and trailing slashes`() {
        assertEquals("Music/ABBA/Gold", fullFolderPathOf("Music/ABBA/Gold/"))
        assertEquals("Music", fullFolderPathOf("Music/"))
        assertEquals("Music", fullFolderPathOf("/Music/"))
    }

    @Test
    fun `fullFolderPathOf returns null for a null or blank relative path`() {
        assertNull(fullFolderPathOf(null))
        assertNull(fullFolderPathOf(""))
        assertNull(fullFolderPathOf("/"))
    }
}
