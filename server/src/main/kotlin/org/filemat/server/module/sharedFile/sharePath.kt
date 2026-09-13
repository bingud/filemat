package org.filemat.server.module.sharedFile

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.isPathInside
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.sharedFile.model.FileShare
import java.nio.file.Files
import java.nio.file.Path

/**
 * Join a share-relative path onto the shared root and resolve it.
 *
 * All shared-file IO must go through this (via [org.filemat.server.module.file.service.file.FileService.resolvePathWithOptionalShare])
 * so listing, download, zip, search, thumbnails, and future operations share one policy.
 * Recursive walks re-apply it per child with [resolveSharedDescendant].
 *
 * - Path traversal out of the share is rejected.
 * - System-wide symlink following still applies.
 * - When [followOutboundSymlinks] is false, symlink targets outside the share are not followed,
 *   including bounce-back links that leave the share and return.
 * - Symlinks whose immediate targets stay inside the share are allowed when system-wide following is on.
 */
fun resolveSharedFilePath(
    relativePath: FilePath,
    shareRoot: FilePath,
    followOutboundSymlinks: Boolean = FileShare.followSymlinks,
): Result<FilePath> {
    val joined = joinSharePath(shareRoot.path, relativePath)
    if (!isPathInside(joined, shareRoot.path)) {
        return Result.notFound()
    }

    val resolved = resolvePath(FilePath.ofAlreadyNormalized(joined)).let {
        if (it.isNotSuccessful) return it
        it.value
    }

    if (!followOutboundSymlinks) {
        if (!isPathInside(resolved.path, shareRoot.path)) {
            return Result.notFound()
        }
        if (!symlinkImmediateTargetsStayInside(joined, shareRoot.path)) {
            return Result.notFound()
        }
    }

    return resolved.toResult()
}

/**
 * Resolve an already-absolute descendant of [shareRoot] with the same policy as [resolveSharedFilePath].
 * Recursive listing, search, and zip must use this so children are not handled with a weaker check.
 */
fun resolveSharedDescendant(
    absolutePath: Path,
    shareRoot: FilePath,
    followOutboundSymlinks: Boolean = FileShare.followSymlinks,
): Result<FilePath> {
    val relative = toShareRelativePath(absolutePath, shareRoot.path) ?: return Result.notFound()
    return resolveSharedFilePath(relative, shareRoot, followOutboundSymlinks)
}

internal fun toShareRelativePath(absolutePath: Path, shareRoot: Path): FilePath? {
    if (!isPathInside(absolutePath, shareRoot)) return null
    val relative = shareRoot.normalize().relativize(absolutePath.normalize())
    val relativeStr = relative.toString().replace('\\', '/')
    if (relativeStr.isEmpty() || relativeStr == ".") return FilePath.of("/")
    return FilePath.of(relativeStr)
}

internal fun joinSharePath(shareRoot: Path, relativePath: FilePath): Path {
    val relativeStr = relativePath.path.toString().trimStart('/', '\\')
    if (relativeStr.isEmpty() || relativeStr == ".") {
        return shareRoot.normalize()
    }
    return shareRoot.resolve(relativeStr).normalize()
}

/**
 * False if any symlink along [path] points (immediately) outside [root].
 * Does not follow those outbound targets.
 */
internal fun symlinkImmediateTargetsStayInside(path: Path, root: Path): Boolean {
    var current: Path = path.root ?: return false

    for (segment in path) {
        current = current.resolve(segment)
        if (!Files.isSymbolicLink(current)) continue

        val immediate = runCatching {
            val target = Files.readSymbolicLink(current)
            (current.parent ?: return false).resolve(target).normalize()
        }.getOrElse { return false }

        if (!isPathInside(immediate, root)) {
            return false
        }
    }

    return true
}
