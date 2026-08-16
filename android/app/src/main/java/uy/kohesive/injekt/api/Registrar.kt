/*
 * Embedded copy of kohesive/injekt (Apache-2.0), api/Registrar.kt + Module.kt
 * + Exceptions.kt (merged — they are tiny).
 * See TypeInfo.kt header for why this is embedded.
 */
package uy.kohesive.injekt.api

interface InjektRegistrar : InjektRegistry, InjektFactory {
    fun importModule(submodule: InjektModule) {
        submodule.registerWith(this)
    }
}

interface InjektModule {
    fun registerWith(intoModule: InjektRegistrar) {
        intoModule.registerInjectables()
    }

    fun InjektRegistrar.registerInjectables()
}

class InjektionException(message: String) : RuntimeException(message)
