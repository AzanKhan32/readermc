package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

/** Port of extensions-lib SChapter (Apache-2.0). Signature-compatible. */
interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    // lib 1.6 addition — same scratchpad as SManga.memo, per chapter.
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        if (other.memo.isNotEmpty()) memo = other.memo
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}
