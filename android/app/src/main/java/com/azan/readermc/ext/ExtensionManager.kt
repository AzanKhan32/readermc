package com.azan.readermc.ext

import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Manages extension repos (keiyoushi-style) and the installed-extension
 * lifecycle: fetch index, download APK, load, uninstall, update-check.
 * All methods are blocking — callers run them off the main thread.
 */
class ExtensionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("ext_manager", Context.MODE_PRIVATE)

    /** sourceId -> live Source instance (populated by loadInstalled/install). */
    val sources = java.util.concurrent.ConcurrentHashMap<Long, Source>()

    /** pkgName -> loaded extension info. */
    val installed = java.util.concurrent.ConcurrentHashMap<String, ExtensionLoader.LoadedExtension>()

    private val client get() = Injekt.get<NetworkHelper>().client

    // ── Repos ────────────────────────────────────────────────────────────────

    /**
     * Normalizes any pasted repo URL to its base:
     * ".../index.pb" or ".../index.min.json" or trailing slash are stripped.
     */
    fun normalizeRepoUrl(input: String): String {
        var url = input.trim()
        url = url.removeSuffix("/index.pb")
        url = url.removeSuffix("/index.min.json")
        url = url.removeSuffix("/index.json")
        url = url.trimEnd('/')
        return url
    }

    fun repoList(): List<String> {
        val raw = prefs.getString("repos", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun repoAdd(url: String): List<String> {
        val normalized = normalizeRepoUrl(url)
        val repos = repoList().toMutableList()
        if (normalized.isNotEmpty() && normalized !in repos) repos.add(normalized)
        saveRepos(repos)
        return repos
    }

    fun repoRemove(url: String): List<String> {
        val normalized = normalizeRepoUrl(url)
        val repos = repoList().toMutableList()
        repos.remove(normalized)
        saveRepos(repos)
        return repos
    }

    private fun saveRepos(repos: List<String>) {
        prefs.edit().putString("repos", JSONArray(repos).toString()).apply()
    }

    /**
     * Fetches a repo's index and returns a JSONArray in the *legacy* shape the
     * JS layer already understands: name, pkg, apk, lang, code, version, nsfw,
     * sources[{id,name,lang,baseUrl}].
     *
     * Repos now ship two files side by side:
     *
     *  - index.json      current format. An object with extensionList.extensions,
     *                    each carrying resources.apkUrl as a complete absolute URL.
     *  - index.min.json  legacy format. A flat array of bare apk *filenames*.
     *                    keiyoushi has emptied theirs down to two placeholder
     *                    entries ("Outdated App", "Update to Mihon 0.20.1+")
     *                    whose APKs were never uploaded, so installing one
     *                    fails with HTTP 404.
     *
     * So we prefer index.json and only fall back to index.min.json for repos
     * that have not migrated yet.
     */
    fun fetchRepoIndex(repoBase: String): JSONArray {
        fetchModernIndex(repoBase)?.let { return it }
        return fetchLegacyIndex(repoBase)
    }

    /** Reads index.json. Returns null if absent/unparseable so we can fall back. */
    private fun fetchModernIndex(repoBase: String): JSONArray? {
        val body = try {
            client.newCall(GET("$repoBase/index.json")).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body!!.string()
            }
        } catch (_: Exception) {
            return null
        }

        return try {
            val extensions = JSONObject(body)
                .getJSONObject("extensionList")
                .getJSONArray("extensions")

            val out = JSONArray()
            for (i in 0 until extensions.length()) {
                out.put(toLegacyEntry(extensions.getJSONObject(i)))
            }
            out
        } catch (_: Exception) {
            // Object shape missing -> not the modern format; let the caller fall back.
            null
        }
    }

    /** Reads the old flat-array index.min.json. */
    private fun fetchLegacyIndex(repoBase: String): JSONArray {
        val response = client.newCall(GET("$repoBase/index.min.json")).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code} fetching repo index")
            return JSONArray(it.body!!.string())
        }
    }

    /**
     * Maps one modern entry onto the legacy field names.
     *
     * The important part is `apk`: we store resources.apkUrl verbatim. It is a
     * fully-qualified URL, which install() detects and uses as-is instead of
     * rebuilding "$repoBase/apk/$filename".
     */
    private fun toLegacyEntry(src: JSONObject): JSONObject {
        val sourcesIn = src.optJSONArray("sources") ?: JSONArray()
        val sourcesOut = JSONArray()
        val langs = linkedSetOf<String>()

        for (i in 0 until sourcesIn.length()) {
            val s = sourcesIn.getJSONObject(i)
            val lang = s.optString("language", "")
            if (lang.isNotEmpty()) langs.add(lang)
            sourcesOut.put(
                JSONObject()
                    .put("id", s.optString("id", ""))
                    .put("name", s.optString("name", ""))
                    .put("lang", lang)
                    // homeUrl is the modern name for what the JS layer calls
                    // baseUrl — it needs this for the Cloudflare / session-token
                    // WebView retry flow.
                    .put("baseUrl", s.optString("homeUrl", "")),
            )
        }

        return JSONObject()
            .put("name", src.optString("name", ""))
            .put("pkg", src.optString("packageName", ""))
            .put("apk", src.optJSONObject("resources")?.optString("apkUrl", "") ?: "")
            // Modern repos carry the icon as a ready-made absolute URL right
            // next to apkUrl. This was being dropped, which is why
            // not-installed extensions had no logo: the JS-side
            // "{repoBase}/icon/{pkg}.png" guess is a legacy-format convention
            // that modern repos don't honor.
            .put("icon", src.optJSONObject("resources")?.optString("iconUrl", "") ?: "")
            .put("lang", if (langs.size == 1) langs.first() else "all")
            // versionCode arrives as a string in this format.
            .put("code", src.optString("versionCode", "0").toIntOrNull() ?: 0)
            .put("version", src.optString("versionName", ""))
            .put("nsfw", if (src.optString("contentWarning", "") == "CONTENT_WARNING_NSFW") 1 else 0)
            .put("sources", sourcesOut)
    }

    // ── Install / uninstall / load ───────────────────────────────────────────

    fun loadInstalled() {
        sources.clear()
        installed.clear()
        ExtensionLoader.loadAll(context).forEach(::register)
    }

    private fun register(ext: ExtensionLoader.LoadedExtension) {
        installed[ext.pkgName] = ext
        ext.sources.forEach { sources[it.id] = it }
    }

    private fun unregister(pkgName: String) {
        installed.remove(pkgName)?.sources?.forEach { sources.remove(it.id) }
        // Evict the memoized icon so an updated APK's new icon is re-extracted.
        iconCache.remove(pkgName)
    }

    /**
     * Downloads the extension APK into the private extensions dir and loads it
     * immediately. Returns the loaded extension.
     *
     * [apk] may be either a complete URL (modern index.json, which points at a
     * CDN rather than the repo itself) or a bare filename (legacy
     * index.min.json), in which case it resolves against {repoBase}/apk/.
     */
    fun install(repoBase: String, pkgName: String, apk: String): ExtensionLoader.LoadedExtension {
        val dir = ExtensionLoader.extensionsDir(context)
        val target = File(dir, "$pkgName.apk")
        val tmp = File(dir, "$pkgName.apk.part")

        val url = if (apk.startsWith("http://") || apk.startsWith("https://")) {
            apk
        } else {
            "$repoBase/apk/$apk"
        }

        val response = client.newCall(GET(url)).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} downloading APK")
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }

        // Replace atomically-ish: unload old version first so the class
        // loader doesn't hold the stale file open.
        unregister(pkgName)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IllegalStateException("Could not move APK into place")

        val ext = ExtensionLoader.load(context, target)
        register(ext)
        return ext
    }

    fun uninstall(pkgName: String): Boolean {
        unregister(pkgName)
        val file = File(ExtensionLoader.extensionsDir(context), "$pkgName.apk")
        return !file.exists() || file.delete()
    }

    // ── JSON serialization for the JS layer ──────────────────────────────────

    /**
     * pkg -> icon data URI, extracted lazily on first installedJson() call and
     * memoized. Extraction renders a bitmap per APK, so doing it once per
     * install (not once per UI refresh) matters with 100+ extensions.
     * computeIfAbsent can't cache nulls, so misses store "" and map back to
     * JSONObject.NULL when serialized.
     */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun installedJson(): JSONArray {
        val arr = JSONArray()
        installed.values.sortedBy { it.name.lowercase() }.forEach { ext ->
            val icon = iconCache.computeIfAbsent(ext.pkgName) {
                ExtensionLoader.iconDataUri(context, ext.apkFile) ?: ""
            }
            val sourcesArr = JSONArray()
            ext.sources.forEach { s ->
                sourcesArr.put(
                    JSONObject()
                        .put("id", s.id.toString()) // String: JS can't hold 64-bit ints
                        .put("name", s.name)
                        .put("lang", ExtensionLoader.langOf(s))
                        // The JS layer needs the site URL for the Cloudflare /
                        // session-token WebView retry flow (open the site in a
                        // real WebView, then retry the failed native call).
                        .put(
                            "baseUrl",
                            (s as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: "",
                        ),
                )
            }
            arr.put(
                JSONObject()
                    .put("pkg", ext.pkgName)
                    .put("name", ext.name)
                    .put("versionName", ext.versionName)
                    .put("versionCode", ext.versionCode)
                    .put("nsfw", ext.isNsfw)
                    .put("icon", icon.ifEmpty { JSONObject.NULL })
                    .put("sources", sourcesArr),
            )
        }
        return arr
    }
}
