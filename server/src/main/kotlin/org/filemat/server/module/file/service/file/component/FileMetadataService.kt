package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class FileMetadataService(
    private val fileService: FileService,
    private val filesystemService: FilesystemService,
    private val savedFileService: SavedFileService,
    private val fileLockService: FileLockService,
    private val entityService: EntityService
) {

    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath, foldersOnly: Boolean = false): Result<Pair<FullFileMetadata, List<FullFileMetadata>?>> {
        val pathResult = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        val lock = fileLockService.getLock(canonicalPath.path, LockType.READ)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        return try {
            val metadata: FullFileMetadata = getFullMetadata(user, rawPath = rawPath, canonicalPath = canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }
            val type = metadata.fileType

            if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymlinks)) {
                val entries = fileService.getFolderEntries(
                    user = user,
                    canonicalPath = canonicalPath,
                    foldersOnly = foldersOnly
                ).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }

                Result.ok(metadata to entries)
            } else if (type == FileType.FILE || type == FileType.FILE_LINK || type == FileType.FOLDER_LINK) {
                Result.ok(metadata to null)
            } else {
                Result.error("Requested path is not a file or folder.")
            }
        } finally {
            lock.unlock()
        }
    }


    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getSharedFileOrFolderEntries(rawPath: FilePath, foldersOnly: Boolean = false, shareToken: String): Result<Pair<FileMetadata, List<FileMetadata>?>> {
        val entity = entityService.getByShareToken(shareToken = shareToken, UserAction.GET_SHARED_FILE)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        if (entity.path == null) return Result.notFound()
        val rawSharePath = FilePath.of(entity.path)

        // Resolve entity path
        val canonicalSharePathResult = resolvePath(rawSharePath)
        if (canonicalSharePathResult.isNotSuccessful) return canonicalSharePathResult.cast()
        val canonicalSharePath = canonicalSharePathResult.value

        // Get path of requested file
        val canonicalPath = FilePath.ofAlreadyNormalized(
            canonicalSharePath.path.resolve(rawPath.path.toString().removePrefix("/"))
        )

        val lock = fileLockService.getLock(canonicalPath.path, LockType.READ)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        try {
            // Get file metadata, change its path to be relative
            val rawMetadata: FileMetadata = filesystemService.getMetadata(canonicalPath) ?: return Result.notFound()

            val metadata = rawMetadata.copy(path = rawPath.pathString)
            val type = metadata.fileType

            if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymlinks)) {
                // Get folder entries, change paths back to relative
                val entries = fileService.getFolderEntries(
                    user = null,
                    canonicalPath = canonicalPath,
                    foldersOnly = foldersOnly,
                    ignorePermissions = true,
                    metaMapper = { meta: FileMetadata, _ -> meta }
                ).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }.map { entry ->
                    val relativePath = canonicalPath.path.relativize(Path.of(entry.path))
                    val newPath = rawPath.path.resolve(relativePath)

                    entry.copy(path = newPath.toString())
                }

                return Result.ok(metadata to entries)
            } else if (type == FileType.FILE || type == FileType.FILE_LINK || type == FileType.FOLDER_LINK) {
                return Result.ok(metadata to null)
            } else {
                return Result.error("Requested path is not a file or folder.")
            }
        } finally {
            lock.unlock()
        }
    }

    fun getMetadata(user: Principal?, rawPath: FilePath, isPathCanonical: Boolean = false): Result<FileMetadata> {
        val canonicalPath = if (isPathCanonical) {
            rawPath
        } else {
            val pathResult = resolvePath(rawPath)
            if (pathResult.isNotSuccessful) return pathResult.cast()
            pathResult.value
        }

        return getMetadata(user, rawPath, canonicalPath)
    }

    /**
     * Returns file metadata. Authenticates user
     */
    fun getMetadata(user: Principal?, rawPath: FilePath, canonicalPath: FilePath): Result<FileMetadata> {
        fileService.isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystemService.getMetadata(rawPath)?.toResult()
            ?: Result.notFound()
    }

    /**
     * Returns file metadata along with applied file permissions. Authenticates user
     */
    fun getFullMetadata(user: Principal?, rawPath: FilePath, canonicalPath: FilePath, ignorePermissions: Boolean = false): Result<FullFileMetadata> {
        return getFullMetadata(
            user = user,
            rawPath = rawPath,
            canonicalPath = canonicalPath,
            ignorePermissions = ignorePermissions,
            metadataMapper = FullFileMetadata::from
        )
    }

    /**
     * Returns file metadata along with applied file permissions. Authenticates user
     *
     * Allows to specify what metadata class to return
     */
    fun <T : AbstractFileMetadata> getFullMetadata(
        user: Principal?,
        rawPath: FilePath,
        canonicalPath: FilePath,
        ignorePermissions: Boolean = false,
        metadataMapper: (meta: FileMetadata, isSaved: Boolean?, permissions: Set<FilePermission>) -> T
    ): Result<T> {
        if (!ignorePermissions) {
            fileService.isAllowedToAccessFile(user, canonicalPath).let { it: Result<Unit> ->
                if (it.isNotSuccessful) return it.cast()
            }
        }

        val meta = filesystemService.getMetadata(rawPath)
            ?: return Result.notFound()

        val permissions: Set<FilePermission> = user?.let { fileService.getActualFilePermissions(user, canonicalPath) } ?: setOf(FilePermission.READ)
        val isSaved = if (user != null) savedFileService.isSaved(user.userId, meta.path) else null

        val fullMeta = metadataMapper(meta, isSaved, permissions)
        return fullMeta.toResult()
    }

}