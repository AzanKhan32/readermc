package eu.kanade.tachiyomi.source.model

import android.net.Uri
import java.io.Serializable

/**
 * Port of extensions-lib Page (Apache-2.0). Extensions construct these as
 * Page(index, url) or Page(index, url, imageUrl) — constructor signature must
 * stay identical. Status/progress machinery from the full app is reduced to
 * simple fields (nothing in the extension APKs depends on the flow versions).
 */
open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null,
) : Serializable {

    val number: Int
        get() = index + 1

    @Transient
    @Volatile
    var status: State = State.QUEUE

    @Transient
    @Volatile
    var progress: Int = 0

    enum class State {
        QUEUE,
        LOAD_PAGE,
        DOWNLOAD_IMAGE,
        READY,
        ERROR,
    }
}
