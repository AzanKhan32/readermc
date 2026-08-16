package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

/**
 * Port of extensions-lib ConfigurableSource (Apache-2.0). Sources with
 * settings (login, mirror choice, image quality...) implement this. The
 * reader app doesn't render a preference UI yet, but the interface must
 * exist so those extensions load; their getSharedPreferences-based defaults
 * still apply.
 */
interface ConfigurableSource : Source {

    fun setupPreferenceScreen(screen: PreferenceScreen)
}
