package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Port of Mihon's NetworkHelper (Apache-2.0), simplified. Extensions receive
 * this via Injekt and read [client], [cloudflareClient], [cookieJar] and
 * [defaultUserAgentProvider] — those four members are the public contract.
 *
 * Cloudflare handling: the shared [AndroidCookieJar] is backed by the
 * system WebView CookieManager, which is the same store the app's existing
 * manual challenge WebViewActivity writes cf_clearance into. So once the
 * user passes a challenge in that WebView, OkHttp requests here carry the
 * clearance cookie automatically.
 */
/**
 * Host-scoped bearer tokens pushed in from the web layer (e.g. the app's
 * MangaDex login). The interceptor below attaches them to every extension
 * request for that host, so extensions without their own login code (like
 * the MangaDex extension) still make authenticated API calls.
 */
object HostAuthStore {
    @Volatile
    var tokens: Map<String, String> = emptyMap()
        private set

    fun set(host: String, token: String?) {
        tokens = if (token.isNullOrBlank()) tokens - host else tokens + (host to token)
    }
}

class NetworkHelper(context: Context) {

    private val appContext = context.applicationContext

    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .cache(
            Cache(
                directory = File(context.cacheDir, "ext_network_cache"),
                maxSize = 5L * 1024 * 1024, // 5 MiB
            ),
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        // MUST be first: extensions-lib's awaitSuccess()/asObservable() assert this
        // interceptor is present on the default client and refuse to run without it.
        // It also wraps any non-IOException thrown further down the chain into an
        // IOException, so a misbehaving source can't crash the whole app.
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        // Also asserted on the default client. Runs after the UA interceptor so
        // the challenge check sees the same UA the WebView earned clearance with.
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor { chain ->
            var request = chain.request()
            var builder = request.newBuilder()
            var changed = false

            // Host-scoped bearer token (pushed from the app's MangaDex login).
            val token = HostAuthStore.tokens[request.url.host]
            if (token != null && request.header("Authorization") == null) {
                builder = builder.header("Authorization", "Bearer $token")
                changed = true
            }

            // The MangaDex extension appends contentRating[] params from its
            // own internal preferences, which default to excluding erotica /
            // pornographic — and this app has no extension settings screen to
            // change them. Widen the ratings to all four UNCONDITIONALLY (the
            // MangaDex API serves adult listings without auth; the block was
            // purely the extension's preference defaults). Not tying this to
            // the token also removes any login/startup timing dependency.
            val url = request.url
            val ratedPath = url.encodedPath.startsWith("/manga") ||
                    url.encodedPath.startsWith("/chapter") ||
                    url.encodedPath.contains("/feed")
            if (url.host == "api.mangadex.org" && ratedPath) {
                val existing = url.queryParameterValues("contentRating[]")
                val widened = url.newBuilder()
                // No params at all = API defaults (excludes pornographic), so
                // widen from the full default set, not from empty — appending
                // only the adult ratings would drop safe titles.
                val base = existing.ifEmpty {
                    listOf("safe", "suggestive").onEach {
                        widened.addQueryParameter("contentRating[]", it)
                    }
                }
                listOf("erotica", "pornographic").forEach { rating ->
                    if (rating !in base) {
                        widened.addQueryParameter("contentRating[]", rating)
                    }
                }
                val newUrl = widened.build()
                android.util.Log.d("ReaderMC", "MD widen: $newUrl (auth=${token != null})")
                builder = builder.url(newUrl)
                changed = true
            }

            if (changed) request = builder.build()
            chain.proceed(request)
        }
        .build()

    /**
     * In Mihon this client resolves Cloudflare challenges through a headless
     * WebView. Here it's the same client — cf_clearance cookies arrive via
     * the shared CookieManager once the user passes the app's manual
     * challenge WebView.
     */
    val cloudflareClient: OkHttpClient
        get() = client

    /**
     * MUST match the visible challenge WebView's User-Agent. Sites like
     * HDoujin bind their session token ("clearance") to the browser identity
     * that earned it: the token is minted in the WebView (device's real
     * mobile Chrome UA) but spent by this OkHttp client. With the old
     * hardcoded desktop UA the API rejected every token with 403 → endless
     * "Open webview to refresh token". The device UA is resolved lazily on
     * first use (WebView can't be touched too early in app startup) and
     * falls back to the static UA if WebView is unavailable.
     */
    private val webViewUserAgent: String by lazy {
        try {
            android.webkit.WebSettings.getDefaultUserAgent(appContext)
                // Sites treat "; wv)" (WebView marker) as a bot signal;
                // the visible WebView strips it the same way via WebSettings.
                .replace("; wv)", ")")
        } catch (e: Exception) {
            DEFAULT_USER_AGENT
        }
    }

    fun defaultUserAgentProvider(): String = webViewUserAgent

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
