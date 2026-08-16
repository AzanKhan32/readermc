/*
 * Embedded copy of kohesive/injekt (Apache-2.0), registry/default/DefaultRegistrar.kt.
 * See api/TypeInfo.kt header for why this is embedded.
 */
package uy.kohesive.injekt.registry.default

import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.erasedType
import uy.kohesive.injekt.api.get
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Default registry implementation using ConcurrentHashMaps: write little,
 * read many.
 */
open class DefaultRegistrar : InjektRegistrar {

    private val NOKEY = object {}

    internal data class Instance(val forWhatType: Type, val forKey: Any)

    private val existingValues = ConcurrentHashMap<Instance, Any>()
    private val threadedValues = object : ThreadLocal<HashMap<Instance, Any>>() {
        override fun initialValue(): HashMap<Instance, Any> = hashMapOf()
    }

    private val factories = ConcurrentHashMap<Type, () -> Any>()
    private val keyedFactories = ConcurrentHashMap<Type, (Any) -> Any>()

    internal class LoggerInfo(
        val forWhatType: Type,
        val nameFactory: (String) -> Any,
        val classFactory: (Class<Any>) -> Any,
    )

    @Volatile
    private var loggerFactory: LoggerInfo? = null

    // ==== Registry methods by TypeReference ====

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        addSingletonFactory(forType) { singleInstance }
        get(forType) // load value into front cache
    }

    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) {
        factories[forType.type] = { existingValues.getOrPut(Instance(forType.type, NOKEY)) { factoryCalledOnce() } }
    }

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) {
        factories[forType.type] = factoryCalledEveryTime
    }

    override fun <R : Any> addPerThreadFactory(forType: TypeReference<R>, factoryCalledOncePerThread: () -> R) {
        factories[forType.type] = {
            threadedValues.get()!!.getOrPut(Instance(forType.type, NOKEY)) { factoryCalledOncePerThread() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKey: (K) -> R) {
        keyedFactories[forType.type] = { key ->
            existingValues.getOrPut(Instance(forType.type, key)) { factoryCalledPerKey(key as K) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> addPerThreadPerKeyFactory(forType: TypeReference<R>, factoryCalledPerKeyPerThread: (K) -> R) {
        keyedFactories[forType.type] = { key ->
            threadedValues.get()!!.getOrPut(Instance(forType.type, key)) { factoryCalledPerKeyPerThread(key as K) }
        }
    }

    override fun <R : Any> addLoggerFactory(forLoggerType: TypeReference<R>, factoryByName: (String) -> R, factoryByClass: (Class<Any>) -> R) {
        loggerFactory = LoggerInfo(forLoggerType.type, factoryByName, factoryByClass)
    }

    override fun <O : Any, T : O> addAlias(existingRegisteredType: TypeReference<T>, otherAncestorOrInterface: TypeReference<O>) {
        val existingFactory = factories[existingRegisteredType.type]
        val existingKeyedFactory = keyedFactories[existingRegisteredType.type]

        if (existingFactory != null) {
            factories[otherAncestorOrInterface.type] = existingFactory
        }
        if (existingKeyedFactory != null) {
            keyedFactories[otherAncestorOrInterface.type] = existingKeyedFactory
        }
    }

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean {
        return factories[forType.type] != null || keyedFactories[forType.type] != null
    }

    // ==== Factory methods ====

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R {
        val factory = factories[forType]
            ?: throw InjektionException("No registered instance or factory for type $forType")
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R {
        val factory = factories[forType] ?: return default
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R {
        val factory = factories[forType] ?: return default()
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? {
        val factory = factories[forType] ?: return null
        return factory.invoke() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R {
        val factory = keyedFactories[forType]
            ?: throw InjektionException("No registered keyed factory for type $forType")
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R {
        val factory = keyedFactories[forType] ?: return default
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R {
        val factory = keyedFactories[forType] ?: return default()
        return factory.invoke(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? {
        val factory = keyedFactories[forType] ?: return null
        return factory.invoke(key) as R
    }

    private fun assertLogger(expectedLoggerType: Type) {
        val lf = loggerFactory
            ?: throw InjektionException("Cannot call getLogger() -- A logger factory has not been registered with Injekt")
        if (!lf.forWhatType.erasedType<Any>().isAssignableFrom(expectedLoggerType.erasedType<Any>())) {
            throw InjektionException("Logger factories registered with Injekt indicate they return type ${lf.forWhatType} but current injekt target is expecting type $expectedLoggerType")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getLogger(expectedLoggerType: Type, byName: String): R {
        assertLogger(expectedLoggerType)
        return loggerFactory!!.nameFactory(byName) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getLogger(expectedLoggerType: Type, forClass: Class<Any>): R {
        assertLogger(expectedLoggerType)
        return loggerFactory!!.classFactory(forClass.erasedType<Any>() as Class<Any>) as R
    }
}
