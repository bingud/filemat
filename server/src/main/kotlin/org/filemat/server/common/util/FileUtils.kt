package org.filemat.server.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.apache.tika.Tika
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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

/**
 * Depth-first walk that does not follow directory links.
 * [onListFailed] runs when a directory cannot be listed, including the walk root.
 */
fun Path.safeWalk(
    with: FileLockService? = null,
    followDirectoryLinks: Boolean = false,
    include: (Path) -> Boolean = { true },
    onListFailed: () -> Unit = {},
): Flow<Path> = safeWalk(
    with = with,
    followDirectoryLinks = followDirectoryLinks,
    include = include,
    onListFailed = onListFailed,
    ancestorRealPaths = emptySet(),
)

private fun Path.safeWalk(
    with: FileLockService?,
    followDirectoryLinks: Boolean,
    include: (Path) -> Boolean,
    onListFailed: () -> Unit,
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
                        currentCoroutineContext().ensureActive()
                        emitAll(
                            path.safeWalk(
                                with = with,
                                followDirectoryLinks = followDirectoryLinks,
                                include = include,
                                onListFailed = onListFailed,
                                ancestorRealPaths = childAncestors,
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onListFailed()
            }
        }
    } finally {
        if (lock != null) lock.unlock()
    }
}

data class FolderSize(
    val fileCount: Long,
    val folderCount: Long,
    val totalSize: Long,
    val failedFolderCount: Long,
)

/**
 * Counts nested files and folders under this path. The starting folder is not included in [FolderSize.folderCount].
 * Directory sizes are not summed. Symlinks count as files and are not followed.
 *
 * Fails when the root path is never visited (for example a missed read lock) or when the root directory cannot be listed.
 */
suspend fun Path.measureFolderContents(with: FileLockService? = null): Result<FolderSize> {
    var fileCount = 0L
    var folderCount = 0L
    var totalSize = 0L
    var failedFolderCount = 0L
    var skipRoot = true
    var seenDescendant = false
    var rootListFailed = false

    this@measureFolderContents.safeWalk(
        with = with,
        onListFailed = {
            if (!seenDescendant) rootListFailed = true
            else failedFolderCount++
        },
    ).collect { path ->
        currentCoroutineContext().ensureActive()

        if (skipRoot) {
            skipRoot = false
            return@collect
        }

        seenDescendant = true

        println("path: $path")
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return@collect
        }

        if (attrs.isDirectory && !attrs.isSymbolicLink) {
            folderCount++
            return@collect
        }

        fileCount++
        totalSize += attrs.size()
    }

    if (skipRoot) {
        return if (with != null) {
            Result.reject("This folder is currently being modified.")
        } else {
            Result.error("Failed to read this folder.")
        }
    }
    if (rootListFailed) {
        return Result.error("Failed to read this folder.")
    }

    return Result.ok(
        FolderSize(
            fileCount = fileCount,
            folderCount = folderCount,
            totalSize = totalSize,
            failedFolderCount = failedFolderCount,
        )
    )
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