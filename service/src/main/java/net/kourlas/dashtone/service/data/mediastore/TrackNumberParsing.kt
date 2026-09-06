package net.kourlas.dashtone.service.data.mediastore

/**
 * MediaStore packs disc and track number into a single TRACK column as `disc * 1000 + track`
 * (or just `track` when no disc is encoded). Returns (discNumber, trackNumber), defaulting
 * discNumber to 1 and trackNumber to 0 when unset.
 */
internal fun parseDiscAndTrack(rawTrack: Int): Pair<Int, Int> {
    if (rawTrack <= 0) return 1 to 0
    val disc = rawTrack / 1000
    val track = rawTrack % 1000
    return if (disc > 0) disc to track else 1 to rawTrack
}
