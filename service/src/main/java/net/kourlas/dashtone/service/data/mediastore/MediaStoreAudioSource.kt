package net.kourlas.dashtone.service.data.mediastore

/** Source of the device's audio library. Abstracted from ContentResolver so [LibraryScanner]'s
 * grouping/reconciliation logic can be unit-tested without an Android runtime. */
interface MediaStoreAudioSource {
    fun queryAllAudio(): List<RawAudioRow>
}
