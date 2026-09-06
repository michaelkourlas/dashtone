package net.kourlas.dashtone.service.data.mediastore

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.getSystemService
import net.kourlas.dashtone.service.R

/**
 * Resolves a MediaStore VOLUME_NAME ("external_primary", or a lowercase UUID for a removable
 * volume) to a human-readable label for the folder-exclusion setting, e.g. "Internal Storage" or
 * "SD Card" -- otherwise a user has no way to tell which physical storage a same-named folder
 * (e.g. two different "Music" folders) actually lives on.
 */
object VolumeLabels {
    private const val PRIMARY_VOLUME_NAME = "external_primary"

    fun resolve(context: Context, volumeName: String): String {
        if (volumeName == PRIMARY_VOLUME_NAME) return context.getString(R.string.volume_internal_storage)

        val storageManager = context.getSystemService<StorageManager>() ?: return volumeName
        val volume = storageManager.storageVolumes.find { it.matches(volumeName) } ?: return volumeName
        return volume.getDescription(context) ?: volumeName
    }

    // StorageVolume.getMediaStoreVolumeName() (API 30+) is the precise match; below that, a
    // removable volume's MediaStore VOLUME_NAME is its lowercased UUID, so fall back to comparing
    // against StorageVolume.uuid directly -- still correct, just not documented as a stable
    // contract the way the API 30+ method is.
    private fun StorageVolume.matches(volumeName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaStoreVolumeName?.let { return it == volumeName }
        }
        return uuid?.lowercase() == volumeName
    }
}
