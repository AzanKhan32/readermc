package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Port of extensions-lib RateLimitInterceptor (Apache-2.0).
 *
 * IMPORTANT: this file's name and package must match the lib exactly —
 * precompiled extensions link against
 * eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt.
 *
 * An OkHttp interceptor that handles rate limiting: permits per period.
 * Examples: permits = 5, period = 1.seconds  =>  5 requests per second
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
) = addInterceptor(RateLimitInterceptor(null, permits, period))

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) = addInterceptor(RateLimitInterceptor(null, permits, period.toDuration(unit.toDurationUnit())))

internal fun TimeUnit.toDurationUnit(): DurationUnit = when (this) {
    TimeUnit.NANOSECONDS -> DurationUnit.NANOSECONDS
    TimeUnit.MICROSECONDS -> DurationUnit.MICROSECONDS
    TimeUnit.MILLISECONDS -> DurationUnit.MILLISECONDS
    TimeUnit.SECONDS -> DurationUnit.SECONDS
    TimeUnit.MINUTES -> DurationUnit.MINUTES
    TimeUnit.HOURS -> DurationUnit.HOURS
    TimeUnit.DAYS -> DurationUnit.DAYS
}

/** Shared implementation for global and per-host rate limiting. */
internal class RateLimitInterceptor(
    private val host: String?,
    private val permits: Int,
    period: Duration,
) : Interceptor {

    private val requestQueue = ArrayList<Long>(permits)
    private val rateLimitMillis = period.inWholeMilliseconds
    private val fairLock = java.util.concurrent.Semaphore(1, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")

        val request = chain.request()
        if (host != null && request.url.host != host) {
            return chain.proceed(request)
        }

        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }

        val requestQueue = this.requestQueue
        val timestamp: Long

        try {
            synchronized(requestQueue) {
                while (requestQueue.size >= permits) { // queue is full, remove expired entries
                    val periodStart = System.currentTimeMillis() - rateLimitMillis
                    var hasRemovedExpired = false
                    val iterator = requestQueue.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next() <= periodStart) {
                            iterator.remove()
                            hasRemovedExpired = true
                        }
                    }
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    } else if (hasRemovedExpired) {
                        break
                    } else {
                        try { // wait for the first entry to expire, or notified by cached response
                            (requestQueue as Object).wait(requestQueue.first() - periodStart)
                        } catch (_: InterruptedException) {
                            continue
                        }
                    }
                }

                // add request to queue
                timestamp = System.currentTimeMillis()
                requestQueue.add(timestamp)
            }
        } finally {
            fairLock.release()
        }

        val response = chain.proceed(request)
        if (response.networkResponse == null) { // response is from cache
            synchronized(requestQueue) {
                if (requestQueue.remove(timestamp)) {
                    (requestQueue as Object).notifyAll()
                }
            }
        }

        return response
    }
}
