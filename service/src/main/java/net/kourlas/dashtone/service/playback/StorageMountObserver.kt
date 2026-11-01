package net.kourlas.dashtone.service.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/** Calls [onMounted] on the main thread each time a storage volume mounts, such as a USB stick
 * that appears some time after car ignition. */
class StorageMountObserver(
    private val context: Context,
    private val onMounted: () -> Unit,
) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onMounted()
            }
        }
        receiver = newReceiver
        // ACTION_MEDIA_MOUNTED carries the volume's path as a file:// URI, so the filter needs
        // the data scheme to match it. The system sends this broadcast, so the receiver does
        // not need to be exported.
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        ContextCompat.registerReceiver(context, newReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
