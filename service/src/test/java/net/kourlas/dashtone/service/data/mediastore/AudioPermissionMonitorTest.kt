package net.kourlas.dashtone.service.data.mediastore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AudioPermissionMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = Shadows.shadowOf(context as Application)

    // Spells out why DashtoneLibrarySessionCallback.onGetLibraryRoot calls refresh(): the value
    // this holds is whatever was true when something last asked, and a grant made from the system
    // settings app raises no callback to tell it otherwise.
    @Test
    fun `a grant made outside the app is visible only once refresh is called`() {
        application.denyPermissions(AudioPermission.PERMISSION)
        val monitor = AudioPermissionMonitor(context)
        assertFalse(monitor.isGranted.value)

        application.grantPermissions(AudioPermission.PERMISSION)
        assertFalse("nothing has told the monitor yet", monitor.isGranted.value)

        monitor.refresh()

        assertTrue(monitor.isGranted.value)
    }

    @Test
    fun `refresh picks up a revoked permission too`() {
        application.grantPermissions(AudioPermission.PERMISSION)
        val monitor = AudioPermissionMonitor(context)
        assertTrue(monitor.isGranted.value)

        application.denyPermissions(AudioPermission.PERMISSION)
        monitor.refresh()

        assertFalse(monitor.isGranted.value)
    }
}
