package org.filemat.server.module.file.service.file.component

import kotlinx.serialization.Serializable
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.withRealNames
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.savedFile.SavedFileService
import org.springframework.stereotype.Service
import kotlin.io.path.isSymbolicLink

@Service
class FileDeletionService(
    private val fileService: FileService,
    private val filesystemService: FilesystemService,
    private val savedFileService: SavedFileService,
    private val state: State,
) {

    fun deleteFile(user: Principal, rawPath: FilePath): Result<Unit> {
        val rawParent = rawPath.path.parent ?: return Result.reject("Cannot delete root folder.")
        // Windows: a path without a drive would resolve on the server's current drive
        if (!rawPath.path.isAbsolute) return Result.notFound()

        // The folder is resolved first, so a link inside a linked folder is checked where it really is
        val parent = resolvePath(FilePath.ofAlreadyNormalized(rawParent), state.app.followSymlinks).let { parentResult ->
            if (parentResult.isNotSuccessful) return parentResult.cast()
            parentResult.value
        }
        val pathInParent = FilePath.ofAlreadyNormalized(parent.path.resolve(rawPath.path.fileName))

        // Resolve the target path. A link is deleted itself, so it is not resolved, but its real name is checked, not an 8.3 short name.
        val canonicalPath = if (pathInParent.path.isSymbolicLink()) {
            withRealNames(pathInParent)
        } else {
            resolvePath(rawPath, state.app.followSymlinks).let { canonicalResult ->
                if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
                canonicalResult.value
            }
        }

        if (PathPolicy.isRoot(canonicalPath.path)) return Result.reject("Cannot delete root folder.")

        // Check basic access
        fileService.isAllowedToDeleteFile(user = user, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Verify file exists
        val exists = filesystemService.exists(canonicalPath.path, followSymbolicLinks = false)
        if (!exists) return Result.reject("File not found.")


        // Perform deletion
        filesystemService.deleteFile(target = canonicalPath, user = user).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update saved files
        savedFileService.removeSavedFile(path = canonicalPath)

        return Result.ok()
    }

    /**
     * Deletes a list of file paths.
     * @return Deleted paths, and why the others were not deleted
     */
    fun deleteFiles(user: Principal, rawPathList: List<FilePath>): DeleteFilesResult {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<DeleteFailure>()
        rawPathList.forEach { path ->
            val result = deleteFile(user, path)
            if (result.isSuccessful) {
                deleted += path.pathString
            } else {
                failed += DeleteFailure(path = path.pathString, message = result.errorOrNull ?: "File was not found.")
            }
        }
        return DeleteFilesResult(deleted = deleted, failed = failed)
    }
}

@Serializable
data class DeleteFilesResult(val deleted: List<String>, val failed: List<DeleteFailure>)

@Serializable
data class DeleteFailure(val path: String, val message: String)