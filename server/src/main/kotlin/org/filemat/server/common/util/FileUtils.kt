package org.filemat.server.common.util

import org.apache.tika.Tika
import org.filemat.server.common.State
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.walk

val tika = Tika()

fun getOptionalPathWalkOption(): Array<out PathWalkOption> {
    return if (State.App.followSymlinks) arrayOf(PathWalkOption.FOLLOW_LINKS) else emptyArray()
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

fun Path.safeWalk(vararg options: PathWalkOption): Sequence<Path> = sequence {
    val it = walk(*options).iterator()
    while (true) {
        val next = try {
            if (!it.hasNext()) break
            it.next()
        } catch (e: Exception) {
            continue
        }
        yield(next)
    }
}