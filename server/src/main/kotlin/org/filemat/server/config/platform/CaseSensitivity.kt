package org.filemat.server.config.platform

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Per-folder case sensitivity (§2.3).
 *
 * POSIX folders are always case-sensitive. Windows folders are case-insensitive unless they have
 * the flag set by `fsutil file setCaseSensitiveInfo <folder> enable` (WSL sets it on folders it creates).
 */
object CaseSensitivity {
    // An admin can toggle the flag while the server runs, so flags expire quickly
    private val cache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build()

    fun isCaseSensitive(folder: Path): Boolean {
        if (!Platform.isWindows) return true

        // Keyed by the exact string, because `WindowsPath` equality ignores case
        val cacheKey = folder.toString()
        cache.getIfPresent(cacheKey)?.let { return it }

        // A folder that does not exist yet is not cached, so it is read again once it is created.
        // One whose filesystem has no flag (FAT, exFAT) is, or every path key would open each folder again.
        val flag = WindowsNative.isCaseSensitiveDirectory(folder)
            ?: if (Files.isDirectory(folder)) false else return false
        cache.put(cacheKey, flag)
        return flag
    }
}
