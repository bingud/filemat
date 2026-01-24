package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.isFileStoreMatching
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.pathString

@Service
class FileCopyService(private val fileService: FileService, private val filesystemService: FilesystemService, private val entityService: EntityService) {

    fun copyFile(user: Principal, rawPath: FilePath, rawDestinationPath: FilePath): Result<FullFileMetadata> {
        val isSymlink = rawPath.path.isSymbolicLink()

        // Resolve the copied path
        val canonicalPath: FilePath = resolvePath(rawPath)
            .let { (result: Result<FilePath>, pathHasSymlink: Boolean) ->
                if (result.notFound) return Result.reject("Copied file was not found.")
                if (result.isNotSuccessful) return result.cast()
                result.value
            }

        if (canonicalPath.pathString == "/") return Result.reject("Cannot copy root folder.")

        val rawParentDestinationPath = FilePath.of(rawDestinationPath.path.parent.pathString)

        // Resolve parent of destination path
        val canonicalParentDestinationPath: FilePath = resolvePath(rawParentDestinationPath)
            .let { (result: Result<FilePath>, pathHasSymlink: Boolean) ->
                if (result.notFound) return Result.reject("Destination folder was not found.")
                if (result.isNotSuccessful) return result.cast()
                result.value
            }

        val isFileStoreMatching = if (State.App.followSymlinks) {
            isFileStoreMatching(canonicalPath.path, canonicalParentDestinationPath.path)
                ?: return Result.error("Failed to check if copied file path is on the same filesystem.")
        } else null

        val copyResolvedSymlinks = State.App.followSymlinks && isSymlink && isFileStoreMatching == false

        val actualCanonicalPath = if (copyResolvedSymlinks) {
            canonicalPath
        } else {
            rawPath
        }

        // Check if user is permitted to copy the file
        fileService.isAllowedToAccessFile(user = user, canonicalPath = actualCanonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check if user is permitted to write to destination folder
        fileService.isAllowedToEditFile(user = user, canonicalPath = canonicalParentDestinationPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Get destination path
        val canonicalDestinationPath = FilePath.ofAlreadyNormalized(
            canonicalParentDestinationPath.path.resolve(rawDestinationPath.path.fileName)
        )

        // Move the file
        filesystemService.copyFile(
            user = user,
            canonicalSource = actualCanonicalPath,
            canonicalDestination = canonicalDestinationPath,
            copyResolvedSymlinks = copyResolvedSymlinks
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Create entity
        entityService.create(
            canonicalPath = canonicalDestinationPath,
            ownerId = user.userId,
            userAction = UserAction.COPY_FILE,
            followSymLinks = false
        )

        val fileMeta = fileService.getFullMetadata(
            user = user,
            rawPath = rawDestinationPath,
            canonicalPath = canonicalDestinationPath,
        ).let {
            if (it.isNotSuccessful) return Result.error("File was copied. Server failed to provide file metadata.")
            it.value
        }

        return Result.ok(fileMeta)
    }

}