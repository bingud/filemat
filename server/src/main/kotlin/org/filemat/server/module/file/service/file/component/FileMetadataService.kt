package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.localDrivePath
import org.filemat.server.common.util.resolvePath
import org.filemat.server.config.platform.CaseSensitivity
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.sharedFile.resolveSharedDescendant
import org.springframework.stereotype.Service

@Service
class FileMetadataService(
    private val fileService: FileService,
    private val filesystemService: FilesystemService,
    private val savedFileService: SavedFileService,
    private val fileLockService: FileLockService,
    private val state: State,
) {

    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath, foldersOnly: Boolean = false): Result<Pair<FullFileMetadata, List<FullFileMetadata>?>> {
        val pathResult = resolvePath(rawPath, state.app.followSymlinks)
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

            if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && state.app.followSymlinks)) {
                val entries = fileService.getFolderEntries(
                    user = user,
                    canonicalPath = canonicalPath,
                    foldersOnly = foldersOnly
                ).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }

                Result.ok(metadata.copy(caseSensitive = caseSensitivityOf(canonicalPath)) to entries)
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
        val shareRoot = fileService.resolvePathWithOptionalShare(FilePath.of(PathPolicy.root), shareToken).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val canonicalPath = fileService.resolvePathWithOptionalShare(rawPath, shareToken).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val lock = fileLockService.getLock(canonicalPath.path, LockType.READ)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        try {
            // Get file metadata, change its path to be relative
            val rawMetadata: FileMetadata = filesystemService.getMetadata(canonicalPath) ?: return Result.notFound()

            val metadata = rawMetadata.copy(path = rawPath.pathString)
            val type = metadata.fileType

            if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && state.app.followSymlinks)) {
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
                }.mapNotNull { entry ->
                    val entryPath = PathPolicy.parse(entry.path)
                    if (resolveSharedDescendant(entryPath, shareRoot, state.app.followSymlinks).isNotSuccessful) return@mapNotNull null

                    val relativePath = canonicalPath.path.relativize(entryPath)
                    val newPath = rawPath.path.resolve(relativePath)

                    entry.copy(path = PathPolicy.toApiString(newPath))
                }

                return Result.ok(metadata.copy(caseSensitive = caseSensitivityOf(canonicalPath)) to entries)
            } else if (type == FileType.FILE || type == FileType.FILE_LINK || type == FileType.FOLDER_LINK) {
                return Result.ok(metadata to null)
            } else {
                return Result.error("Requested path is not a file or folder.")
            }
        } finally {
            lock.unlock()
        }
    }

    fun getMetadata(user: Principal?, rawPath: FilePath, isPathCanonical: Boolean = false, ignorePermissions: Boolean = false): Result<FileMetadata> {
        val canonicalPath = if (isPathCanonical) {
            rawPath
        } else {
            val pathResult = resolvePath(rawPath, state.app.followSymlinks)
            if (pathResult.isNotSuccessful) return pathResult.cast()
            pathResult.value
        }

        return getMetadata(user, rawPath, canonicalPath, ignorePermissions)
    }

    /**
     * Returns file metadata. Authenticates user
     */
    fun getMetadata(user: Principal?, rawPath: FilePath, canonicalPath: FilePath, ignorePermissions: Boolean = false): Result<FileMetadata> {
        fileService.isAllowedToAccessFile(
            user = user,
            canonicalPath = canonicalPath,
            ignorePermissions = ignorePermissions,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystemService.getMetadata(displayPath(rawPath, canonicalPath))?.toResult()
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
        fileService.isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePermissions).let { it: Result<Unit> ->
            if (it.isNotSuccessful) return it.cast()
        }

        val meta = filesystemService.getMetadata(displayPath(rawPath, canonicalPath))
            ?: return Result.notFound()

        val permissions: Set<FilePermission> = user?.let { fileService.getActualFilePermissions(user, canonicalPath) } ?: setOf(FilePermission.READ)
        val isSaved = if (user != null) savedFileService.isSaved(user.userId, meta.path) else null

        val fullMeta = metadataMapper(meta, isSaved, permissions)
        return fullMeta.toResult()
    }

    /**
     * The requested path, so that a followed link keeps its own path. On Windows a path in the wrong case
     * names the same file, and then the real case is shown (the web app rewrites its URL to it).
     * A path on a `subst` drive is shown on the drive it points to, like the entries of a listing.
     */
    private fun displayPath(rawPath: FilePath, canonicalPath: FilePath): FilePath {
        val requested = if (Platform.isWindows) localDrivePath(rawPath) ?: rawPath else rawPath
        return if (PathPolicy.isSamePath(requested.path, canonicalPath.path)) canonicalPath else requested
    }

    // The drive list is not a real folder, so it has no flag
    private fun caseSensitivityOf(folder: FilePath): Boolean? =
        if (PathPolicy.isVirtualRoot(folder.path)) null else CaseSensitivity.isCaseSensitive(folder.path)
}
