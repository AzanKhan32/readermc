package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration

/**
 * Port of extensions-lib SpecificHostRateLimitInterceptor (Apache-2.0).
 * File name/package must match the lib exactly — precompiled extensions
 * link against ...interceptor.SpecificHostRateLimitInterceptorKt.
 */
fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
) = addInterceptor(RateLimitInterceptor(url.host, permits, period))

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
) = addInterceptor(RateLimitInterceptor(url.toHttpUrl().host, permits, period))

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimitHost(
    url: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) = addInterceptor(RateLimitInterceptor(url.host, permits, period.toDuration(unit.toDurationUnit())))
