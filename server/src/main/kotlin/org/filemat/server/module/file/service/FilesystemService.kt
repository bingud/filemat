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
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
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
class FilesystemService(
    private val logService: LogService,
) {

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

    private fun deleteChildrenManually(path: Path, excludedPath: String): Result<Unit> {
        val children = runCatching {
            path.listDirectoryEntries()
        } .getOrElse { return Result.error("Failed to list folder entries.") }

        var failed = false

        for (child in children) {
            when {
                // 3a) the data folder itself: dive in but never remove it
                child.pathString == excludedPath -> {
                    val sub = deleteChildrenManually(child, excludedPath)
                    if (sub.hasError) failed = true
                }
                // 3b) anything else: drop the whole subtree
                else -> {
                    val deletionResult = internal_deleteFile(child, recursive = true)
                    if (deletionResult.isNotSuccessful) {
                        System.err.println("Failed to delete: ${child.pathString}")
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
        } catch (e: AccessDeniedException) {
            Result.error("Missing permission to move file.")
        } catch (e: Exception) {
            Result.error("Failed to move file.")
        }
    }

    fun deleteFile(target: FilePath, recursive: Boolean): Result<Unit> {
        val targetIsProtectedFolder = target.path.startsWith(Props.dataFolder)
        val containsProtectedFolder = Props.dataFolderPath.startsWith(target.path)
        val isFolderProtected = State.App.allowWriteDataFolder == false

        val affectsProtectedFolder = (targetIsProtectedFolder || containsProtectedFolder) && isFolderProtected

        if (!target.path.exists()) return Result.notFound()

        // Check if deleted file affects the Filemat data folder
        // Delete recursively without deleting the protected folder
        if (affectsProtectedFolder) {
            if (targetIsProtectedFolder || !recursive) {
                return Result.reject("Cannot delete ${Props.appName} data folder.")
            }

            if (containsProtectedFolder) {
                if (!recursive) {
                    return Result.reject("Folder cannot be deleted because it is not empty.")
                }
                return deleteChildrenManually(target.path, excludedPath = Props.dataFolder)
            }
        }

        return internal_deleteFile(target.path, recursive)
    }

    @OptIn(ExperimentalPathApi::class)
    private fun internal_deleteFile(path: Path, recursive: Boolean): Result<Unit> {
        return try {
            if (recursive) {
                path.deleteRecursively()
            } else {
                path.deleteExisting()
            }
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: DirectoryNotEmptyException) {
            Result.reject("Folder cannot be deleted because it is not empty.")
        } catch (e: FileSystemException) {
            // This catches AccessDeniedException and generic recursive failures
            val isPermissionError = e.suppressed.any { it is AccessDeniedException } || e is AccessDeniedException

            if (isPermissionError) {
                Result.error("Missing permission to delete file.")
            } else {
                Result.error("Failed to delete file due to filesystem error.")
            }
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.DELETE_FILE,
                description = "Error when deleting a file.",
                message = e.stackTraceToString(),
            )
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