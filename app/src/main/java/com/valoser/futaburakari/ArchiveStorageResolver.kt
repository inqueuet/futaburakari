package com.valoser.futaburakari

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * アーカイブ用ディレクトリの解決とレガシーパスの統合を担うユーティリティ。
 * ThreadArchiver と DetailCacheManager の双方から利用される。
 */
object ArchiveStorageResolver {
    private const val TAG = "ArchiveStorageResolver"
    private const val PUBLIC_DIR = "Futaburakari/Threads"
    private const val LEGACY_PUBLIC_DIR = "Futaburakari/ThreadArchives"
    private const val APP_SPECIFIC_DIR = "Threads"
    private const val LEGACY_APP_SPECIFIC_DIR = "ThreadArchives"
    private const val LEGACY_INTERNAL_DIR = "archive_media"

    @Volatile
    private var cachedRoot: File? = null

    /**
     * アーカイブのルートディレクトリを返す（存在しない場合は作成）。
     * 初回解決時にレガシーディレクトリの移行も実施する。
     */
    fun resolveArchiveRoot(context: Context): File {
        cachedRoot?.let { return it }
        return synchronized(this) {
            cachedRoot?.let { return it }
            val root = computeArchiveRoot(context)
            cachedRoot = root
            root
        }
    }

    /**
     * アーカイブ内に指定サブディレクトリを作成して返す。
     */
    fun ensureArchiveDirectory(context: Context, dirName: String): File? {
        val root = resolveArchiveRoot(context)
        val dir = File(root, dirName)
        return if (ensureDirectory(dir)) dir else null
    }

    private fun computeArchiveRoot(context: Context): File {
        val legacyInternalRoot = File(context.filesDir, LEGACY_INTERNAL_DIR)

        val candidates = buildList {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { downloads ->
                add(File(downloads, PUBLIC_DIR))
            }
            context.externalMediaDirs?.forEach { base ->
                if (base != null) add(File(base, PUBLIC_DIR))
            }
            context.getExternalFilesDir(null)?.let { add(File(it, APP_SPECIFIC_DIR)) }
            add(legacyInternalRoot)
        }

        val root = candidates.firstOrNull { ensureDirectory(it) }
            ?: legacyInternalRoot.also { ensureDirectory(it) }

        migrateLegacyDirectories(context, root)
        Log.d(TAG, "Using archive root: ${root.absolutePath}")
        return root
    }

    private fun ensureDirectory(dir: File): Boolean {
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.isDirectory && dir.canWrite()
        }.getOrDefault(false)
    }

    private fun migrateLegacyDirectories(context: Context, target: File) {
        val legacyDirs = mutableSetOf<File>()

        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { downloads ->
            legacyDirs += File(downloads, LEGACY_PUBLIC_DIR)
        }
        context.externalMediaDirs?.forEach { base ->
            if (base != null) legacyDirs += File(base, LEGACY_PUBLIC_DIR)
        }
        context.getExternalFilesDir(null)?.let { ext ->
            legacyDirs += File(ext, LEGACY_APP_SPECIFIC_DIR)
        }
        legacyDirs += File(context.filesDir, LEGACY_INTERNAL_DIR)

        legacyDirs
            .filter { it.exists() && it.absolutePath != target.absolutePath }
            .forEach { legacy ->
                migrateContent(legacy, target)
            }
    }

    private fun migrateContent(source: File, target: File) {
        val children = source.listFiles() ?: emptyArray()
        if (children.isEmpty()) {
            runCatching { source.deleteRecursively() }
            return
        }

        Log.d(TAG, "Migrating archive directory ${source.absolutePath} -> ${target.absolutePath}")
        children.forEach { child ->
            val destination = File(target, child.name)
            if (destination.exists()) return@forEach

            runCatching {
                if (child.isDirectory) {
                    child.copyRecursively(destination, overwrite = false)
                } else {
                    child.copyTo(destination, overwrite = false)
                }
                child.deleteRecursively()
            }.onFailure { error ->
                Log.w(TAG, "Failed to migrate ${child.absolutePath}", error)
            }
        }

        val remaining = source.listFiles()
        if (remaining == null || remaining.isEmpty()) {
            runCatching { source.deleteRecursively() }
        }
    }
}
