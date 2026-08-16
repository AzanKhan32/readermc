package eu.kanade.tachiyomi.source.model

/** Port of extensions-lib MangasPage (Apache-2.0). */
data class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
)
