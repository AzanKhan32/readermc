package eu.kanade.tachiyomi

/**
 * Port of extensions-lib AppInfo (Apache-2.0). A few extensions check the
 * host app version to gate features; report a recent Mihon-equivalent
 * version so nothing gets artificially disabled.
 */
object AppInfo {
    fun getVersionCode(): Int = 999999

    fun getVersionName(): String = "0.18.0"
}
