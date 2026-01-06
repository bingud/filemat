package org.filemat.server.module.file.service.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.common.util.walkFilesWithExcludedFile
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.*


@Service
class FileDeletionService(
    private val logService: LogService,
) {

    fun deleteFile(target: FilePath, recursive: Boolean): Result<Unit> {
        val relation = getPathRelationship(path = target.path, target = Props.dataFolderPath)
        val isFolderProtected = State.App.allowWriteDataFolder == false

        val affectsProtectedFolder = (relation.containsTarget || relation.isInsideTarget) && isFolderProtected

        if (!target.path.exists()) return Result.notFound()

        // Check if deleted file affects the Filemat data folder
        // Delete recursively without deleting the protected folder
        if (affectsProtectedFolder) {
            if (relation.isInsideTarget || !recursive) {
                return Result.reject("Cannot delete ${Props.appName} data folder.")
            }

            if (relation.containsTarget) {
                if (!recursive) {
                    return Result.reject("Folder cannot be deleted because it is not empty.")
                }
                return deleteChildrenManually(target.path, excludedPath = Props.dataFolderPath).cast()
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

    private fun deleteChildrenManually(
        path: Path,
        excludedPath: Path,
        recursive: Boolean = false
    ): Result<Int> {
        return walkFilesWithExcludedFile(
            path = path,
            excludedPath = excludedPath,
            recursive = recursive,
            failedResult = { count -> Result.error("$count files could not be deleted.") },
        ) { child ->
            internal_deleteFile(path = child, recursive = true).let {
                if (it.isNotSuccessful) return@walkFilesWithExcludedFile false
                true
            }
        }
    }

    /*
        private fun deleteChildrenManuallyOld(
            path: Path,
            excludedPath: Path,
            recursive: Boolean = false
        ): Result<Int> {
            val children = runCatching {
                path.listDirectoryEntries()
            }.getOrElse { return Result.error("Failed to list folder entries.") }

            var failedCount = 0

            for (child in children) {
                when {
                    child == excludedPath -> {
                        continue
                    }

                    excludedPath.startsWith(child) -> {
                        val sub = deleteChildrenManually(child, excludedPath, true)
                        failedCount += sub.value
                    }

                    else -> {
                        val deletionResult = internal_deleteFile(child, recursive = true)
                        if (deletionResult.isNotSuccessful) {
                            failedCount++
                        }
                    }
                }
            }

            if (!recursive && failedCount > 0) {
                val letter = if (failedCount > 1) "s" else ""
                return Result.error("$failedCount file$letter could not be deleted.")
            }

            return Result.ok(failedCount)
        }
    */
}