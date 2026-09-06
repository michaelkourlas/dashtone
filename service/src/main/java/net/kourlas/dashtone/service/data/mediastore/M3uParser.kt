package net.kourlas.dashtone.service.data.mediastore

import java.net.URI

/**
 * Parses the extended-or-basic M3U/M3U8 format: framework-free (no android.net.Uri) so it's
 * plain-JVM testable. Only local file references are meaningful here -- there's no streaming
 * support, so http(s) entries are left for the caller to filter out via a failed path match.
 */
internal object M3uParser {

    /** Returns the ordered, non-comment entry lines (each a path or URI as written in the file,
     * not yet resolved against [baseDir]). */
    fun parseEntries(content: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /** Resolves a single M3U entry to a normalized absolute path. [baseDir] is the playlist
     * file's own containing directory, against which relative entries (including `../` segments)
     * are resolved -- M3U has no notion of a working directory of its own. */
    fun resolveAbsolutePath(entry: String, baseDir: String): String {
        val decoded = if (entry.startsWith("file://")) {
            runCatching { URI(entry).path }.getOrNull() ?: entry.removePrefix("file://")
        } else {
            entry
        }
        val absolute = if (decoded.startsWith("/")) decoded else "$baseDir/$decoded"
        return normalizePath(absolute)
    }

    /** Collapses `.`/`..`/empty segments so paths built via string concatenation -- or read
     * as-is from MediaStore's DATA column -- compare equal regardless of formatting. */
    fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return "/" + stack.joinToString("/")
    }
}
