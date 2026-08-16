/*
 * Embedded copy of kohesive/injekt (Apache-2.0), api/Scope.kt.
 * See TypeInfo.kt header for why this is embedded.
 */
@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt.api

import kotlin.reflect.KClass

open class InjektScope(val registrar: InjektRegistrar) : InjektRegistrar by registrar {
    inline fun <reified T : Any> injectLazy(): Lazy<T> {
        return lazy { get(fullType<T>()) }
    }

    inline fun <reified T : Any> injectValue(): Lazy<T> {
        return lazyOf(get(fullType<T>()))
    }

    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
        return lazy { get(fullType<T>(), key) }
    }

    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
        return lazyOf(get(fullType<T>(), key))
    }

    inline fun <reified R : Any> injectLogger(forClass: Class<Any>): Lazy<R> {
        return lazy { logger(fullType<R>(), forClass) }
    }

    inline fun <reified R : Any> injectLogger(forClass: KClass<Any>): Lazy<R> {
        return lazy { logger(fullType<R>(), forClass.java) }
    }

    inline fun <reified R : Any> injectLogger(byName: String): Lazy<R> {
        return lazy { logger(fullType<R>(), byName) }
    }

    inline fun <reified R : Any> addScopedSingletonFactory(noinline scopedFactoryCalledOnce: InjektScope.() -> R) {
        addSingletonFactory(fullType<R>()) { this.scopedFactoryCalledOnce() }
    }

    inline fun <reified R : Any> addScopedFactory(noinline scopedFactoryCalledEveryTime: InjektScope.() -> R) {
        addFactory(fullType<R>()) { this.scopedFactoryCalledEveryTime() }
    }

    inline fun <reified R : Any, K : Any> addScopedPerKeyFactory(noinline scopedFactoryCalledPerKey: InjektScope.(key: K) -> R) {
        addPerKeyFactory(fullType<R>()) { key: K -> this.scopedFactoryCalledPerKey(key) }
    }

    inline fun <reified R : Any, K : Any> addScopedPerThreadPerKeyFactory(noinline scopedFactoryCalledPerKeyPerThread: InjektScope.(key: K) -> R) {
        addPerThreadPerKeyFactory(fullType<R>()) { key: K -> this.scopedFactoryCalledPerKeyPerThread(key) }
    }

    inline fun <reified R : Any> addScopedPerThreadFactory(noinline scopedFactoryCalledPerThread: InjektScope.() -> R) {
        addPerThreadFactory(fullType<R>()) { this.scopedFactoryCalledPerThread() }
    }
}

abstract class LocalScoped(val localScope: InjektScope)  {
    inline fun <reified T : Any> injectLazy(): Lazy<T> {
        return localScope.injectLazy()
    }

    inline fun <reified T : Any> injectValue(): Lazy<T> {
        return localScope.injectValue()
    }

    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
        return localScope.injectLazy(key)
    }

    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
        return localScope.injectValue(key)
    }
}
