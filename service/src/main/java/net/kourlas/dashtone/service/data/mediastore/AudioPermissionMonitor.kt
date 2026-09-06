package net.kourlas.dashtone.service.data.mediastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live, observable mirror of [AudioPermission.isGranted]. Android has no push notification for a
 * runtime permission changing, so this only updates when something that could plausibly have
 * changed it calls [refresh] -- SettingsFragment (the in-app permission row) requests the
 * runtime permission directly and must call [refresh] itself afterward: that call is what
 * actually updates this shared instance, not checking [AudioPermission.isGranted] again for its
 * own UI.
 */
@Singleton
class AudioPermissionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isGranted = MutableStateFlow(AudioPermission.isGranted(context))
    val isGranted: StateFlow<Boolean> = _isGranted.asStateFlow()

    fun refresh() {
        _isGranted.value = AudioPermission.isGranted(context)
    }
}
