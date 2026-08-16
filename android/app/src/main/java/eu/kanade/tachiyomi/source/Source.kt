package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.awaitSingle
import rx.Observable

/**
 * Port of extensions-lib Source (Apache-2.0). Covers both generations of
 * extensions: lib 1.4-era code overrides the deprecated Rx fetch* methods,
 * lib 1.5-era code overrides the suspend get* methods. Each style's default
 * implementation delegates to the other, exactly like Mihon.
 */
interface Source {

    val id: Long

    val name: String

    val lang: String
        get() = ""

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")

    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga = fetchMangaDetails(manga).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> =
        fetchChapterList(manga).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitSingle()
}
