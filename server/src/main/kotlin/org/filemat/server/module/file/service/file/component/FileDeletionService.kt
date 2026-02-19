package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.resolvePath
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
    private val savedFileService: SavedFileService
) {

    fun deleteFile(user: Principal, rawPath: FilePath): Result<Unit> {
        val isSymlink = rawPath.path.isSymbolicLink()

        // Resolve the target path
        val canonicalPath = if (isSymlink) {
            rawPath
        } else {
            resolvePath(rawPath).let { canonicalResult ->
                if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
                canonicalResult.value
            }
        }

        if (canonicalPath.pathString == "/") return Result.reject("Cannot delete root folder.")

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
     * @return List of successfully deleted paths
     */
    fun deleteFiles(user: Principal, rawPathList: List<FilePath>): List<FilePath> {
        val successful = mutableListOf<FilePath>()
        rawPathList.forEach { path ->
            val result = deleteFile(user, path)
            if (result.isSuccessful) successful.add(path)
        }
        return successful
    }
}