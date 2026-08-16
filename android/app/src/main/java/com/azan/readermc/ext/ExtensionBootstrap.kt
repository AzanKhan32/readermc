package com.azan.readermc.ext

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Registers the singletons Tachiyomi extensions resolve via Injekt at
 * runtime. Mirrors the subset of Mihon's AppModule that extensions
 * actually use: Application, NetworkHelper, Json.
 *
 * Call [ExtensionBootstrap.init] once (from MainActivity.load()) BEFORE
 * loading any extension.
 */
object ExtensionBootstrap {

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext as Application
            Injekt.importModule(
                object : InjektModule {
                    override fun InjektRegistrar.registerInjectables() {
                        addSingleton(app)
                        addSingletonFactory { NetworkHelper(app) }
                        addSingletonFactory {
                            Json {
                                ignoreUnknownKeys = true
                                explicitNulls = false
                            }
                        }
                    }
                },
            )
            initialized = true
        }
    }
}
