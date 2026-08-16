package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

/**
 * Port of Mihon/Tachiyomi extensions-lib SManga (Apache-2.0).
 * Extension APKs are compiled against this exact interface (package + member
 * signatures must not change) — the host app provides the implementation.
 */
interface SManga : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    // lib 1.6 addition: per-manga scratchpad sources use to persist state
    // (API ids, tokens) between calls. 4KHD failed with
    // "No interface method setMemo(JsonObject)" until this existed.
    var memo: JsonObject

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copyFrom(other: SManga) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        status = other.status
        update_strategy = other.update_strategy
        // Mirror upstream: only overwrite when the other side actually has data.
        if (other.memo.isNotEmpty()) memo = other.memo
        if (!initialized) initialized = other.initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}
