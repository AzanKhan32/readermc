package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Port of Mihon's UncaughtExceptionInterceptor (Apache-2.0).
 *
 * OkHttp only contracts to throw [IOException] from a call. Extensions,
 * however, routinely throw plain [Exception]/[IllegalStateException] from
 * inside their own interceptors and parse code (Cloudflare handling, token
 * refresh, JSON parsing, etc). Those escape the call unwrapped and crash the
 * app instead of surfacing as a normal network failure.
 *
 * This wraps anything that isn't already an [IOException] so callers can rely
 * on catching [IOException] alone.
 *
 * extensions-lib's `awaitSuccess()` / `asObservable()` helpers also assert
 * that this interceptor is installed on the default client, so it must be
 * registered on [eu.kanade.tachiyomi.network.NetworkHelper.client] — and it
 * must be added FIRST, so it sits outermost and can catch throws from every
 * interceptor added after it.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            if (e is IOException) throw e else throw IOException(e)
        }
    }
}
