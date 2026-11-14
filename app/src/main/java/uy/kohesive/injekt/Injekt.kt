package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get

object Injekt : InjektScope()

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Injekt.get<T>()
}

inline fun <reified T : Any, K : Any> injectLazy(key: K): Lazy<T> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Injekt.get<T, K>(key)
}
