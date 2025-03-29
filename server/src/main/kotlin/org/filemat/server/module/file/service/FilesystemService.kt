package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.util.FileUtils
import org.filemat.server.common.util.getFileType
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * Service that provides low level filesystem access
 *
 * For easier unit tests
 */
@Service
class FilesystemService {

    /**
     * Gets metadata for a file.
     */
    fun getMetadata(path: FilePath): FileMetadata? {
        val attributes = readAttributes(path.pathObject, State.App.followSymLinks)
            ?: return null

        val type = attributes.getFileType()
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            filename = path.pathObject.absolutePathString(),
            modifiedDate = modificationTime,
            createdDate = creationTime,
            fileType = type,
            size = attributes.size(),
        )
    }

    /**
     * Returns list of files for a folder
     */
    fun listFiles(file: File): List<File>? {
        return file.listFiles()?.toList()
    }

    /**
     * Returns basic file attributes of path
     */
    fun readAttributes(path: Path, followSymbolicLinks: Boolean): BasicFileAttributes? {
        return runCatching {
            if (followSymbolicLinks) {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } else {
                Files.readAttributes(path.toRealPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        }.getOrNull()
    }

    /**
     * Returns whether input file path exists
     */
    fun exists(path: Path, followSymbolicLinks: Boolean) = if (followSymbolicLinks) path.exists() else path.exists(LinkOption.NOFOLLOW_LINKS)

    /**
     * Returns inode number for input path
     */
    fun getInode(path: String) = FileUtils.getInode(path)

}