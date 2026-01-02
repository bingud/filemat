package org.filemat.server.common.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.apache.tika.Tika
import org.filemat.server.common.State
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.walk

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