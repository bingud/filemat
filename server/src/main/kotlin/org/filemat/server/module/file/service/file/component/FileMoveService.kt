package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class FileMoveService(private val fileService: FileService, private val entityService: EntityService, private val savedFileService: SavedFileService, private val filesystemService: FilesystemService) {

    fun moveFile(user: Principal, rawPath: FilePath, rawNewPath: FilePath): Result<Unit> {
        // Resolve the target path
        val rawParent = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        val sourceParent = resolvePath(rawParent)
            .let { (canonicalResult, _) ->
                if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
                canonicalResult.value
            }

        val canonicalPath = FilePath.ofAlreadyNormalized(sourceParent.path.resolve(rawPath.path.fileName))

        // Safety checks
        if (canonicalPath.pathString == "/") return Result.reject("Cannot move root folder.")

        // Get the target parent folder
        val rawNewPathParent = FilePath.ofAlreadyNormalized(rawNewPath.path.parent)

        // Resolve the target path
        val (newPathParentResult, newPathHasSymlink) = resolvePath(rawNewPathParent)
        if (newPathParentResult.isNotSuccessful) return newPathParentResult.cast()
        val newPathParent = newPathParentResult.value

        // Get the target path
        val newPath = FilePath.ofAlreadyNormalized(newPathParent.path.resolve(rawNewPath.path.fileName))

        // Check if file is being moved into itself
        if (canonicalPath == newPath || newPath.startsWith(canonicalPath)) return Result.reject("File cannot be moved into itself.")

        // Check source permissions
        if (canonicalPath.path.parent == newPathParent.path) {
            // Check if user is permitted to move the file
            fileService.isAllowedToRenameFile(user = user, canonicalPath = canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        } else {
            // Check if user is permitted to move the file
            fileService.isAllowedToMoveFile(user = user, canonicalPath = canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        // Check target parent folder `WRITE` permission
        fileService.isAllowedToEditFile(user = user, canonicalPath = newPathParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Move the file in filesystem
        filesystemService.moveFile(user = user, source = canonicalPath, destination = newPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update the entity
        entityService.move(path = canonicalPath, newPath = newPath, userAction = UserAction.MOVE_FILE).let {
            if (it.isNotSuccessful) {
                // Revert file move
                filesystemService.moveFile(user = user, source = newPath, destination = canonicalPath)
                return it.cast()
            }
        }

        // Update saved files
        savedFileService.changePath(path = canonicalPath, newPath = newPath)

        return Result.ok()
    }

    /**
     * Moves multiple files, returns inputted paths of successfully moved files
     */
    fun moveMultipleFiles(user: Principal, rawPaths: List<FilePath>, rawNewParentPath: FilePath): Result<List<FilePath>> {
        val (canonicalResult, parentPathHasSymlink) = resolvePath(rawNewParentPath)
        if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
        val newParentPath = canonicalResult.value

        val movedFiles: MutableList<FilePath> = mutableListOf()
        rawPaths.forEach { rawPath ->
            val newPath = FilePath.ofAlreadyNormalized(newParentPath.path.resolve(rawPath.path.fileName))
            if (newPath == newParentPath) return@forEach

            val op = moveFile(user, rawPath, newPath)
            if (op.isSuccessful) {
                movedFiles.add(rawPath)
            }
        }

        return Result.ok(movedFiles)
    }
}