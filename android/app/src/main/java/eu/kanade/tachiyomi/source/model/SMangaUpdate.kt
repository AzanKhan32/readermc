package eu.kanade.tachiyomi.source.model

/**
 * Combined details+chapters result introduced by extensions-lib 1.6.
 *
 * New-style extensions stub the legacy chapter path (chapterListRequest /
 * chapterListParse throw UnsupportedOperationException) and instead implement
 *   suspend fun getMangaUpdate(manga, chapters, fetchDetails, fetchChapters): SMangaUpdate
 * The extension APKs only REFERENCE this class — verified by decompiling one —
 * so the host app must define it or any call into the new API dies with
 * NoClassDefFoundError. Definition matches keiyoushi/extensions-lib branch 1.6
 * exactly; do not add fields, or the constructor signature the compiled
 * extensions expect will no longer match.
 */
@Suppress("Unused")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
