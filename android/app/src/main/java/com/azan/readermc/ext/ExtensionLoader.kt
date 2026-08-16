package com.azan.readermc.ext

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

/**
 * Loads Mihon/Tachiyomi extension APKs from the app's private storage
 * ("private extension" style — no system package install needed).
 * Modeled on Mihon's ExtensionLoader (Apache-2.0).
 */
object ExtensionLoader {

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    // Supported extensions-lib versions.
    // Mihon uses a discrete list, not a range: the repo only ever ships 1.4 and 1.6,
    // and 1.2/1.3/1.5 no longer exist. Keeping 1.2/1.3 costs nothing and keeps any
    // older side-loaded APKs working.
    private val SUPPORTED_LIB_VERSIONS = listOf(1.2, 1.3, 1.4, 1.5, 1.6)

    const val EXT_DIR = "exts"

    data class LoadedExtension(
        val pkgName: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val libVersion: Double,
        val isNsfw: Boolean,
        val sources: List<Source>,
        val apkFile: File,
    )

    fun extensionsDir(context: Context): File =
        File(context.filesDir, EXT_DIR).apply { mkdirs() }

    /** Loads every APK in the private extensions dir. Broken ones are skipped, not fatal. */
    fun loadAll(context: Context): List<LoadedExtension> {
        val files = extensionsDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: return emptyList()
        return files.mapNotNull { apk ->
            try {
                load(context, apk)
            } catch (e: Throwable) {
                android.util.Log.e("ExtensionLoader", "Failed to load ${apk.name}", e)
                null
            }
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    fun load(context: Context, apkFile: File): LoadedExtension {
        val pm = context.packageManager
        val apkPath = apkFile.absolutePath

        @Suppress("DEPRECATION")
        val pkgInfo: PackageInfo = pm.getPackageArchiveInfo(
            apkPath,
            PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
        ) ?: throw IllegalStateException("Could not parse APK: ${apkFile.name}")

        val hasFeature = pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
        if (!hasFeature) {
            throw IllegalStateException("${apkFile.name} is not a Tachiyomi extension APK")
        }

        val appInfo = pkgInfo.applicationInfo
            ?: throw IllegalStateException("APK has no application info: ${apkFile.name}")
        // Required for loadLabel/metadata on an archive (not installed) APK.
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath

        val versionName = pkgInfo.versionName
            ?: throw IllegalStateException("Missing versionName in ${apkFile.name}")
        @Suppress("DEPRECATION")
        val versionCode = pkgInfo.versionCode.toLong()

        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
            ?: throw IllegalStateException("Invalid versionName $versionName")
        if (SUPPORTED_LIB_VERSIONS.none { it == libVersion }) {
            throw IllegalStateException(
                "Lib version $libVersion of ${pkgInfo.packageName} is not supported " +
                    "(supported: ${SUPPORTED_LIB_VERSIONS.joinToString()})",
            )
        }

        val isNsfw = appInfo.metaData?.getInt(METADATA_NSFW, 0) == 1
        val label = appInfo.loadLabel(pm).toString().substringAfter("Tachiyomi: ")

        // Class names: "tachiyomi.extension.class" is a ;-separated list;
        // names starting with "." are relative to the package.
        val metaClasses = appInfo.metaData?.getString(METADATA_SOURCE_CLASS)
            ?: appInfo.metaData?.getString(METADATA_SOURCE_FACTORY)
            ?: throw IllegalStateException("No source class metadata in ${apkFile.name}")

        val classLoader = DexClassLoader(
            apkPath,
            File(context.codeCacheDir, "ext_opt").apply { mkdirs() }.absolutePath,
            appInfo.nativeLibraryDir,
            context.classLoader,
        )

        val sources = metaClasses
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) pkgInfo.packageName + it else it }
            .flatMap { className ->
                val clazz = Class.forName(className, false, classLoader)
                when (val obj = clazz.getDeclaredConstructor().newInstance()) {
                    is Source -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> throw IllegalStateException("Unknown source class type: $className")
                }
            }

        if (sources.isEmpty()) {
            throw IllegalStateException("Extension ${pkgInfo.packageName} provides no sources")
        }

        return LoadedExtension(
            pkgName = pkgInfo.packageName,
            name = label,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            isNsfw = isNsfw,
            sources = sources,
            apkFile = apkFile,
        )
    }

    fun langOf(source: Source): String =
        (source as? CatalogueSource)?.lang ?: source.lang

    /**
     * Extracts the extension APK's launcher icon and returns it as a
     * "data:image/png;base64,..." URI the WebView can drop straight into an
     * <img src>. Returns null when the APK has no usable icon.
     *
     * loadIcon() can hand back any Drawable subclass (BitmapDrawable,
     * AdaptiveIconDrawable, vector), so the drawable is rendered onto a canvas
     * rather than cast. 96px is plenty for list rows and keeps the base64
     * payload ~5-10 KB per extension.
     */
    fun iconDataUri(context: Context, apkFile: File): String? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            val appInfo = pkgInfo.applicationInfo ?: return null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath

            val drawable = appInfo.loadIcon(pm) ?: return null
            val size = 96
            val bitmap = android.graphics.Bitmap.createBitmap(
                size,
                size,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)

            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        } catch (e: Throwable) {
            android.util.Log.w("ExtensionLoader", "icon extraction failed for ${apkFile.name}", e)
            null
        }
    }
}
