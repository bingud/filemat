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
import org.springframework.stereotype.Service

@Service
class FileMoveService(private val fileService: FileService, private val entityService: EntityService, private val savedFileService: SavedFileService, private val filesystemService: FilesystemService) {

    fun moveFile(user: Principal, rawPath: FilePath, rawDestinationPath: FilePath): Result<Unit> {
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
        val rawDestinationParentPath = FilePath.ofAlreadyNormalized(rawDestinationPath.path.parent)

        // Resolve the target path
        val destinationParentPath = resolvePath(rawDestinationParentPath)
            .let { (destinationParentPathResult, containsSymLink) ->
                if (destinationParentPathResult.isNotSuccessful) return destinationParentPathResult.cast()
                destinationParentPathResult.value
            }

        // Get the target path
        val canonicalDestinationPath = FilePath.ofAlreadyNormalized(destinationParentPath.path.resolve(rawDestinationPath.path.fileName))

        // Check if file is being moved into itself
        if (canonicalPath == canonicalDestinationPath || canonicalDestinationPath.startsWith(canonicalPath)) return Result.reject("File cannot be moved into itself.")

        val isRename = destinationParentPath.path == sourceParent.path

        if (!isRename) {
            // Check target parent folder `WRITE` permission
            fileService.isAllowedToEditFile(user = user, canonicalPath = destinationParentPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        // Move the file in filesystem
        // Checks file permissions
        // Updates the entity paths
        filesystemService.moveFile(user = user, source = canonicalPath, destination = canonicalDestinationPath, isRename = isRename).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update saved files
        savedFileService.changePath(path = canonicalPath, newPath = canonicalDestinationPath)

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