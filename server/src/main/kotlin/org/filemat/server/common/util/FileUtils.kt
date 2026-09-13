package org.filemat.server.common.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.apache.tika.Tika
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

val tika = Tika()


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
 * Real path used to detect directory cycles. Follows links/junctions; falls back to a normalized absolute path.
 */
fun Path.walkIdentity(): Path = try {
    toRealPath()
} catch (_: Exception) {
    toAbsolutePath().normalize()
}

fun Path.isAlreadyWalked(ancestors: Set<Path>): Boolean {
    if (ancestors.isEmpty()) return false
    val identity = walkIdentity()
    if (identity in ancestors) return true
    return ancestors.any { ancestor ->
        runCatching { Files.isSameFile(identity, ancestor) }.getOrDefault(false)
    }
}

internal fun Set<Path>.plusWalkIdentity(path: Path): Set<Path> {
    val identity = path.walkIdentity()
    if (identity in this) return this
    val next = LinkedHashSet<Path>(size + 1)
    next.addAll(this)
    next.add(identity)
    return next
}

fun Path.safeWalk(
    with: FileLockService? = null,
    followDirectoryLinks: Boolean = false,
    include: (Path) -> Boolean = { true },
): Flow<Path> = safeWalk(
    with = with,
    followDirectoryLinks = followDirectoryLinks,
    include = include,
    ancestorRealPaths = emptySet(),
)

private fun Path.safeWalk(
    with: FileLockService?,
    followDirectoryLinks: Boolean,
    include: (Path) -> Boolean,
    ancestorRealPaths: Set<Path>,
): Flow<Path> = flow {
    val lock = with?.getLock(this@safeWalk, LockType.READ)
    if (lock?.successful == false) return@flow

    try {
        if (!include(this@safeWalk)) return@flow

        // Emit the current path itself
        emit(this@safeWalk)

        // Traverse children if directory
        val directoryOptions = if (followDirectoryLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        if (Files.isDirectory(this@safeWalk, *directoryOptions)) {
            if (this@safeWalk.isAlreadyWalked(ancestorRealPaths)) return@flow
            val childAncestors = ancestorRealPaths.plusWalkIdentity(this@safeWalk)

            try {
                // newDirectoryStream is lazy and allows catching access errors per directory
                Files.newDirectoryStream(this@safeWalk).use { stream ->
                    for (path in stream) {
                        emitAll(
                            path.safeWalk(
                                with = with,
                                followDirectoryLinks = followDirectoryLinks,
                                include = include,
                                ancestorRealPaths = childAncestors,
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    } finally {
        if (lock != null) lock.unlock()
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

/**
 * True when [path] is [root] or a descendant. Uses path components, so `/share` does not match `/share-extra`.
 */
fun isPathInside(path: Path, root: Path): Boolean {
    val normalizedPath = path.normalize()
    val normalizedRoot = root.normalize()
    return normalizedPath.startsWith(normalizedRoot)
}

fun isFileStoreMatching(
    one: Path,
    two: Path
): Boolean? {
    try {
        val oneStore = Files.getFileStore(one)
        val twoStore = Files.getFileStore(two)

        return oneStore == twoStore
    } catch (e: Exception) {
        return null
    }
}