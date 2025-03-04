package org.filemat.server.common.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

enum class FileType {
    FILE,
    FOLDER,
    FILE_LINK,
    FOLDER_LINK,
    OTHER
}

object FileUtils {

    fun getInode(path: String) = getInode(Paths.get(path))
    fun getInode(path: Path): Long? {
        return try {
            Files.getAttribute(path, "unix:ino") as Long
        } catch (e: Exception) {
            null
        }
    }

    fun getLastModifiedTime(path: String) = getLastModifiedTime(Paths.get(path))
    fun getLastModifiedTime(path: Path): Long? {
        return try {
            Files.getLastModifiedTime(path).to(TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun getCreationTime(path: String) = getCreationTime(Paths.get(path))
    fun getCreationTime(path: Path): Long? {
        return try {
            val time = Files.getAttribute(path, "basic:creationTime") as FileTime
            time.to(TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun getType(path: Path): FileType? {
        try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            return when {
                attrs.isDirectory -> FileType.FOLDER
                attrs.isRegularFile -> FileType.FILE
                attrs.isSymbolicLink -> TODO()
                else -> FileType.OTHER
            }
        } catch (e: Exception) {
            return null
        }
    }
}