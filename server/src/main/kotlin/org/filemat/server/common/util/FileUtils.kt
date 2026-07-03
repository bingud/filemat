package org.filemat.server.common.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.apache.tika.Tika
import org.filemat.server.common.State
import org.filemat.server.common.platform.Platform
import org.filemat.server.common.platform.PathPolicy
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

val tika = Tika()


object FileUtils {
    data class FileIdentity(
        val fileKey: String?,
        val inode: Long?,
        val isStable: Boolean,
    )

    fun getInode(path: Path, followSymbolicLinks: Boolean): Long? {
        val options = if (followSymbolicLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        return try {
            Files.getAttribute(path, "unix:ino", *options) as Long
        } catch (e: Exception) {
            null
        }
    }

    fun getFileIdentity(path: Path, followSymbolicLinks: Boolean): FileIdentity? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val options = if (followSymbolicLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

        val inode = getInode(path, followSymbolicLinks)
        val posixDev = getUnixDevice(path, followSymbolicLinks)
        if (inode != null && posixDev != null) {
            return FileIdentity(fileKey = "posix:$posixDev:$inode", inode = inode, isStable = true)
        }

        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, *options)
        }.getOrNull() ?: return null

        val providerKey = attributes.fileKey()?.toString()
        if (!providerKey.isNullOrBlank()) {
            val storeKey = runCatching { Files.getFileStore(path).stableKey() }.getOrDefault("unknown-store")
            val platform = if (Platform.isWindows) "windows" else "provider"
            return FileIdentity(fileKey = "$platform:$storeKey:$providerKey", inode = inode, isStable = true)
        }

        return FileIdentity(fileKey = null, inode = inode, isStable = false)
    }


    fun isSupportedFilesystem(path: Path): Boolean? {
        return getFileIdentity(path, followSymbolicLinks = false)?.isStable
    }

    private fun getUnixDevice(path: Path, followSymbolicLinks: Boolean): Long? {
        val options = if (followSymbolicLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        return try {
            Files.getAttribute(path, "unix:dev", *options) as Long
        } catch (e: Exception) {
            null
        }
    }

    private fun FileStore.stableKey(): String {
        return "${name()}:${type()}"
    }
}



fun Path.safeWalk(with: FileLockService? = null): Flow<Path> = flow {
    val lock = with?.getLock(this@safeWalk, LockType.READ)
    if (lock?.successful == false) return@flow

    try {
        // Emit the current path itself
        emit(this@safeWalk)

        // Traverse children if directory
        val options = if (State.App.followSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        if (Files.isDirectory(this@safeWalk, *options)) {
            try {
                // newDirectoryStream is lazy and allows catching access errors per directory
                Files.newDirectoryStream(this@safeWalk).use { stream ->
                    for (path in stream) {
                        // Recursively walk children
                        emitAll(path.safeWalk(with))
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
    val isInside = PathPolicy.startsWith(path, target)
    val contains = PathPolicy.startsWith(target, path)
    return PathRelationship(
        isInsideTarget = isInside,
        containsTarget = contains,
        isEqual = isInside && contains
    )
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