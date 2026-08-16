/*
 * Embedded copy of kohesive/injekt (Apache-2.0), core/Injekt.kt.
 * See api/TypeInfo.kt header for why this is embedded.
 *
 * CRITICAL: `Injekt` must stay a top-level `var` in this exact package —
 * extension APKs reference it as InjektKt.getInjekt() at runtime.
 */
@file:Suppress("NOTHING_TO_INLINE")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.logger
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import kotlin.reflect.KClass

@Volatile
var Injekt: InjektScope = InjektScope(DefaultRegistrar())

/** A class that starts up a system using Injekt with the default global scope. */
abstract class InjektScopedMain(val scope: InjektScope) : InjektModule {
    init {
        @Suppress("LeakingThis")
        scope.registrar.importModule(this)
    }
}

abstract class InjektMain : InjektScopedMain(Injekt)

inline fun <reified T : Any> injectLazy(): Lazy<T> {
    return lazy { Injekt.get(fullType<T>()) }
}

inline fun <reified T : Any> injectValue(): Lazy<T> {
    return lazyOf(Injekt.get(fullType<T>()))
}

inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
    return lazy { Injekt.get(fullType<T>(), key) }
}

inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
    return lazyOf(Injekt.get(fullType<T>(), key))
}

inline fun <reified R : Any, reified T : Any> R.injectLogger(): Lazy<T> {
    return lazy { Injekt.logger(fullType<T>(), R::class.java as Class<Any>) }
}

inline fun <reified R : Any> injectLogger(forClass: KClass<Any>): Lazy<R> {
    return lazy { Injekt.logger(fullType<R>(), forClass.java) }
}

inline fun <reified R : Any> injectLogger(forClass: Class<Any>): Lazy<R> {
    return lazy { Injekt.logger(fullType<R>(), forClass) }
}

inline fun <reified R : Any> injectLogger(byName: String): Lazy<R> {
    return lazy { Injekt.logger(fullType<R>(), byName) }
}
