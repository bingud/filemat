package org.filemat.server.module.file.service.filesystem.fileOperation

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
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
) : FilesystemCopyOperations {

    override fun copyFile(
        canonicalSource: FilePath,
        canonicalDestination: FilePath,
        user: Principal,
        ignorePermissions: Boolean?,
        copyResolvedSymlinks: Boolean?,
    ): Result<Unit> = fileLockService.tryWithLock(
        canonicalSource.path to LockType.READ,
        canonicalDestination.path to LockType.WRITE,
        checkChildren = true
    ) {
        val resolveSymlinks = if (copyResolvedSymlinks != null) {
            copyResolvedSymlinks
        } else {
            if (State.App.followSymlinks) {
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

    private fun copyFile(
        canonicalSource: FilePath,
        canonicalDestination: FilePath,
        user: Principal,
        ignorePermissions: Boolean? = null,
        copyResolvedSymlinks: Boolean,
    ): Result<Unit> {
        val isReadDataFolderProtected = State.App.allowReadDataFolder == false
        val isWriteDataFolderProtected = State.App.allowWriteDataFolder == false

        if (!canonicalSource.path.exists()) return Result.notFound()
        if (canonicalDestination.path.startsWith(canonicalSource.path)) return Result.reject("Cannot copy folder into itself.")
        if (canonicalDestination.path.exists(LinkOption.NOFOLLOW_LINKS)) return Result.reject("A file or folder already exists at the destination.")

        // Cannot copy INTO protected data folder (Write protection)
        val destRelation = getPathRelationship(path = canonicalDestination.path, target = Props.dataFolderPath)
        if (destRelation.isInsideTarget && isWriteDataFolderProtected) {
            return Result.reject("Cannot copy files into ${Props.appName} data folder.")
        }

        // Cannot copy FROM protected data folder (Read protection)
        if (isReadDataFolderProtected) {
            val sourceRelation = getPathRelationship(path = canonicalSource.path, target = Props.dataFolderPath)
            if (sourceRelation.isInsideTarget) {
                return Result.reject("Cannot copy ${Props.appName} data folder contents.")
            }
        }

        val failedCount = copyRecursiveSafe(
            currentSource = canonicalSource.path,
            currentDest = canonicalDestination.path,
            user = user,
            protectedPath = if (isReadDataFolderProtected) Props.dataFolderPath else null,
            ignorePermissions = ignorePermissions,
            copyResolvedSymlinks = copyResolvedSymlinks,
        )

        return if (failedCount == 0) Result.ok() else Result.error("$failedCount items could not be copied.")
    }

    private fun copyRecursiveSafe(
        currentSource: Path,
        currentDest: Path,
        user: Principal,
        protectedPath: Path?,
        ignorePermissions: Boolean?,
        copyResolvedSymlinks: Boolean,
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check
        if (protectedPath != null && currentSource == protectedPath) return 1

        val isDirectory = if (copyResolvedSymlinks) {
            Files.isDirectory(currentSource)
        } else {
            Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS)
        }

        val isSymlink = if (copyResolvedSymlinks) Files.isSymbolicLink(currentSource) else null

        val resolvedPath = if (isSymlink == true) {
            resolvePath(sourceFilePath).let { result ->
                if (result.isNotSuccessful) return failedCount + 1
                result.value
            }.also {
                if (currentSource.startsWith(it.path)) return failedCount + 1
                if (it.path.startsWith(currentSource)) return failedCount + 1
            }
        } else null


        if (isDirectory) {
            try {
                // Ensure destination directory exists
                if (Files.notExists(currentDest)) {
                    Files.createDirectories(currentDest)
                }

                Files.newDirectoryStream(currentSource).use { stream ->
                    for (child in stream) {
                        failedCount += copyRecursiveSafe(
                            currentSource = child,
                            currentDest = currentDest.resolve(child.fileName),
                            user = user,
                            protectedPath = protectedPath,
                            ignorePermissions = ignorePermissions,
                            copyResolvedSymlinks = copyResolvedSymlinks
                        )
                    }
                }
            } catch (e: Exception) {
                return failedCount + 1
            }

            // For directories, we just create the shell and recurse.
            // We do not perform a "copy" action on the folder object itself.
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

        // Action: Copy file
        val copyResult = internal_copy(
            source = currentSource,
            dest = currentDest,
            copyResolvedSymlinks = copyResolvedSymlinks
        )

        if (copyResult.isNotSuccessful) failedCount++

        return failedCount
    }

    private fun internal_copy(
        source: Path,
        dest: Path,
        copyResolvedSymlinks: Boolean,
    ): Result<Unit> {
        return try {
            try {
                // Try copying with attributes first
                if (copyResolvedSymlinks) {
                    Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES)
                } else {
                    Files.copy(source, dest, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                }
            } catch (fallbackEx: Exception) {
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