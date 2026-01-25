package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.common.util.plural
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.*
import kotlin.io.path.exists


interface FilesystemMoveOperations {
    /**
     * Moves a file (recursively)
     *
     * Checks all permissions (MOVE or RENAME)
     *
     * Changes the entity path in DB
     */
    fun moveFile(
        source: FilePath,
        destination: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        isRename: Boolean? = null
    ): Result<Unit>
}

@Service
class FilesystemMoveService(
    private val logService: LogService,
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val entityService: EntityService,
    @Lazy private val filesystemService: FilesystemService,
) : FilesystemMoveOperations {

    override fun moveFile(
        source: FilePath,
        destination: FilePath,
        user: Principal,
        ignorePermissions: Boolean?,
        isRename: Boolean?
    ): Result<Unit> = fileLockService.tryWithLock(
        listOf(source.path, destination.path), LockType.WRITE,
        checkChildren = true
    ) {
        val isDataFolderProtected = State.App.allowWriteDataFolder == false

        if (!source.path.exists()) return@tryWithLock Result.notFound()
        if (destination.path.startsWith(source.path)) return@tryWithLock Result.reject("Cannot move folder into itself.")
        if (destination.path.exists(LinkOption.NOFOLLOW_LINKS)) return@tryWithLock Result.reject("A file or folder already exists at the destination.")

        // Cannot move INTO the protected data folder
        val destRelation = getPathRelationship(path = destination.path, target = Props.dataFolderPath)
        if (destRelation.isInsideTarget && isDataFolderProtected) {
            return@tryWithLock Result.reject("Cannot move files into ${Props.appName} data folder.")
        }

        // Cannot move protected data folder itself or files inside it if protected
        if (isDataFolderProtected) {
            val sourceRelation = getPathRelationship(path = source.path, target = Props.dataFolderPath)
            if (sourceRelation.isInsideTarget) {
                return@tryWithLock Result.reject("Cannot move ${Props.appName} data folder contents.")
            }
        }

        val failedCount = moveRecursiveSafe(
            currentSource = source.path,
            currentDest = destination.path,
            user = user,
            protectedPath = if (isDataFolderProtected) Props.dataFolderPath else null,
            ignorePermissions = ignorePermissions,
            isRename = isRename ?: (source.path.parent == destination.path.parent)
        )

        return@tryWithLock if (failedCount == 0) {
            Result.ok()
        } else {
            if (isRename == true) {
                if (failedCount == 1) {
                    Result.error("Failed to rename file.")
                } else {
                    Result.error("Failed to rename $failedCount ${plural("files", failedCount)}.")
                }
            } else {
                Result.error("Failed to move  $failedCount ${plural("file", failedCount)}.")
            }
        }
    }.onFailure { Result.reject("Could not move. The file is currently being modified.") }

    private fun moveRecursiveSafe(
        currentSource: Path,
        currentDest: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean?,
        isRename: Boolean = false
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check: Do not move the protected path
        if (protectedPath != null && currentSource == protectedPath) return 1

        val isDirectory = Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS)

        if (isDirectory) {
            try {
                // Ensure destination directory exists
                if (Files.notExists(currentDest)) {
                    Files.createDirectories(currentDest)
                }

                Files.newDirectoryStream(currentSource).use { stream ->
                    for (child in stream) {
                        failedCount += moveRecursiveSafe(
                            currentSource = child,
                            currentDest = currentDest.resolve(child.fileName),
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions,
                            isRename = isRename
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }
        }

        // Check permissions (MOVE or RENAME)
        if (isRename) {
            fileService.isAllowedToRenameFile(user = user, canonicalPath = sourceFilePath, ignorePermissions = ignorePermissions)
                .let { if (it.isNotSuccessful) return failedCount + 1 }
        } else {
            fileService.isAllowedToMoveFile(user = user, canonicalPath = sourceFilePath, ignorePermissions = ignorePermissions)
                .let { if (it.isNotSuccessful) return failedCount + 1 }
        }

        // Move file OR Delete empty source folder
        // If we had failures in children, we cannot delete this folder, so we count this folder as failed
        if (isDirectory && failedCount > 0) {
            val sourcePath = FilePath.ofAlreadyNormalized(currentSource)
            val destinationPath = FilePath.ofAlreadyNormalized(currentDest)

            // Duplicate DB entity for the destination folder that was copied partially
            entityService.duplicateEntity(
                canonicalPath = sourcePath,
                canonicalDestinationPath = destinationPath,
                UserAction.DUPLICATE_ENTITY
            ).let {
                if (it.isNotSuccessful) {
                    filesystemService.deleteFile(
                        target = destinationPath,
                        user = user,
                        ignorePermissions = true
                    )
                }
            }

            return failedCount // Don't increment, just return existing failures
        }

        val moveResult = internal_moveOrCleanup(
            source = currentSource,
            dest = currentDest,
            isDirectory = isDirectory
        )

        // Move the entity path
        if (moveResult.isSuccessful) {
            val sourcePath = FilePath.ofAlreadyNormalized(currentSource)
            val destinationPath = FilePath.ofAlreadyNormalized(currentDest)

            entityService.move(path = sourcePath, newPath = destinationPath, userAction = UserAction.MOVE_FILE).let {
                if (it.isNotSuccessful) {
                    internal_moveOrCleanup(
                        source = currentDest,
                        dest = currentSource,
                        isDirectory = isDirectory
                    )
                    return failedCount + 1
                }
            }
        } else {
            failedCount++
        }

        if (moveResult.isNotSuccessful) failedCount++

        return failedCount
    }

    private fun internal_moveOrCleanup(
        source: Path,
        dest: Path,
        isDirectory: Boolean,
    ): Result<Unit> {
        return try {
            if (isDirectory) {
                // For directories, content is already moved. We just remove the empty shell.
                Files.delete(source)
            } else {
                try {
                    Files.move(source, dest, LinkOption.NOFOLLOW_LINKS)
                } catch (fallbackEx: Exception) {
                    try {
                        Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    } catch (e: IOException) {
                        // If copying attributes fails, retry without them
                        Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS)
                    }
                    Files.delete(source)
                }
            }
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: DirectoryNotEmptyException) {
            // This happens if recursion skipped a protected file.
            // The folder is legitimately not empty and should stay.
            Result.reject("Folder not empty.")
        } catch (e: FileSystemException) {
            val isPermissionError = e.suppressed.any { it is AccessDeniedException } || e is AccessDeniedException
            if (isPermissionError) {
                Result.error("Missing permission to move file.")
            } else {
                Result.error("Failed to move file due to filesystem error.")
            }
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.MOVE_FILE,
                description = "Error when moving a file.",
                message = e.stackTraceToString(),
            )
            Result.error("Failed to move file.")
        }
    }
}