package net.kourlas.dashtone.service.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parseEntries skips blank lines and comments including EXTM3U and EXTINF`() {
        val content = """
            #EXTM3U
            #EXTINF:123,Some Artist - Some Track

            /storage/emulated/0/Music/Track.mp3
            # a regular comment
            ../Other Album/Track 2.mp3
        """.trimIndent()

        assertEquals(
            listOf("/storage/emulated/0/Music/Track.mp3", "../Other Album/Track 2.mp3"),
            M3uParser.parseEntries(content),
        )
    }

    @Test
    fun `resolveAbsolutePath leaves an absolute path unchanged`() {
        assertEquals(
            "/storage/emulated/0/Music/Track.mp3",
            M3uParser.resolveAbsolutePath("/storage/emulated/0/Music/Track.mp3", "/storage/emulated/0/Playlists"),
        )
    }

    @Test
    fun `resolveAbsolutePath resolves a relative path against the base directory`() {
        assertEquals(
            "/storage/emulated/0/Music/Track.mp3",
            M3uParser.resolveAbsolutePath("Track.mp3", "/storage/emulated/0/Music"),
        )
    }

    @Test
    fun `resolveAbsolutePath resolves parent directory references`() {
        assertEquals(
            "/storage/emulated/0/Other Album/Track.mp3",
            M3uParser.resolveAbsolutePath("../Other Album/Track.mp3", "/storage/emulated/0/Music"),
        )
    }

    @Test
    fun `resolveAbsolutePath strips the file scheme from a file uri`() {
        assertEquals(
            "/storage/emulated/0/Music/Track.mp3",
            M3uParser.resolveAbsolutePath("file:///storage/emulated/0/Music/Track.mp3", "/anything"),
        )
    }

    @Test
    fun `normalizePath collapses redundant separators and dot segments`() {
        assertEquals(
            "/storage/emulated/0/Music/Track.mp3",
            M3uParser.normalizePath("/storage//emulated/0/./Music/Track.mp3"),
        )
    }
}
