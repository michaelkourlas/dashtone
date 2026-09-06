package net.kourlas.dashtone.service.playback

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Watches MediaStore for changes (files added/removed on disk, e.g. a USB drive plugged in) and
 * debounces them into a single rescan callback, rather than rescanning on every individual
 * MediaStore write. */
class LibraryChangeObserver(
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val onLibraryChanged: suspend () -> Unit,
) {
    private var observer: ContentObserver? = null
    private var debounceJob: Job? = null

    fun start() {
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    onLibraryChanged()
                }
            }
        }
        observer = newObserver
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants= */ true,
            newObserver,
        )
        // Playlist files (.m3u/.m3u8) are indexed under the general Files collection, not Audio.
        // Media, so editing a playlist wouldn't otherwise trigger a rescan until relaunch.
        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            /* notifyForDescendants= */ true,
            newObserver,
        )
    }

    fun stop() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        debounceJob?.cancel()
        debounceJob = null
    }

    private companion object {
        const val DEBOUNCE_MS = 2000L
    }
}
