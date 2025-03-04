package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.util.FileType
import org.filemat.server.common.util.FileUtils
import org.filemat.server.common.util.controller.AController
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

data class FileMeta(
    val filename: String,
    val modificationTime: Long,
    val creationTime: Long,
    val fileType: FileType,
    val size: Long,
    val inode: Long,
)

@RestController
@RequestMapping("/v1/folder")
class FileController : AController() {

    @PostMapping("/list")
    fun listFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") _rawPath: String
    ): ResponseEntity<String> {
        val rawPath = "/home/wsl/test/anotherfile.txt"
        val path = Paths.get(rawPath)

        val inode = FileUtils.getInode(path)
        val modTime = FileUtils.getLastModifiedTime(path)
        val creationTime = FileUtils.getCreationTime(path)

        return ok()
    }

}


fun getFileMeta(filePath: String): FileMeta {
    val path = Paths.get(filePath)

    /*
     * 1) NOFOLLOW_LINKS read:
     *    We first read basic attributes without following links.
     *    This is the only blocking call for normal files/folders.
     */
    val noFollowAttrs = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )

    /*
     * Check if the path itself is a symbolic link.
     * If not, we can determine everything in one go.
     */
    val isLink = noFollowAttrs.isSymbolicLink

    // Decide whether we need a second read (only for symbolic links).
    val (fileType, size, creationTime, modificationTime, fileKey) = if (!isLink) {
        // Not a symbolic link - single call is enough
        val fileType = when {
            noFollowAttrs.isDirectory -> FileType.FOLDER
            noFollowAttrs.isRegularFile -> FileType.FILE
            else -> FileType.OTHER
        }
        listOf(
            fileType,
            noFollowAttrs.size(),
            noFollowAttrs.creationTime().to(TimeUnit.SECONDS),
            noFollowAttrs.lastModifiedTime().toMillis(),
            noFollowAttrs.fileKey()
        )
    } else {
        /*
         * 2) FOLLOW_LINKS read:
         *    If it's a symbolic link, we do one more read to see if the *target* is a file or folder.
         */
        val followAttrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val linkedType = when {
            followAttrs.isDirectory -> FileType.FOLDER_LINK
            followAttrs.isRegularFile -> FileType.FILE_LINK
            else -> FileType.OTHER
        }
        listOf(
            linkedType,
            followAttrs.size(),
            followAttrs.creationTime().to(TimeUnit.SECONDS),
            followAttrs.lastModifiedTime().toMillis(),
            followAttrs.fileKey()
        )
    }

    /*
     * Attempt to parse the inode from the fileKey (if provided by the system).
     * On many Unix-like systems, fileKey() includes something like "ino=123456".
     */
    val inode = fileKey
        ?.toString()
        ?.substringAfter("ino=", missingDelimiterValue = "")
        ?.substringBefore(",")
        ?.toLongOrNull() ?: -1L

    return FileMeta(
        filename = path.fileName.toString(),
        modificationTime = modificationTime,
        creationTime = creationTime,
        fileType = fileType as FileType,
        size = size as Long,
        inode = inode
    )
}
