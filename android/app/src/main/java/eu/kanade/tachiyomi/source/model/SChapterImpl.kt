package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    // lib 1.6: non-null default so sources can read before writing.
    override var memo: JsonObject = buildJsonObject { }
}
