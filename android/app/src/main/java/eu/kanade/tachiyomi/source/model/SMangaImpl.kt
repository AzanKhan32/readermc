package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = SManga.UNKNOWN

    override var thumbnail_url: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    // lib 1.6: non-null default so sources can read memo before writing it.
    override var memo: JsonObject = buildJsonObject { }

    override var initialized: Boolean = false
}
