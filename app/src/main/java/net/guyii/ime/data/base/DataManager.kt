// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package net.guyii.ime.data.base

import android.content.res.AssetManager
import android.os.Build
import net.guyii.ime.util.FileUtils
import net.guyii.ime.util.ResourceUtils
import net.guyii.ime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"
    const val USER_CONFIG_FILE_NAME = "user.yaml"
    const val INSTALLATION_FILE_NAME = "installation.yaml"

    val POST_SCHEMA_DEPLOY_EXPORT_FILES =
        listOf(
            DEFAULT_CUSTOM_FILE_NAME,
            USER_CONFIG_FILE_NAME,
        )

    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    val sharedDataDir = File(appContext.getExternalFilesDir(null), "shared").also { it.mkdirs() }

    private val runtimeUserDataDir =
        File(appContext.getExternalFilesDir(null), "rime").also { it.mkdirs() }

    /** App-scoped path used by Rime at runtime. */
    val userDataDir get() = runtimeUserDataDir

    val prebuiltDataDir = File(sharedDataDir, "build")
    val stagingDir get() = File(userDataDir, "build")

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                    ResourceUtils.copyFile(it.path, destPath)
                }
                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath)

        // No default.custom.yaml is generated: schema list and key bindings ship inside
        // assets/shared/default.yaml. Creating one here would be newer than the prebuilt
        // artifacts in assets/shared/build, making rime treat them as stale and rebuild
        // every dictionary on first launch.

        stampSyncedFiles()

        Timber.d("Synced!")
    }

    /**
     * Pins every extracted file to a fixed modification time.
     *
     * Rime decides whether the compiled artifacts in `shared/build` are still good by
     * comparing each source file's mtime against the `__build_info/timestamps` recorded
     * inside them, and demands an exact match. Extraction from the APK would otherwise
     * stamp the sources with the install time, which never matches, so every fresh
     * install rebuilt all three dictionaries -- around two minutes of spinning before
     * the keyboard could type anything.
     *
     * Pinning makes the mtimes reproducible across installs and devices, so the shipped
     * artifacts stay valid. The compiled artifacts are pinned slightly later than their
     * sources so they never look older than what they were built from.
     *
     * Whenever a file under `assets/shared` changes, the artifacts have to be rebuilt on
     * a device and copied back into `assets/shared/build`, or rime will rebuild them once
     * on the user's phone. See DESIGN.md.
     */
    private fun stampSyncedFiles() {
        val buildDir = prebuiltDataDir.absolutePath
        sharedDataDir.walkTopDown().filter { it.isFile }.forEach {
            val stamp = if (it.absolutePath.startsWith(buildDir)) BUILT_MTIME else SOURCE_MTIME
            it.setLastModified(stamp)
        }
    }

    /** Arbitrary fixed instant (2024-01-01T00:00:00Z); only its stability matters. */
    private const val SOURCE_MTIME = 1_704_067_200_000L
    private const val BUILT_MTIME = SOURCE_MTIME + 60_000L
}
