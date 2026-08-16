package com.azan.readermc.ext

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Translates between the JS layer (JSON over the Capacitor bridge) and the
 * Kotlin Source API. All methods are blocking — the plugin calls them from
 * a background thread.
 *
 * Manga/chapter identity across the bridge is the Tachiyomi convention:
 * the `url` field (a source-relative URL) plus the source id.
 */
object SourceBridge {

    private fun catalogueOf(manager: ExtensionManager, sourceId: String): CatalogueSource {
        val source = manager.sources[sourceId.toLong()]
            ?: throw IllegalStateException("Source $sourceId is not installed/loaded")
        return source as? CatalogueSource
            ?: throw IllegalStateException("Source $sourceId is not a catalogue source")
    }

    /**
     * Hard deadline for every bridge call. The plugin runs these blocking
     * methods on a worker thread; without a timeout, one source whose site
     * never responds (3600000 Beauty) parks that thread in runBlocking
     * FOREVER — and every subsequent call from ANY source queues behind it,
     * which is why other sources stopped working until an app restart.
     * 45s is generous enough for slow sites plus a Cloudflare challenge.
     */
    private const val CALL_TIMEOUT_MS = 45_000L

    /** runBlocking + withTimeout with a readable error for the UI. */
    private fun <T> bridgeCall(op: String, block: suspend () -> T): T = runBlocking {
        try {
            withTimeout(CALL_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.e("SourceBridge", "$op timed out after ${CALL_TIMEOUT_MS}ms")
            throw IllegalStateException(
                "$op timed out after ${CALL_TIMEOUT_MS / 1000}s — the site is slow, unreachable, or blocking the app",
                e,
            )
        }
    }

    // ── Catalogue ────────────────────────────────────────────────────────────

    /**
     * All three catalogue entry points funnel through this wrapper. Without
     * it, an extension throwing an exception with a null message (NPE, bare
     * UnsupportedOperationException, ...) reached the UI as the literal
     * string "null" — seen with 3600000 Beauty's "extLatest failed: null" —
     * leaving nothing to diagnose. describe() adds the exception class and
     * top stack frames, same as the chapter-list path.
     */
    private inline fun <T> withDiagnostics(op: String, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            Log.e("SourceBridge", "$op failed", e)
            throw IllegalStateException(describe(e), e)
        }

    fun popular(manager: ExtensionManager, sourceId: String, page: Int): JSONObject =
        bridgeCall("popular") {
            withDiagnostics("popular") {
                val mp = catalogueOf(manager, sourceId).getPopularManga(page)
                mangasPageJson(mp.mangas, mp.hasNextPage)
            }
        }

    fun latest(manager: ExtensionManager, sourceId: String, page: Int): JSONObject =
        bridgeCall("latest") {
            withDiagnostics("latest") {
                val source = catalogueOf(manager, sourceId)
                // Tachiyomi sources declare whether they have a "latest
                // updates" feed via supportsLatest; those that don't (e.g.
                // 3600000 Beauty) throw UnsupportedOperationException from
                // latestUpdatesRequest when called anyway. The flag is read
                // reflectively because the ported CatalogueSource interface
                // predates it on some builds; if absent, assume supported and
                // let the UOE catch below handle it.
                val supportsLatest = runCatching {
                    source.javaClass.getMethod("getSupportsLatest").invoke(source) as? Boolean
                }.getOrNull() ?: true

                val mp = if (!supportsLatest) {
                    Log.i("SourceBridge", "source $sourceId has no latest feed; using popular")
                    source.getPopularManga(page)
                } else {
                    try {
                        source.getLatestUpdates(page)
                    } catch (e: UnsupportedOperationException) {
                        // Flag said yes but the impl still refused — same
                        // outcome, same fallback.
                        Log.i("SourceBridge", "latest unsupported on $sourceId; using popular", e)
                        source.getPopularManga(page)
                    }
                }
                mangasPageJson(mp.mangas, mp.hasNextPage)
            }
        }

    fun search(
        manager: ExtensionManager,
        sourceId: String,
        query: String,
        page: Int,
        statesJson: String?,
    ): JSONObject = bridgeCall("search") {
        withDiagnostics("search") {
            val source = catalogueOf(manager, sourceId)
            // Always start from the source's own fresh FilterList (its defaults),
            // then overlay whatever states the UI sent.
            val filters = source.getFilterList()
            if (!statesJson.isNullOrBlank()) applyFilterStates(filters.list, JSONArray(statesJson))
            val mp = source.getSearchManga(page, query, filters)
            mangasPageJson(mp.mangas, mp.hasNextPage)
        }
    }

    // ── Source filters (the extension's own website taxonomy) ───────────────

    /**
     * Serializes the source's own FilterList to JSON. This is the same
     * hierarchy Mihon shows in its filter sheet — whatever genres/tags the
     * extension scrapes or hardcodes from its website.
     */
    fun filters(manager: ExtensionManager, sourceId: String): JSONArray =
        filtersToJson(catalogueOf(manager, sourceId).getFilterList().list)

    private fun filtersToJson(filters: List<Filter<*>>): JSONArray = JSONArray().apply {
        filters.forEach { put(filterToJson(it)) }
    }

    private fun filterToJson(filter: Filter<*>): JSONObject {
        val json = JSONObject().put("name", filter.name)
        when (filter) {
            is Filter.Header -> json.put("type", "header")
            is Filter.Separator -> json.put("type", "separator")
            is Filter.Select<*> -> json.put("type", "select")
                .put("state", filter.state)
                .put("values", JSONArray(filter.values.map { it.toString() }))
            is Filter.Text -> json.put("type", "text").put("state", filter.state)
            is Filter.CheckBox -> json.put("type", "checkbox").put("state", filter.state)
            is Filter.TriState -> json.put("type", "tristate").put("state", filter.state)
            is Filter.Sort -> json.put("type", "sort")
                .put("values", JSONArray(filter.values.toList()))
                .put(
                    "state",
                    filter.state?.let {
                        JSONObject().put("index", it.index).put("ascending", it.ascending)
                    } ?: JSONObject.NULL,
                )
            is Filter.Group<*> -> json.put("type", "group")
                .put("children", filtersToJson(filter.state.filterIsInstance<Filter<*>>()))
        }
        return json
    }

    /**
     * Overlays UI-selected states onto a fresh FilterList, index-aligned with
     * the JSON produced by [filtersToJson]. Null entries mean "keep default".
     */
    private fun applyFilterStates(filters: List<Filter<*>>, states: JSONArray) {
        for (i in 0 until minOf(filters.size, states.length())) {
            if (states.isNull(i)) continue
            val filter = filters[i]
            val state = states.opt(i)
            when (filter) {
                is Filter.Select<*> -> (state as? Number)?.let { filter.state = it.toInt() }
                is Filter.Text -> (state as? String)?.let { filter.state = it }
                is Filter.CheckBox -> (state as? Boolean)?.let { filter.state = it }
                is Filter.TriState -> (state as? Number)?.let { filter.state = it.toInt() }
                is Filter.Sort -> (state as? JSONObject)?.let {
                    filter.state = Filter.Sort.Selection(it.optInt("index"), it.optBoolean("ascending", true))
                }
                is Filter.Group<*> -> (state as? JSONArray)?.let {
                    applyFilterStates(filter.state.filterIsInstance<Filter<*>>(), it)
                }
                else -> Unit
            }
        }
    }

    // ── Details / chapters / pages ───────────────────────────────────────────

    fun mangaDetails(manager: ExtensionManager, sourceId: String, mangaUrl: String): JSONObject =
        bridgeCall("mangaDetails") {
            val source = catalogueOf(manager, sourceId)
            val stub = SManga.create().apply { url = mangaUrl }
            val details = source.getMangaDetails(stub)
            // Sources may leave url/title blank in details; keep the stub url.
            if (details.url.isEmpty()) details.url = mangaUrl
            mangaJson(details, source)
        }

    fun chapterList(manager: ExtensionManager, sourceId: String, mangaUrl: String): JSONArray =
        bridgeCall("chapterList") {
            val source = catalogueOf(manager, sourceId)

            // A url-only stub is NOT enough for many sources. Mihon always calls
            // getChapterList() with a fully-populated SManga loaded from its
            // database, so extensions freely dereference title/thumbnail_url and
            // some use `!!` while doing so — which surfaces here as an NPE whose
            // message is literally "null". Hydrate the stub via getMangaDetails()
            // first so the object matches what the extension expects.
            val manga = SManga.create().apply { url = mangaUrl }
            try {
                source.getMangaDetails(manga).let { details ->
                    // NOTE: deliberately do NOT copy details.url over manga.url.
                    // Sources routinely return details with a relative or empty
                    // url, and some override chapterListRequest() to throw
                    // UnsupportedOperationException unless the url is absolute.
                    // The url handed to us from browse is the authoritative one.
                    // `title` is a lateinit-style property: reading it when unset
                    // throws, so copy it defensively.
                    runCatching { manga.title = details.title }
                    manga.thumbnail_url = details.thumbnail_url
                    manga.author = details.author
                    manga.artist = details.artist
                    manga.description = details.description
                    manga.genre = details.genre
                    manga.status = details.status
                    manga.initialized = true
                }
            } catch (_: Throwable) {
                // Details are best-effort. If the source can't provide them we
                // still try the chapter list, but guarantee `title` is readable
                // so a dereference can't be the thing that fails.
                runCatching { manga.title }.onFailure { manga.title = "" }
            }

            val chapters = try {
                source.getChapterList(manga)
            } catch (e: UnsupportedOperationException) {
                // CONFIRMED by decompiling the extension's dex (androguard):
                // lib-1.6 generated extensions stub the legacy chapter path —
                // chapterListRequest() and chapterListParse() are unconditional
                // `throw UnsupportedOperationException` — and instead implement
                //   suspend getMangaUpdate(manga, knownChapters, fetchDetails,
                //                          fetchChapters): SMangaUpdate
                // which returns details AND chapters in one call. This app's
                // ported Source interface predates that method, so it must be
                // invoked reflectively. The UOE is the signal to switch paths.
                newApiChapterList(source, manga)
                    ?: run {
                        val diag = dumpChapterMethods(source) + dumpMangaState(source, manga)
                        Log.e("SourceBridge", "no chapter path on ${source.javaClass.name}$diag", e)
                        throw IllegalStateException(describe(e) + diag, e)
                    }
            } catch (e: Throwable) {
                // Never let a null-message throwable reach the UI as "null".
                throw IllegalStateException(describe(e), e)
            }
            val arr = JSONArray()
            chapters.forEach { arr.put(chapterJson(it)) }
            arr
        }

    /**
     * Lists every chapter-related method the extension's own class declares,
     * walking up its superclass chain and stopping before this app's ported
     * HttpSource. This distinguishes the two possibilities behind an
     * UnsupportedOperationException from chapterListRequest():
     *
     *  - the extension declares `getChapterList(SManga, Continuation)` but this
     *    app's Source interface declares a DIFFERENT signature, so the override
     *    silently doesn't override and the deprecated fetchChapterList() default
     *    runs instead; or
     *  - the extension genuinely has no getChapterList() and the throw is real.
     *
     * RESULT for 1Manga.co: no getChapterList is declared, so the signature
     * theory is dead and the throw inside chapterListRequest is genuine.
     */
    /**
     * Calls the lib-1.6 combined-update API reflectively:
     *   suspend fun getMangaUpdate(manga, chapters, fetchDetails, fetchChapters): SMangaUpdate
     * (some builds name it fetchMangaUpdate). Compiled suspend functions take a
     * trailing Continuation and return either the result or COROUTINE_SUSPENDED,
     * which is exactly the contract suspendCoroutineUninterceptedOrReturn
     * expects — so the reflective call slots into the coroutine machinery
     * without any polling or latches. Returns null when the source has no such
     * method, so the caller can fall through to its diagnostic error.
     */
    private suspend fun newApiChapterList(source: Any, manga: SManga): List<SChapter>? {
        val method = source.javaClass.methods.firstOrNull { m ->
            (m.name == "getMangaUpdate" || m.name == "fetchMangaUpdate") &&
                m.parameterTypes.size == 5 &&
                Continuation::class.java.isAssignableFrom(m.parameterTypes[4])
        } ?: return null

        Log.i("SourceBridge", "using new-API ${method.name} on ${source.javaClass.name}")

        val update = suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
            try {
                method.invoke(source, manga, emptyList<SChapter>(), true, true, cont)
            } catch (e: InvocationTargetException) {
                // Unwrap so the UI reports the extension's real exception, not
                // the reflection wrapper.
                throw IllegalStateException(describe(e.targetException ?: e), e.targetException ?: e)
            }
        } ?: return null

        // The result is an SMangaUpdate from the extension's classloader — a
        // type this app cannot reference at compile time, so read `chapters`
        // reflectively (getter first, bare field as fallback).
        val rawChapters = runCatching {
            update.javaClass.methods
                .first { it.name == "getChapters" && it.parameterTypes.isEmpty() }
                .invoke(update)
        }.getOrElse {
            runCatching { update.javaClass.getField("chapters").get(update) }.getOrNull()
        } ?: return null

        @Suppress("UNCHECKED_CAST")
        return rawChapters as? List<SChapter>
    }

    /**
     * Reports the real (non-obfuscated) class chain of the source plus the exact
     * SManga field values handed to chapterListRequest(). `status` is called out
     * because SManga.LICENSED == 3 is a value several themes treat as "refuse to
     * list chapters", and a manga built only from browse results can carry a
     * default that lands there.
     */
    private fun dumpMangaState(source: Any, manga: SManga): String {
        val chain = generateSequence(source.javaClass as Class<*>?) { it.superclass }
            .takeWhile { it != Any::class.java }
            .joinToString(" : ") { it.name }
        return "\n\nclass chain: $chain" +
            "\n\nSManga handed to source:" +
            "\n  url = ${runCatching { manga.url }.getOrElse { "<threw>" }}" +
            "\n  title = ${runCatching { manga.title }.getOrElse { "<threw>" }}" +
            "\n  status = ${runCatching { manga.status }.getOrElse { "<threw>" }} (LICENSED=3)" +
            "\n  initialized = ${runCatching { manga.initialized }.getOrElse { "<threw>" }}"
    }

    private fun dumpChapterMethods(source: Any): String {
        val sb = StringBuilder("\n\ndeclared chapter methods:")
        var cls: Class<*>? = source.javaClass
        while (cls != null && cls != HttpSource::class.java && cls != Any::class.java) {
            cls.declaredMethods
                .filter { it.name.contains("hapter", ignoreCase = true) }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                    sb.append("\n  ${cls!!.simpleName}.${m.name}($params) -> ${m.returnType.simpleName}")
                }
            cls = cls.superclass
        }
        return sb.toString()
    }

    /**
     * Builds a human-readable description of a throwable. Extensions routinely
     * throw exceptions with no message (`!!` on a missing CSS selector being the
     * classic), so `e.message` alone renders as "null" and tells us nothing.
     */
    private fun describe(e: Throwable): String {
        val name = e::class.java.simpleName
        val msg = e.message?.takeIf { it.isNotBlank() }
        val base = if (msg != null) "$name: $msg" else name
        val cause = e.cause?.let { c ->
            val cm = c.message?.takeIf { it.isNotBlank() }
            " (caused by ${c::class.java.simpleName}${cm?.let { ": $it" } ?: ""})"
        } ?: ""
        // Always dump the complete trace to Logcat. The UI string is necessarily
        // truncated, and the real throw site is often deeper than what fits.
        Log.e("SourceBridge", "extension call failed", e)

        // IMPORTANT: do NOT filter frames here. An earlier version of this
        // method hid `com.azan.readermc.*` frames so the extension's own code
        // would surface first — but that also hides the case where the throw
        // actually originates in THIS app's ported lib code, called from the
        // extension. That made a port bug look like an extension bug. Show the
        // top frames verbatim; the topmost frame is the real throw site.
        val origin = e.stackTrace
            .take(6)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .takeIf { it.isNotBlank() }
            ?.let { " at $it" }
            ?: ""

        val hint = when (e) {
            is NullPointerException ->
                " — the source's page layout likely changed, or it needs manga " +
                    "fields this app didn't supply"
            is UnsupportedOperationException ->
                " — this is usually an extensions-lib method this app hasn't " +
                    "implemented, or a write to a read-only collection"
            else -> ""
        }
        return "$base$cause$origin$hint"
    }

    fun pageList(manager: ExtensionManager, sourceId: String, chapterUrl: String): JSONArray =
        bridgeCall("pageList") {
            val source = catalogueOf(manager, sourceId)
            val stub = SChapter.create().apply {
                url = chapterUrl
                // Same defensive move as chapterList: `name` is required to be
                // set before it can be read, and some sources log/parse it.
                name = ""
            }
            val pages = try {
                source.getPageList(stub)
            } catch (e: Throwable) {
                throw IllegalStateException(describe(e), e)
            }
            val arr = JSONArray()
            for (page in pages) {
                // Resolve imageUrl lazily where needed (some sources return
                // pages whose imageUrl requires a second request). Called
                // directly (we're already in a coroutine): the old nested
                // runBlocking here would deadlock-risk and escape the
                // bridgeCall timeout.
                val imageUrl = page.imageUrl ?: (source as? HttpSource)?.getImageUrl(page)
                arr.put(
                    JSONObject()
                        .put("index", page.index)
                        .put("url", page.url)
                        .put("imageUrl", imageUrl ?: JSONObject.NULL),
                )
            }
            arr
        }

    // ── Image fetching (through the source's own client + headers) ──────────

    /**
     * Fetches an image through the source's OkHttp client so per-source
     * headers/interceptors (referer, rate limits, scrambled-image
     * descrambling interceptors!) all apply. Returns base64 + mime.
     */
    fun fetchImage(manager: ExtensionManager, sourceId: String, page: JSONObject): JSONObject {
        val source = manager.sources[sourceId.toLong()] as? HttpSource
            ?: throw IllegalStateException("Source $sourceId is not an HttpSource")

        val pageObj = Page(
            index = page.optInt("index", 0),
            url = page.optString("url", ""),
            imageUrl = page.optString("imageUrl", "").ifEmpty { null },
        )

        val response = bridgeCall("fetchImage") { source.getImage(pageObj) }
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code} fetching image")
            val bytes = it.body!!.bytes()
            val mime = it.header("Content-Type") ?: "image/jpeg"
            return JSONObject()
                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .put("mime", mime)
        }
    }

    /** Plain URL fetch with the source's headers (covers, when JS <img> fails). */
    fun fetchCover(manager: ExtensionManager, sourceId: String, url: String): JSONObject {
        val source = manager.sources[sourceId.toLong()] as? HttpSource
        val client = source?.client ?: Injekt.get<NetworkHelper>().client
        val request: Request = if (source != null) GET(url, source.headers) else GET(url)
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code} fetching cover")
            val bytes = it.body!!.bytes()
            val mime = it.header("Content-Type") ?: "image/jpeg"
            return JSONObject()
                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .put("mime", mime)
        }
    }

    // ── JSON mapping ─────────────────────────────────────────────────────────

    private fun mangasPageJson(mangas: List<SManga>, hasNextPage: Boolean): JSONObject {
        val arr = JSONArray()
        mangas.forEach { arr.put(mangaJson(it, null)) }
        return JSONObject().put("mangas", arr).put("hasNextPage", hasNextPage)
    }

    private fun mangaJson(m: SManga, source: CatalogueSource?): JSONObject =
        JSONObject()
            .put("url", m.url)
            .put("title", m.title)
            .put("thumbnailUrl", m.thumbnail_url ?: JSONObject.NULL)
            .put("author", m.author ?: JSONObject.NULL)
            .put("artist", m.artist ?: JSONObject.NULL)
            .put("description", m.description ?: JSONObject.NULL)
            .put("genre", m.genre ?: JSONObject.NULL)
            .put("status", m.status)

    private fun chapterJson(c: SChapter): JSONObject =
        JSONObject()
            .put("url", c.url)
            .put("name", c.name)
            .put("dateUpload", c.date_upload)
            .put("chapterNumber", c.chapter_number.toDouble())
            .put("scanlator", c.scanlator ?: JSONObject.NULL)
}
