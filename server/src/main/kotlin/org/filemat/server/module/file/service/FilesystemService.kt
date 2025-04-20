package org.filemat.server.module.file.service

import me.desair.tus.server.TusFileUploadService
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.FileUtils
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*

/**
 * Service that provides low level filesystem access
 *
 * For easier unit tests
 */
@Service
class FilesystemService {

    final var tusFileService: TusFileUploadService? = null
        private set

    fun initializeTusService() {
        tusFileService = TusFileUploadService()
            .withUploadUri("/api/v1/file/upload")
            .withStoragePath(State.App.uploadFolderPath)
    }

    fun createFolder(folder: FilePath): Result<Unit> {
        return try {
            Files.createDirectories(folder.path)
            Result.ok()
        } catch (e: Exception) {
            Result.error("Failed to create folder.")
        }
    }

    /**
     * Returns whether file is in a supported filesystem
     */
    fun isSupportedFilesystem(path: FilePath): Boolean? {
        return FileUtils.isSupportedFilesystem(path.path)
    }

    fun moveFile(source: FilePath, destination: FilePath, overwriteDestination: Boolean): Result<Unit> {
        return try {
            Files.move(
                source.path,
                destination.path,
                *if (overwriteDestination) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            )
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: Exception) {
            Result.error("Failed to move file.")
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun deleteFile(target: FilePath, recursive: Boolean): Result<Unit> {
        return try {
            if (recursive) {
                target.path.deleteRecursively()
            } else {
                target.path.deleteExisting()
            }
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: Exception) {
            Result.error("Failed to delete file.")
        }
    }


    /**
     * Gets metadata for a file.
     */
    fun getMetadata(path: FilePath): FileMetadata? {
        val attributes = readAttributes(path.path, State.App.followSymLinks)
            ?: return null

        val type = when {
            attributes.isRegularFile -> FileType.FILE
            attributes.isDirectory -> FileType.FOLDER
            attributes.isSymbolicLink -> {
                if (State.App.followSymLinks) {
                    val target = Files.readSymbolicLink(path.path)
                    if (target.isDirectory()) FileType.FOLDER_LINK else FileType.FILE_LINK
                } else {
                    FileType.FILE
                }
            }
            else -> FileType.OTHER
        }
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            filename = path.path.absolutePathString(),
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
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
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
    fun getInode(path: Path, followSymbolicLinks: Boolean) = FileUtils.getInode(path, followSymbolicLinks)

}