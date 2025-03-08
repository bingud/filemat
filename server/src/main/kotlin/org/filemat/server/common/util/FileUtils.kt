package org.filemat.server.common.util

import org.filemat.server.module.file.model.FileType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

object FileUtils {

    fun getInode(path: String) = getInode(Paths.get(path))
    fun getInode(path: Path): Long? {
        return try {
            Files.getAttribute(path, "unix:ino") as Long
        } catch (e: Exception) {
            null
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

fun BasicFileAttributes.getFileType(): FileType = if (this.isRegularFile) FileType.FILE else if (this.isSymbolicLink) FileType.ANY_LINK else if (this.isDirectory) FileType.FOLDER else FileType.OTHER