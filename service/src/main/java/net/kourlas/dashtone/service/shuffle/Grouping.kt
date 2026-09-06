package net.kourlas.dashtone.service.shuffle

/**
 * A category type songs can be grouped into for [ShuffleSettings]. Ordinal is persisted directly
 * (see [net.kourlas.dashtone.service.data.local.QueueStateEntity.groupingOrdinal]), so append new
 * groupings at the end rather than reordering.
 */
enum class Grouping {
    ALBUM,
    ARTIST,
    GENRE,
    ALBUM_ARTIST,
    COMPOSER,
}
