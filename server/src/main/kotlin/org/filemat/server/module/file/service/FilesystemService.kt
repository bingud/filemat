package org.filemat.server.module.file.service

import org.filemat.server.common.util.FileUtils
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists

/**
 * Service that provides low level filesystem access
 *
 * For easier unit tests
 */
@Service
class FilesystemService {

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
        return if (followSymbolicLinks){
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } else {
            Files.readAttributes(path.toRealPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
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