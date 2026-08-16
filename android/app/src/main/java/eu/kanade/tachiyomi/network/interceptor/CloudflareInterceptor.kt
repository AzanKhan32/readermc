package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Cloudflare challenge detector.
 *
 * Mihon's version resolves challenges in a headless WebView. This app already
 * has a *visible* challenge WebView (WebViewActivity) whose cookies land in the
 * shared CookieManager that AndroidCookieJar reads, so by the time a request
 * gets here the cf_clearance cookie is either present (request succeeds) or it
 * isn't (we surface a clear, actionable error instead of raw HTML).
 *
 * This class must be registered on NetworkHelper.client: extensions-lib and the
 * app's own client validation both assert its presence on the default client.
 */
class CloudflareInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (!response.isChallenge()) return response

        // Close the challenge body before throwing so the connection isn't leaked.
        response.close()
        throw IOException(
            "Cloudflare challenge on ${chain.request().url.host} — " +
                "open the WebView button to solve it, then retry.",
        )
    }

    private fun Response.isChallenge(): Boolean {
        if (code !in CHALLENGE_CODES) return false
        val server = header("Server").orEmpty()
        return server.startsWith("cloudflare", ignoreCase = true) ||
            server.startsWith("sucuri", ignoreCase = true)
    }

    companion object {
        private val CHALLENGE_CODES = intArrayOf(403, 503)
    }
}
