package org.filemat.server.common.util

import org.apache.tika.Tika
import org.filemat.server.module.file.model.FileType
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
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


    fun getInode(attributes: BasicFileAttributes): Long? {
        return attributes.fileKey()?.toString().orEmpty()
            .substringAfter("ino=").let {
                val inode = StringBuilder()
                it.forEach { char ->
                    if (char.isDigit()) {
                        inode.append(char)
                    } else {
                        return@forEach
                    }
                }
                inode.toString().toLongOrNull()
            }
    }

    fun findFilePathByInode(inode: Long, searchDir: String): String? {
        val process = ProcessBuilder("find", searchDir, "-inum", inode.toString()).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        return if (result.isNotEmpty()) result else null
    }
}