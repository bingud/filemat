package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.InternalFolder
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.FileErrors
import org.filemat.server.common.util.FileFailures
import org.filemat.server.config.Props
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.*
import kotlin.io.path.*


interface FilesystemDeletionOperations {
    /**
     * Recursively deletes files
     *
     * Verifies permissions
     *
     * Deletes entities from DB
     *
     * [internalFolder]: Filemat deletes one of its own working files in this folder, such as a finished upload's temp folder.
     * The target must be inside it (`State.isInInternalFolder`) and is not checked against the user, even in the data folder.
     */
    fun deleteFile(
        target: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        internalFolder: InternalFolder? = null,
    ): Result<Unit>
}

@Service
class FilesystemDeletionService(
    private val logService: LogService,
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val entityService: EntityService,
    private val thumbnailService: ThumbnailService,
    private val state: State,
) : FilesystemDeletionOperations {

    override fun deleteFile(
        target: FilePath,
        user: Principal,
        ignorePermissions: Boolean?,
        internalFolder: InternalFolder?,
    ): Result<Unit> = fileLockService.tryWithLock(target.path, LockType.WRITE) {
        val isWriteDataFolderProtected = state.app.allowWriteDataFolder == false
        if (!target.path.exists()) return@tryWithLock Result.notFound()

        val isFilematFile = internalFolder != null
        if (internalFolder != null && !state.isInInternalFolder(internalFolder, target.path)) {
            return@tryWithLock Result.reject("Only ${Props.appName}'s own working files can be deleted this way.")
        }

        if (isWriteDataFolderProtected) {
            // Filemat's own working files may be deleted from the data folder
            if (state.app.isInDataFolder(target.path) && !isFilematFile) {
                return@tryWithLock Result.reject("Cannot delete ${Props.appName} data folder.")
            }
            // A folder that holds the data folder
            if (state.app.dataFolderPath.startsWith(target.path)) {
                return@tryWithLock Result.reject("Folder cannot be deleted because it is not empty.")
            }
        }

        val isFolder = Files.isDirectory(target.path, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(target.path)
        val failures = FileFailures()
        val failedCount = FileErrors.withRetryBudget {
            deleteRecursiveSafe(
                currentPath = target.path,
                user = user,
                protectedPath = if (isWriteDataFolderProtected) state.app.dataFolderPath else null,
                ignorePermissions = ignorePermissions,
                isFilematFile = isFilematFile,
                failures = failures,
            )
        }

        return@tryWithLock if (failedCount == 0) {
            Result.ok()
        } else {
            failures.toResult("$failedCount items could not be deleted.", isSingleFile = !isFolder)
        }
    }.onFailure { Result.reject("This file is currently being modified.") }

    private fun deleteRecursiveSafe(
        currentPath: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean? = null,
        isFilematFile: Boolean = false,
        failures: FileFailures,
    ): Int {
        var failedCount = 0
        val filePath = FilePath.ofAlreadyNormalized(currentPath)

        // Explicit protection check
        if (protectedPath != null && currentPath == protectedPath) return 1

        // Junctions are deleted as links, never recursed into
        val isDirectory = Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(currentPath)

        if (isDirectory) {
            try {
                Files.newDirectoryStream(currentPath).use { stream ->
                    for (child in stream) {
                        failedCount += deleteRecursiveSafe(
                            currentPath = child,
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions,
                            isFilematFile = isFilematFile,
                            failures = failures,
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }

            if (failedCount > 0) return failedCount + 1
        }

        // Filemat's own working files are not the user's. Links are never followed, so everything below the target is inside its folder too.
        if (!isFilematFile) {
            fileService.isAllowedToDeleteFile(
                user = user,
                canonicalPath = filePath,
                ignorePermissions = ignorePermissions
            ).let {
                if (it.isNotSuccessful) return failedCount + 1
            }
        }

        // Delete file
        // Delete folder if empty
        val deleteResult = internal_deleteFile(currentPath, recursive = false, failures = failures)
        if (deleteResult.isSuccessful) {
            thumbnailService.deleteCacheForPath(filePath)

            val entity = entityService.getByPath(filePath.pathKey, UserAction.DELETE_FILE).let {
                if (it.hasError) return failedCount
                it.valueOrNull
            }

            if (entity != null) {
                entityService.delete(entity, UserAction.DELETE_FILE)
            }
        } else {
            failedCount++
        }

        return failedCount
    }

    @OptIn(ExperimentalPathApi::class)
    private fun internal_deleteFile(
        path: Path,
        recursive: Boolean = false,
        failures: FileFailures,
    ): Result<Unit> {
        return try {
            FileErrors.retryWhenInUse {
                if (recursive) {
                    path.deleteRecursively()
                } else {
                    path.deleteExisting()
                }
            }
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: DirectoryNotEmptyException) {
            Result.reject("Folder cannot be deleted because it is not empty.")
        } catch (e: FileSystemException) {
            // Read-only files stay read-only (Q38)
            FileErrors.failureOf(e, path)?.let {
                failures.add(it)
                return FileErrors.resultOf(it)
            }
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
}