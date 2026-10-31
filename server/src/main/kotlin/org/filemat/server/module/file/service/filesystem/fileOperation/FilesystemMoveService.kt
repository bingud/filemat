package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.InternalFolder
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.FileErrors
import org.filemat.server.common.util.FileFailure
import org.filemat.server.common.util.FileFailures
import org.filemat.server.common.util.createFoldersLike
import org.filemat.server.common.util.isCaseOnlyRename
import org.filemat.server.common.util.isFileStoreMatching
import org.filemat.server.common.util.plural
import org.filemat.server.common.util.resolvePath
import org.filemat.server.config.Props
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
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
     *
     * [internalFolder]: Filemat moves one of its own working files out of this folder, such as a finished upload.
     * The source must be inside it (`State.isInInternalFolder`) and is not checked against the user, even in the data folder.
     * The destination is checked like for any move.
     */
    fun moveFile(
        source: FilePath,
        destination: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        isRename: Boolean? = null,
        internalFolder: InternalFolder? = null,
    ): Result<Unit>
}

@Service
class FilesystemMoveService(
    private val logService: LogService,
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val entityService: EntityService,
    @Lazy private val filesystemService: FilesystemService,
    private val thumbnailService: ThumbnailService,
    private val state: State,
) : FilesystemMoveOperations {

    override fun moveFile(
        source: FilePath,
        destination: FilePath,
        user: Principal,
        ignorePermissions: Boolean?,
        isRename: Boolean?,
        internalFolder: InternalFolder?,
    ): Result<Unit> = fileLockService.tryWithLock(
        listOf(source.path, destination.path), LockType.WRITE,
        checkChildren = true
    ) {
        val isWriteDataFolderProtected = state.app.allowWriteDataFolder == false

        if (!source.path.exists()) return@tryWithLock Result.notFound()
        val isFilematFile = internalFolder != null
        if (internalFolder != null && !state.isInInternalFolder(internalFolder, source.path)) {
            return@tryWithLock Result.reject("Only ${Props.appName}'s own working files can be moved this way.")
        }
        // `photo.jpg` to `Photo.jpg` names the same file, so it "exists" and is "inside" the source
        val isCaseOnlyRename = isCaseOnlyRename(source, destination)
        if (!isCaseOnlyRename) {
            if (PathPolicy.isKeyWithin(destination.pathKey, source.pathKey)) return@tryWithLock Result.reject("Cannot move folder into itself.")
            if (destination.path.exists(LinkOption.NOFOLLOW_LINKS)) return@tryWithLock Result.reject("A file or folder already exists at the destination.")
        }

        // Cannot move INTO the protected data folder. Compared on the real path, so a link into it is caught too.
        val realDestination = resolvePath(destination, followSymlinks = true, allowNonExistent = true).valueOrNull ?: destination
        if (state.app.isInDataFolder(realDestination.path) && isWriteDataFolderProtected) {
            return@tryWithLock Result.reject("Cannot move files into ${Props.appName} data folder.")
        }

        // Cannot move protected data folder itself or files inside it if protected, except Filemat's own working files
        if (isWriteDataFolderProtected && !isFilematFile) {
            if (state.app.isInDataFolder(source.path)) {
                return@tryWithLock Result.reject("Cannot move ${Props.appName} data folder contents.")
            }
        }

        if (isCaseOnlyRename) return@tryWithLock renameCaseOnly(source, destination, user, ignorePermissions)

        val isFolder = Files.isDirectory(source.path, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(source.path)
        val failures = FileFailures()
        val failedCount = FileErrors.withRetryBudget {
            moveRecursiveSafe(
                sourceRoot = source.path,
                currentSource = source.path,
                currentDest = destination.path,
                user = user,
                protectedPath = if (isWriteDataFolderProtected) state.app.dataFolderPath else null,
                ignorePermissions = ignorePermissions,
                isRename = isRename ?: (source.path.parent == destination.path.parent),
                isFilematFile = isFilematFile,
                failures = failures,
            )
        }

        return@tryWithLock if (failedCount == 0) {
            Result.ok()
        } else {
            val summary = if (isRename == true) {
                if (failedCount == 1) "Failed to rename file." else "Failed to rename $failedCount ${plural("file", failedCount)}."
            } else {
                "Failed to move $failedCount ${plural("file", failedCount)}."
            }
            failures.toResult(summary, isSingleFile = !isFolder)
        }
    }.onFailure { Result.reject("Could not move. The file is currently being modified.") }

    private fun moveRecursiveSafe(
        sourceRoot: Path,
        currentSource: Path,
        currentDest: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean?,
        isRename: Boolean = false,
        isFilematFile: Boolean = false,
        failures: FileFailures,
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check: Do not move the protected path
        if (protectedPath != null && currentSource == protectedPath) return 1

        // Junctions are moved as links, never recursed into
        val isDirectory = Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(currentSource)

        if (isDirectory) {
            try {
                // Recurse first so nested MOVE grants can still succeed.
                // Do not create dest yet: an unused folder should not appear at the destination.
                Files.newDirectoryStream(currentSource).use { stream ->
                    for (child in stream) {
                        failedCount += moveRecursiveSafe(
                            sourceRoot = sourceRoot,
                            currentSource = child,
                            currentDest = currentDest.resolve(child.fileName),
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions,
                            isRename = isRename,
                            isFilematFile = isFilematFile,
                            failures = failures,
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }
        }

        // Check permissions (MOVE or RENAME). Filemat's own working files are not the user's.
        // Links are never followed, so everything below a checked working file is inside its folder too.
        val allowed = if (isFilematFile) {
            Result.ok()
        } else if (isRename) {
            fileService.isAllowedToRenameFile(user = user, canonicalPath = sourceFilePath, ignorePermissions = ignorePermissions)
        } else {
            fileService.isAllowedToMoveFile(user = user, canonicalPath = sourceFilePath, ignorePermissions = ignorePermissions)
        }
        if (allowed.isNotSuccessful) {
            if (isDirectory) deleteUnusedDestination(currentDest)
            return failedCount + 1
        }

        // Move file OR Delete empty source folder
        // If we had failures in children, we cannot delete this folder, so we count this folder as failed
        if (isDirectory && failedCount > 0) {
            if (Files.exists(currentDest)) {
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
            }

            return failedCount // Don't increment, just return existing failures
        }

        try {
            // Folders keep their case sensitivity, so names that differ only in case still fit
            if (isDirectory) {
                if (Files.notExists(currentDest)) {
                    createFoldersLike(currentDest, currentSource, sourceRoot)
                }
            } else {
                currentDest.parent?.let { parent ->
                    if (Files.notExists(parent)) {
                        createFoldersLike(parent, currentSource.parent.takeIf { currentSource != sourceRoot }, sourceRoot)
                    }
                }
            }
        } catch (e: Exception) {
            return failedCount + 1
        }

        val moveResult = internal_moveOrCleanup(
            source = currentSource,
            dest = currentDest,
            isDirectory = isDirectory,
            failures = failures,
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
                        isDirectory = isDirectory,
                        failures = FileFailures(),
                    )
                    return failedCount + 1
                }
            }

            thumbnailService.moveCacheForPath(oldPath = sourcePath, newPath = destinationPath)
        } else {
            failedCount++
        }

        return failedCount
    }

    /**
     * Removes a dest folder that was only created as scaffolding, when this node is not allowed
     * to move and nothing actually landed in it.
     */
    private fun deleteUnusedDestination(dest: Path) {
        try {
            if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return
            val empty = Files.newDirectoryStream(dest).use { stream ->
                !stream.iterator().hasNext()
            }
            if (empty) Files.delete(dest)
        } catch (_: Exception) {
            // Best-effort cleanup; leftover dest is preferable to failing the whole move.
        }
    }

    /**
     * Renames a file or folder to a name that differs only in case.
     * `Files.move` does nothing when source and target are the same file, but an atomic move renames it.
     */
    private fun renameCaseOnly(source: FilePath, destination: FilePath, user: Principal, ignorePermissions: Boolean?): Result<Unit> {
        if (!canRenameAll(source.path, user, ignorePermissions)) return Result.error("Failed to rename file.")

        try {
            FileErrors.retryWhenInUse { Files.move(source.path, destination.path, StandardCopyOption.ATOMIC_MOVE) }
        } catch (e: FileSystemException) {
            FileErrors.failureOf(e, source.path)?.let { return FileErrors.resultOf(it) }
            return Result.error("Failed to rename file.")
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.MOVE_FILE,
                description = "Error when renaming a file.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to rename file.")
        }

        entityService.move(path = source, newPath = destination, userAction = UserAction.MOVE_FILE).let {
            if (it.isNotSuccessful) {
                runCatching { Files.move(destination.path, source.path, StandardCopyOption.ATOMIC_MOVE) }
                return Result.error("Failed to rename file.")
            }
        }
        thumbnailService.moveCacheForPath(oldPath = source, newPath = destination)
        return Result.ok()
    }

    /**
     * Returns whether the user may rename [path] and everything inside it, like a recursive rename requires
     */
    private fun canRenameAll(path: Path, user: Principal, ignorePermissions: Boolean?): Boolean {
        val filePath = FilePath.ofAlreadyNormalized(path)
        if (fileService.isAllowedToRenameFile(user = user, canonicalPath = filePath, ignorePermissions = ignorePermissions).isNotSuccessful) return false
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || LinkInspector.isDirectoryLink(path)) return true

        return runCatching {
            Files.newDirectoryStream(path).use { stream -> stream.all { canRenameAll(it, user, ignorePermissions) } }
        }.getOrDefault(false)
    }

    private fun internal_moveOrCleanup(
        source: Path,
        dest: Path,
        isDirectory: Boolean,
        failures: FileFailures,
    ): Result<Unit> {
        return try {
            if (isDirectory) {
                // For directories, content is already moved. We just remove the empty shell.
                FileErrors.retryWhenInUse { Files.delete(source) }
            } else {
                val isJunction = LinkInspector.isDirectoryLink(source)
                val isReadOnly = FileErrors.isReadOnlyFile(source)
                if ((isJunction || isReadOnly) && !isSameVolume(source, dest)) {
                    // Moving to another drive is a copy, then a delete. A junction would become an empty folder.
                    if (isJunction) return Result.reject("Links to folders cannot be moved to another drive.")
                    // The read-only file could not be deleted after the copy (Q38)
                    failures.add(FileFailure.READ_ONLY)
                    return FileErrors.resultOf(FileFailure.READ_ONLY)
                }

                try {
                    FileErrors.retryWhenInUse { Files.move(source, dest, LinkOption.NOFOLLOW_LINKS) }
                } catch (e: Exception) {
                    // Copying would fail the same way
                    if (FileErrors.failureOf(e, source) != null) throw e
                    copyThenDelete(source, dest)
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
            FileErrors.failureOf(e, source)?.let {
                failures.add(it)
                return FileErrors.resultOf(it)
            }
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

    /**
     * Fallback for moves that `Files.move` can't do. Removes the copy when the source can't be deleted, so no duplicate is left.
     */
    private fun copyThenDelete(source: Path, dest: Path) {
        try {
            Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (e: IOException) {
            // If copying attributes fails, retry without them
            Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS)
        }

        try {
            FileErrors.retryWhenInUse { Files.delete(source) }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(dest) }
            throw e
        }
    }

    /**
     * Returns whether the folders of [source] and [dest] are on the same drive. Unknown counts as the same, as before.
     */
    private fun isSameVolume(source: Path, dest: Path): Boolean {
        val sourceFolder = source.parent ?: return true
        val destFolder = dest.parent ?: return true
        return isFileStoreMatching(sourceFolder, destFolder) ?: true
    }
}