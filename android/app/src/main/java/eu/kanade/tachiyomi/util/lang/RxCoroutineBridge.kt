package eu.kanade.tachiyomi.util.lang

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscriber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Port of Mihon's RxCoroutineBridge (Apache-2.0): lets the suspend default
 * methods on Source/CatalogueSource delegate to the deprecated RxJava-1
 * fetch* methods that older extensions still implement.
 */
suspend fun <T> Observable<T>.awaitSingle(): T = single().awaitOne()

private suspend fun <T> Observable<T>.awaitOne(): T = suspendCancellableCoroutine { cont ->
    cont.unsubscribeOnCancellation(
        subscribe(
            object : Subscriber<T>() {
                override fun onStart() {
                    request(1)
                }

                override fun onNext(t: T) {
                    cont.resume(t)
                }

                override fun onCompleted() {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("Should have invoked onNext"),
                        )
                    }
                }

                override fun onError(e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            },
        ),
    )
}

private fun <T> CancellableContinuation<T>.unsubscribeOnCancellation(sub: rx.Subscription) =
    invokeOnCancellation { sub.unsubscribe() }
