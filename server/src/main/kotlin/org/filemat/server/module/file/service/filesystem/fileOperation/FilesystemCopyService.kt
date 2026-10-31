package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.config.Props
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.*
import kotlin.io.path.exists


interface FilesystemCopyOperations {
    fun copyFile(
        canonicalSource: FilePath,
        canonicalDestination: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        copyResolvedSymlinks: Boolean? = null
    ): Result<Unit>
}

@Service
class FilesystemCopyService(
    private val logService: LogService,
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val state: State,
) : FilesystemCopyOperations {

    override fun copyFile(
        canonicalSource: FilePath,
        canonicalDestination: FilePath,
        user: Principal,
        ignorePermissions: Boolean?,
        copyResolvedSymlinks: Boolean?,
    ): Result<Unit> {
        // A name that differs only in case is the same file in a case-insensitive folder.
        // Checked before locking, because both names share one lock.
        if (isCaseOnlyRename(canonicalSource, canonicalDestination)) return Result.reject("A file or folder already exists at the destination.")

        return fileLockService.tryWithLock(
            canonicalSource.path to LockType.READ,
            canonicalDestination.path to LockType.WRITE,
            checkChildren = true
        ) {
            val resolveSymlinks = if (copyResolvedSymlinks != null) {
                copyResolvedSymlinks
            } else {
                if (state.app.followSymlinks) {
                    val isMatching = isFileStoreMatching(canonicalSource.path, canonicalDestination.path.parent ?: canonicalDestination.path)
                        ?: return@tryWithLock Result.error("Failed to check if copied file path is on the same filesystem.")

                    isMatching == false
                } else {
                    false
                }
            }

            copyFile(
                canonicalSource = canonicalSource,
                canonicalDestination = canonicalDestination,
                user = user,
                ignorePermissions = ignorePermissions,
                copyResolvedSymlinks = resolveSymlinks
            )
        }.onFailure { Result.reject("Could not copy. The file is currently being modified.") }
    }

    private fun copyFile(
        canonicalSource: FilePath,
        canonicalDestination: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        copyResolvedSymlinks: Boolean,
    ): Result<Unit> {
        val isReadDataFolderProtected = state.app.allowReadDataFolder == false
        val isWriteDataFolderProtected = state.app.allowWriteDataFolder == false

        if (!canonicalSource.path.exists()) return Result.notFound()
        if (PathPolicy.isKeyWithin(canonicalDestination.pathKey, canonicalSource.pathKey)) return Result.reject("Cannot copy folder into itself.")
        if (canonicalDestination.path.exists(LinkOption.NOFOLLOW_LINKS)) return Result.reject("A file or folder already exists at the destination.")

        // Cannot copy INTO protected data folder (Write protection)
        if (state.app.isInDataFolder(canonicalDestination.path) && isWriteDataFolderProtected) {
            return Result.reject("Cannot copy files into ${Props.appName} data folder.")
        }

        // Cannot copy FROM protected data folder (Read protection)
        if (isReadDataFolderProtected) {
            if (state.app.isInDataFolder(canonicalSource.path)) {
                return Result.reject("Cannot copy ${Props.appName} data folder contents.")
            }
        }

        val isFolder = Files.isDirectory(canonicalSource.path, LinkOption.NOFOLLOW_LINKS) && !LinkInspector.isDirectoryLink(canonicalSource.path)
        val failures = FileFailures()
        val failedCount = FileErrors.withRetryBudget {
            copyRecursiveSafe(
                sourceRoot = canonicalSource.path,
                currentSource = canonicalSource.path,
                currentDest = canonicalDestination.path,
                user = user,
                protectedPath = if (isReadDataFolderProtected) state.app.dataFolderPath else null,
                ignorePermissions = ignorePermissions,
                copyResolvedSymlinks = copyResolvedSymlinks,
                failures = failures,
            )
        }

        return if (failedCount == 0) Result.ok() else failures.toResult("$failedCount items could not be copied.", isSingleFile = !isFolder)
    }

    private fun copyRecursiveSafe(
        sourceRoot: Path,
        currentSource: Path,
        currentDest: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean?,
        copyResolvedSymlinks: Boolean,
        failures: FileFailures,
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check
        if (protectedPath != null && currentSource == protectedPath) return 1

        // Junctions and mount points are skipped: recursing would copy the link target's contents,
        // and Files.copy would make an empty real folder in their place. Symbolic links are copied as links below.
        if (LinkInspector.isDirectoryLink(currentSource)) {
            failures.add(FileFailure.FOLDER_LINK)
            return 1
        }

        val isDirectory = if (copyResolvedSymlinks) {
            Files.isDirectory(currentSource)
        } else {
            Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS)
        }

        val isSymlink = if (copyResolvedSymlinks) Files.isSymbolicLink(currentSource) else null

        val resolvedPath = if (isSymlink == true) {
            resolvePath(sourceFilePath, state.app.followSymlinks).let { result ->
                if (result.isNotSuccessful) return failedCount + 1
                result.value
            }.also {
                if (currentSource.startsWith(it.path)) return failedCount + 1
                if (it.path.startsWith(currentSource)) return failedCount + 1
            }
        } else null


        if (isDirectory) {
            try {
                // Recurse first so nested READ grants can still succeed.
                // Do not create dest yet: an unused folder should not appear at the destination.
                Files.newDirectoryStream(currentSource).use { stream ->
                    for (child in stream) {
                        failedCount += copyRecursiveSafe(
                            sourceRoot = sourceRoot,
                            currentSource = child,
                            currentDest = currentDest.resolve(child.fileName),
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions,
                            copyResolvedSymlinks = copyResolvedSymlinks,
                            failures = failures,
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }

            if (failedCount > 0) {
                deleteUnusedDestination(currentDest)
                return failedCount
            }

            // Empty folder, or all children succeeded and already created dest.
            try {
                if (Files.notExists(currentDest)) {
                    createFoldersLike(currentDest, currentSource, sourceRoot)
                }
            } catch (e: Exception) {
                return failedCount + 1
            }
            return failedCount
        }

        if (isSymlink == true) {
            if (resolvedPath == null) return failedCount + 1

            fileService.isAllowedToAccessFile(
                user = user,
                canonicalPath = resolvedPath,
                ignorePermissions = ignorePermissions
            ).let {
                if (it.isNotSuccessful) return failedCount + 1
            }
        } else {
            // Check download/read permission on source file
            fileService.isAllowedToAccessFile(
                user = user,
                canonicalPath = sourceFilePath,
                ignorePermissions = ignorePermissions
            ).let {
                if (it.isNotSuccessful) return failedCount + 1
            }
        }

        try {
            // Folders keep their case sensitivity, so names that differ only in case still fit
            currentDest.parent?.let { parent ->
                if (Files.notExists(parent)) {
                    createFoldersLike(parent, currentSource.parent.takeIf { currentSource != sourceRoot }, sourceRoot)
                }
            }
        } catch (e: Exception) {
            return failedCount + 1
        }

        val copyResult = internal_copy(
            source = currentSource,
            dest = currentDest,
            copyResolvedSymlinks = copyResolvedSymlinks,
            failures = failures,
        )

        if (copyResult.isNotSuccessful) failedCount++

        return failedCount
    }

    /**
     * Removes a dest folder that was only created as scaffolding, when nothing actually landed in it.
     */
    private fun deleteUnusedDestination(dest: Path) {
        try {
            if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) return
            val empty = Files.newDirectoryStream(dest).use { stream ->
                !stream.iterator().hasNext()
            }
            if (empty) Files.delete(dest)
        } catch (_: Exception) {
            // Best-effort cleanup; leftover dest is preferable to failing the whole copy.
        }
    }

    private fun internal_copy(
        source: Path,
        dest: Path,
        copyResolvedSymlinks: Boolean,
        failures: FileFailures,
    ): Result<Unit> {
        return try {
            try {
                // Try copying with attributes first. On Windows this keeps the read-only attribute (§6.2).
                FileErrors.retryWhenInUse {
                    if (copyResolvedSymlinks) {
                        Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES)
                    } else {
                        Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            } catch (fallbackEx: Exception) {
                // A file in use fails the same way without attributes
                if (FileErrors.isInUse(fallbackEx)) throw fallbackEx
                // Fallback: Copy without attributes
                if (copyResolvedSymlinks) {
                    Files.copy(source, dest)
                } else {
                    Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS)
                }
            }
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: FileAlreadyExistsException) {
            Result.reject("File already exists.")
        } catch (e: FileSystemException) {
            if (FileErrors.isInUse(e)) {
                failures.add(FileFailure.IN_USE)
                return FileErrors.resultOf(FileFailure.IN_USE)
            }
            val isPermissionError = e.suppressed.any { it is AccessDeniedException } || e is AccessDeniedException
            if (isPermissionError) {
                Result.error("Missing permission to copy file.")
            } else {
                Result.error("Failed to copy file due to filesystem error.")
            }
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.COPY_FILE,
                description = "Error when copying a file.",
                message = e.stackTraceToString(),
            )
            Result.error("Failed to copy file.")
        }
    }
}