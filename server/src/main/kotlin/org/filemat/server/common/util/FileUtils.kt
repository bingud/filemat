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
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

val tika = Tika()


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
    // The Windows drive list is not a real folder, so walk each drive
    if (PathPolicy.isVirtualRoot(this@safeWalk)) {
        if (!include(this@safeWalk)) return@flow
        emit(this@safeWalk)
        for (drive in PathPolicy.virtualRootEntries()) {
            emitAll(drive.safeWalk(with, followDirectoryLinks, include, onListFailed, ancestorRealPaths))
        }
        return@flow
    }

    val lock = with?.getLock(this@safeWalk, LockType.READ)
    if (lock?.successful == false) return@flow

    try {
        if (!include(this@safeWalk)) return@flow

        // Emit the current path itself
        emit(this@safeWalk)

        // Traverse children if directory. Junctions and mount points look like directories even without following links.
        val isWalkedDirectory = if (followDirectoryLinks) {
            // A link to a network folder is never followed (§4.3)
            !(Files.isSymbolicLink(this@safeWalk) && reachesNetwork(this@safeWalk)) && Files.isDirectory(this@safeWalk)
        } else {
            Files.isDirectory(this@safeWalk, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(this@safeWalk)
        }
        if (isWalkedDirectory) {
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
 * Directory sizes are not summed. Links (symlinks, junctions) count as files and are not followed.
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

        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return@collect
        }

        if (attrs.isDirectory && !attrs.isSymbolicLink && !LinkInspector.isDirectoryLink(path)) {
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

/**
 * True when [path] is [root] or a descendant. Uses path components, so `/share` does not match `/share-extra`.
 */
fun isPathInside(path: Path, root: Path): Boolean {
    val normalizedPath = path.normalize()
    val normalizedRoot = root.normalize()
    if (!normalizedPath.startsWith(normalizedRoot)) return false
    if (!Platform.isWindows) return true
    // Windows `startsWith` ignores case, but a case-sensitive folder can hold both `Share` and `SHARE` (§2.3)
    return PathPolicy.isKeyWithin(PathPolicy.pathKey(normalizedPath), PathPolicy.pathKey(normalizedRoot))
}

/**
 * Creates the folder [dest] and the missing folders above it, for a move or copy of [sourceRoot] that rebuilds folders one by one.
 * [source] is the folder within [sourceRoot] that [dest] stands for, or null when it is above it.
 * On Windows each new folder within the tree is made case-sensitive when its source folder is (§2.3), or a folder holding
 * `a.txt` and `A.txt` would lose one. Folders above it are created plainly.
 * Throws when a folder can't be created or can't be made case-sensitive.
 */
fun createFoldersLike(dest: Path, source: Path?, sourceRoot: Path) {
    if (!Platform.isWindows) {
        Files.createDirectories(dest)
        return
    }
    createFolderLike(dest, source, sourceRoot)
}

private fun createFolderLike(dest: Path, source: Path?, sourceRoot: Path) {
    if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) return
    val parentSource = if (source == null || source == sourceRoot) null else source.parent
    dest.parent?.let { createFolderLike(it, parentSource, sourceRoot) }

    Files.createDirectory(dest)
    if (source != null && WindowsNative.isCaseSensitiveDirectory(source) == true && WindowsNative.setCaseSensitiveDirectory(dest) != true) {
        runCatching { Files.delete(dest) }
        throw IOException("A case-sensitive folder can't be created at $dest.")
    }
}

/**
 * Returns whether moving [source] to [destination] only changes the case of its name (`photo.jpg` to `Photo.jpg`).
 * Both paths then name the same file, in a case-insensitive folder. Always false on Linux.
 */
fun isCaseOnlyRename(source: FilePath, destination: FilePath): Boolean {
    if (source.pathString == destination.pathString) return false
    if (source.pathKey != destination.pathKey) return false
    return runCatching { Files.isSameFile(source.path, destination.path) }.getOrDefault(false)
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