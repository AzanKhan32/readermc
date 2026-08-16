package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of extensions-lib JsoupExtensions (Apache-2.0). File name/package
 * must match — extensions link against eu.kanade.tachiyomi.util.JsoupExtensionsKt.
 */
fun Response.asJsoup(html: String? = null): Document {
    return Jsoup.parse(html ?: body!!.string(), request.url.toString())
}
