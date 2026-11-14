package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> protected constructor() : TypeReference<T> {
    override val type: Type = (javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[0]
}

inline fun <reified T> fullType(): FullTypeReference<T> = object : FullTypeReference<T>() {}

interface InjektFactory {
    fun <R : Any> getInstance(forType: Type): R
    fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R
    fun <R : Any> getInstanceOrElse(forType: Type, default: R): R
    fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R
    fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R
    fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R
    fun <R : Any> getLogger(forType: Type): R
}

interface InjektRegistrar {
    fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T)
    fun <T : Any> addSingletonFactory(forType: TypeReference<T>, factoryCalledOnce: () -> T)
    fun <T : Any> addFactory(forType: TypeReference<T>, factoryCalledEveryTime: () -> T)
    fun <T : Any, K : Any> addKeyedFactory(forType: TypeReference<T>, factoryCalledEveryTime: (K) -> T)
    fun <T : Any, K : Any> addPerKeyFactory(forType: TypeReference<T>, factoryCalledOncePerKey: (K) -> T)
    fun hasFactory(forType: Type): Boolean
}

interface InjektModule {
    fun InjektRegistrar.registerInjectables()
}

open class InjektScope : InjektFactory, InjektRegistrar {
    private val factories = ConcurrentHashMap<Type, () -> Any>()
    private val keyedFactories = ConcurrentHashMap<Type, (Any) -> Any>()
    private val perKeyValues = ConcurrentHashMap<Pair<Type, Any>, Any>()

    fun registerModule(module: InjektModule) {
        with(module) {
            registerInjectables()
        }
    }

    override fun hasFactory(forType: Type): Boolean = resolveFactory(forType) != null

    private fun resolveFactory(forType: Type): (() -> Any)? {
        factories[forType]?.let { return it }
        if (forType is ParameterizedType) {
            factories[forType.rawType]?.let { return it }
        }
        return null
    }

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        factories[forType.type] = { singleInstance }
    }

    override fun <T : Any> addSingletonFactory(forType: TypeReference<T>, factoryCalledOnce: () -> T) {
        val lazyVal = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { factoryCalledOnce() }
        factories[forType.type] = { lazyVal.value }
    }

    override fun <T : Any> addFactory(forType: TypeReference<T>, factoryCalledEveryTime: () -> T) {
        factories[forType.type] = factoryCalledEveryTime
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any, K : Any> addKeyedFactory(forType: TypeReference<T>, factoryCalledEveryTime: (K) -> T) {
        keyedFactories[forType.type] = factoryCalledEveryTime as (Any) -> Any
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any, K : Any> addPerKeyFactory(forType: TypeReference<T>, factoryCalledOncePerKey: (K) -> T) {
        keyedFactories[forType.type] = { key ->
            perKeyValues.getOrPut(forType.type to key) {
                (factoryCalledOncePerKey as (Any) -> Any)(key)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R {
        val factory = resolveFactory(forType)
            ?: throw IllegalStateException("No Injekt binding registered for type: $forType")
        return factory() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R {
        val factory = resolveFactory(forType) ?: return default()
        return factory() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R {
        val factory = resolveFactory(forType) ?: return default
        return factory() as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R {
        val kf = keyedFactories[forType]
            ?: throw IllegalStateException("No keyed Injekt binding registered for type: $forType")
        return kf(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R {
        val kf = keyedFactories[forType] ?: return default()
        return kf(key) as R
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R {
        val kf = keyedFactories[forType] ?: return default
        return kf(key) as R
    }

    override fun <R : Any> getLogger(forType: Type): R {
        return getInstance(forType)
    }
}

inline fun <reified R : Any> InjektFactory.get(): R = getInstance(fullType<R>().type)

inline fun <reified R : Any, K : Any> InjektFactory.get(key: K): R = getKeyedInstance(fullType<R>().type, key)

inline fun <reified T : Any> InjektRegistrar.addSingleton(instance: T) {
    addSingleton(fullType<T>(), instance)
}

inline fun <reified T : Any> InjektRegistrar.addSingletonFactory(noinline factory: () -> T) {
    addSingletonFactory(fullType<T>(), factory)
}

inline fun <reified T : Any> InjektRegistrar.addFactory(noinline factory: () -> T) {
    addFactory(fullType<T>(), factory)
}
