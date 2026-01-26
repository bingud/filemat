package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
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
     */
    fun deleteFile(
        target: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null
    ): Result<Unit>
}

@Service
class FilesystemDeletionService(
    private val logService: LogService,
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val entityService: EntityService,
) : FilesystemDeletionOperations {

    override fun deleteFile(
        target: FilePath,
        user: Principal,
        ignorePermissions: Boolean?
    ): Result<Unit> = fileLockService.tryWithLock(target.path, LockType.WRITE) {
        val isDataFolderProtected = State.App.allowWriteDataFolder == false
        if (!target.path.exists()) return@tryWithLock Result.notFound()

        if (isDataFolderProtected) {
            val relation = getPathRelationship(path = target.path, target = Props.dataFolderPath)
            if (relation.isInsideTarget) {
                return@tryWithLock Result.reject("Cannot delete ${Props.appName} data folder.")
            }
            if (relation.containsTarget) {
                return@tryWithLock Result.reject("Folder cannot be deleted because it is not empty.")
            }
        }

        val failedCount = deleteRecursiveSafe(
            currentPath = target.path,
            user = user,
            protectedPath = if (isDataFolderProtected) Props.dataFolderPath else null,
            ignorePermissions = ignorePermissions
        )

        return@tryWithLock if (failedCount == 0) Result.ok() else Result.error("$failedCount items could not be deleted.")
    }.onFailure { Result.reject("This file is currently being modified.") }

    private fun deleteRecursiveSafe(
        currentPath: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean? = null
    ): Int {
        var failedCount = 0
        val filePath = FilePath.ofAlreadyNormalized(currentPath)

        // Explicit protection check
        if (protectedPath != null && currentPath == protectedPath) return 1

        val isDirectory = Files.isDirectory(currentPath, LinkOption.NOFOLLOW_LINKS)

        if (isDirectory) {
            try {
                Files.newDirectoryStream(currentPath).use { stream ->
                    for (child in stream) {
                        failedCount += deleteRecursiveSafe(
                            currentPath = child,
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }

            if (failedCount > 0) return failedCount + 1
        }

        fileService.isAllowedToDeleteFile(
            user = user,
            canonicalPath = filePath,
            ignorePermissions = ignorePermissions
        ).let {
            if (it.isNotSuccessful) return failedCount + 1
        }

        // Delete file
        // Delete folder if empty
        val deleteResult = internal_deleteFile(currentPath, recursive = false)
        if (deleteResult.isSuccessful) {
            val entity = entityService.getByPath(filePath.pathString, UserAction.DELETE_FILE).let {
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
    ): Result<Unit> {
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