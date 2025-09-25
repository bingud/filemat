package org.filemat.server.module.file.service

import me.desair.tus.server.TusFileUploadService
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FileUtils
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.springframework.stereotype.Service
import java.io.File
import java.lang.UnsupportedOperationException
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributes
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

    fun getSize(canonicalPath: FilePath): Result<Long> {
        try {
            return canonicalPath.path.fileSize().toResult()
        }catch (e: NoSuchFileException) {
            return Result.notFound()
        } catch (e: Exception) {
            return Result.error("Failed to get file size.")
        }
    }

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

    fun deleteFile(path: FilePath): Result<Unit> {
        val file = path.path.toFile()
        if (!file.exists()) return Result.notFound()

        val dataPath = Props.dataFolder

        // 1) Deleting the data folder itself?
        if (file.absolutePath.startsWith(dataPath)) {
            return if (State.App.allowWriteDataFolder) {
                if (file.deleteRecursively()) Result.ok()
                else Result.error("Failed to delete data folder.")
            } else {
                Result.reject("Cannot edit ${Props.appName} data folder.")
            }
        }

        // 2) Deleting a directory that contains the data folder, but write is disallowed?
        if (!State.App.allowWriteDataFolder
            && file.isDirectory
            && dataPath.startsWith(file.absolutePath)
        ) {
            return deleteChildrenManually(file, dataPath)
        }

        // 3) Everything else: normal recursive delete
        return if (file.deleteRecursively()) Result.ok()
        else Result.error("File deletion failed.")
    }

    private fun deleteChildrenManually(directory: File, dataPath: String): Result<Unit> {
        val children = directory.listFiles()
            ?: return Result.error("Failed to list directory contents.")
        var failed = false

        for (child in children) {
            when {
                // 3a) the data folder itself: dive in but never remove it
                child.absolutePath == dataPath -> {
                    val sub = deleteChildrenManually(child, dataPath)
                    if (sub.hasError) failed = true
                }
                // 3b) anything else: drop the whole subtree
                else -> {
                    if (!child.deleteRecursively()) {
                        System.err.println("Failed to delete: ${child.absolutePath}")
                        failed = true
                    }
                }
            }
        }

        return if (failed) Result.error("Some files could not be deleted.")
        else Result.ok()
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
        } catch (e: FileAlreadyExistsException) {
            Result.error("This file already exists.")
        } catch (e: DirectoryNotEmptyException) {
            Result.reject("This directory cannot be replaced because it is not empty.")
        } catch (e: UnsupportedOperationException) {
            Result.error("This move operation failed because it is unsupported.")
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
        val attributes: PosixFileAttributes = readAttributes(path.path, followSymbolicLinks = false)
            ?: return null

        val type = when {
            attributes.isRegularFile -> FileType.FILE
            attributes.isDirectory -> FileType.FOLDER
            attributes.isSymbolicLink -> {
                val target = Files.readSymbolicLink(path.path)
                val resolved = path.path.parent.resolve(target).normalize()
                if (Files.isDirectory(resolved)) FileType.FOLDER_LINK else FileType.FILE_LINK
            }
            else -> FileType.OTHER
        }
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            path = path.path.absolutePathString(),
            modifiedDate = modificationTime,
            createdDate = creationTime,
            fileType = type,
            size = attributes.size(),
            isExecutable = if (State.App.followSymlinks) Files.isExecutable(path.path) else true,
            isWritable = if (State.App.followSymlinks) Files.isExecutable(path.path) else false,
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
    fun readAttributes(path: Path, followSymbolicLinks: Boolean): PosixFileAttributes? {
        return runCatching {
            if (followSymbolicLinks) {
                Files.readAttributes(path, PosixFileAttributes::class.java)
            } else {
                Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
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