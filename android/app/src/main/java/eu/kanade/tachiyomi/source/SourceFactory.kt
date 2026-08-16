package eu.kanade.tachiyomi.source

/** Port of extensions-lib SourceFactory (Apache-2.0). */
interface SourceFactory {
    fun createSources(): List<Source>
}
