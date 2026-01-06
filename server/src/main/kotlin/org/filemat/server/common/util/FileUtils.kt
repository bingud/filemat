package org.filemat.server.common.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.apache.tika.Tika
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

val tika = Tika()

fun getNoFollowLinksOption(): Array<out LinkOption> {
    return if (State.App.followSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
}

object FileUtils {
    fun getInode(path: Path, followSymbolicLinks: Boolean): Long? {
        val options = if (followSymbolicLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        return try {
            Files.getAttribute(path, "unix:ino", *options) as Long
        } catch (e: Exception) {
            null
        }
    }


    fun isSupportedFilesystem(path: Path): Boolean? {
        if (!Files.exists(path)) return null
        return try {
            Files.getFileStore(path).supportsFileAttributeView("unix")
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Processes all files while avoiding a protected file.
 *
 * Always processes the highest possible top-level folder - which doesnt contain the protected file.
 */
fun walkFilesWithExcludedFile(
    path: Path,
    excludedPath: Path,
    recursive: Boolean = false,
    failedResult: (count: Int) -> Result<Int>,
    action: (file: Path) -> Boolean,
): Result<Int> {
    if (!path.isAbsolute || !excludedPath.isAbsolute) return Result.error("Input file path is not absolute.")

    val children = runCatching {
        path.listDirectoryEntries()
    }.getOrElse { return Result.error("Failed to list folder entries.") }

    var failedCount = 0

    for (child: Path in children) {
        when {
            child == excludedPath -> {
                continue
            }

            excludedPath.startsWith(child) -> {
                val sub = walkFilesWithExcludedFile(child, excludedPath, true, failedResult, action)
                failedCount += sub.value
            }

            else -> {
                val deletionResult = action(child)
                if (!deletionResult) failedCount++
            }
        }
    }

    if (!recursive && failedCount > 0) {
        return failedResult(failedCount)
    }

    return Result.ok(failedCount)
}


fun Path.safeWalk(): Flow<Path> = flow {
    // Emit the current path itself
    emit(this@safeWalk)

    // Traverse children if directory
    if (Files.isDirectory(this@safeWalk)) {
        try {
            // newDirectoryStream is lazy and allows catching access errors per directory
            Files.newDirectoryStream(this@safeWalk).use { stream ->
                for (path in stream) {
                    // Recursively walk children
                    emitAll(path.safeWalk())
                }
            }
        } catch (_: Exception) {}
    }
}

data class PathRelationship(
    val isInsideTarget: Boolean,
    val containsTarget: Boolean,
    val isEqual: Boolean
)

fun getPathRelationship(path: Path, target: Path): PathRelationship {
    val isInside = path.startsWith(target)
    val contains = target.startsWith(path)
    return PathRelationship(
        isInsideTarget = isInside,
        containsTarget = contains,
        isEqual = isInside && contains
    )
}