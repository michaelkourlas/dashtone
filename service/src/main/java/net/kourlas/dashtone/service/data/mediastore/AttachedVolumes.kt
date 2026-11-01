package net.kourlas.dashtone.service.data.mediastore

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The MediaStore volumes that are mounted right now, as VOLUME_NAME values ("external_primary",
 * or a lowercase UUID for a removable volume). Null if the platform cannot report them. */
fun interface AttachedVolumes {
    fun names(): Set<String>?
}

class MediaStoreAttachedVolumes @Inject constructor(
    @ApplicationContext private val context: Context,
) : AttachedVolumes {
    // getExternalVolumeNames() arrived in API 29 together with the VOLUME_NAME column, so below
    // that there are no song volumes to compare against either.
    override fun names(): Set<String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getExternalVolumeNames(context) else null
}
