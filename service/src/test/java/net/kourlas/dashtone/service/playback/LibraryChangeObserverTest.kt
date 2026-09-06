@file:OptIn(ExperimentalCoroutinesApi::class)

package net.kourlas.dashtone.service.playback

import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class LibraryChangeObserverTest {

    private val contentResolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    private fun notifyAudioChange() {
        contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a single change debounces into exactly one callback after the delay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var callCount = 0
        val observer = LibraryChangeObserver(contentResolver, scope) { callCount++ }
        observer.start()

        notifyAudioChange()
        assertEquals(0, callCount) // debounce delay hasn't elapsed yet

        scope.advanceUntilIdle()
        assertEquals(1, callCount)
    }

    @Test
    fun `rapid changes within the debounce window coalesce into a single callback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var callCount = 0
        val observer = LibraryChangeObserver(contentResolver, scope) { callCount++ }
        observer.start()

        notifyAudioChange()
        scope.advanceTimeBy(500)
        notifyAudioChange() // restarts the debounce window
        scope.advanceTimeBy(500)
        notifyAudioChange()
        scope.advanceUntilIdle()

        assertEquals(1, callCount)
    }

    @Test
    fun `changes far enough apart each get their own callback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var callCount = 0
        val observer = LibraryChangeObserver(contentResolver, scope) { callCount++ }
        observer.start()

        notifyAudioChange()
        scope.advanceUntilIdle()
        notifyAudioChange()
        scope.advanceUntilIdle()

        assertEquals(2, callCount)
    }

    @Test
    fun `stop cancels a pending debounced callback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var callCount = 0
        val observer = LibraryChangeObserver(contentResolver, scope) { callCount++ }
        observer.start()

        notifyAudioChange()
        observer.stop()
        scope.advanceUntilIdle()

        assertEquals(0, callCount)
    }

    @Test
    fun `stop unregisters the observer so further MediaStore changes are ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var callCount = 0
        val observer = LibraryChangeObserver(contentResolver, scope) { callCount++ }
        observer.start()
        observer.stop()

        notifyAudioChange()
        scope.advanceUntilIdle()

        assertEquals(0, callCount)
    }
}
