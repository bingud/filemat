package org.filemat.server.module.file.service.file.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FolderSize
import org.filemat.server.common.util.measureFolderContents
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Service
class FileFolderEntriesService(
    private val fileService: FileService,
    private val fileVisibilityService: FileVisibilityService,
    private val savedFileService: SavedFileService,
    private val filesystemService: FilesystemService,
    private val logService: LogService,
    private val fileLockService: FileLockService,
) {

    fun getFolderEntries(
        user: Principal?,
        canonicalPath: FilePath,
        foldersOnly: Boolean = false,
        ignorePermissions: Boolean = false,
    ): Result<List<FullFileMetadata>> {
        val mapper = { meta: FileMetadata, getFull: (meta: FileMetadata) -> FullFileMetadata? ->
            getFull(meta)
        }

        return getFolderEntries(
            user = user,
            canonicalPath = canonicalPath,
            foldersOnly = foldersOnly,
            ignorePermissions = ignorePermissions,
            metaMapper = mapper
        )
    }

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun <T : AbstractFileMetadata> getFolderEntries(
        user: Principal?,
        canonicalPath: FilePath,
        foldersOnly: Boolean = false,
        ignorePermissions: Boolean = false,
        metaMapper: (meta: FileMetadata, getFull: (meta: FileMetadata) -> FullFileMetadata?) -> T?
    ): Result<List<T>> {
        val isAllowed = fileService.isAllowedToAccessFile(user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)
        if (isAllowed.isNotSuccessful) return isAllowed.cast()

        val followSymlinks = State.App.followSymlinks

        // Get folder entries
        val rawAllEntries: List<FileMetadata> = internalGetFolderEntries(canonicalPath = canonicalPath, userAction = UserAction.READ_FOLDER).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val rawEntries: List<FileMetadata> = if (foldersOnly == false) rawAllEntries else rawAllEntries.filter { it.fileType == FileType.FOLDER || (it.fileType == FileType.FOLDER_LINK && followSymlinks) }

        // Filter entries which are allowed and user has sufficient permission
        // Resolve entries which are symlinks
        val entries: List<T> = rawEntries.mapNotNull { meta: FileMetadata ->
            val entryPath = FilePath.of(meta.path)

            fileService.isAllowedToAccessFile(
                user = user,
                canonicalPath = entryPath,
                ignorePermissions = ignorePermissions
            ).let {
                if (it.isNotSuccessful) return@mapNotNull null
            }

            val fullMeta: T? = metaMapper(meta) { mappedMeta ->
                val permissions: Set<FilePermission> = user?.let { fileService.getActualFilePermissions(canonicalPath = entryPath, user = user) } ?: setOf(FilePermission.READ)

                // Check permissions for entry
                val hasPermission = permissions.contains(FilePermission.READ)
                if (!hasPermission) return@metaMapper null

                val isSaved = if (user != null) savedFileService.isSaved(user.userId, mappedMeta.path) else null

                return@metaMapper FullFileMetadata.from(mappedMeta, isSaved = isSaved, permissions = permissions)
            }

            return@mapNotNull fullMeta
        }

        return entries.toResult()
    }

    /**
     * Recursively counts files, folders, and total size under [rawPath].
     * Checks READ only on the requested folder. Descendants are counted without permission checks.
     */
    fun calculateFolderSize(user: Principal, rawPath: FilePath): Result<FolderSize> = runBlocking(Dispatchers.IO) {
        val pathResult = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return@runBlocking pathResult.cast()
        val canonicalPath = pathResult.value

        val isAllowed = fileService.isAllowedToAccessFile(user, canonicalPath)
        if (isAllowed.isNotSuccessful) return@runBlocking isAllowed.cast()

        val followSymlinks = State.App.followSymlinks
        val directoryOptions = if (followSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        if (!Files.isDirectory(canonicalPath.path, *directoryOptions)) {
            return@runBlocking Result.reject("Path is not a directory.")
        }

        try {
            Files.newDirectoryStream(canonicalPath.path).use { }
        } catch (_: Exception) {
            return@runBlocking Result.error("Failed to read this folder.")
        }

        canonicalPath.path.measureFolderContents(with = fileLockService).toResult()
    }

    /**
     * Directly gets entries from a folder. Is not authenticated.
     */
    private fun internalGetFolderEntries(canonicalPath: FilePath, userAction: UserAction): Result<List<FileMetadata>> {
        try {
            // Check if the resolved path is a folder
            if (!Files.isDirectory(canonicalPath.path, *if (State.App.followSymlinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                return Result.notFound()
            }

            // Check folder permissions
            if (!Files.isExecutable(canonicalPath.path)) {
                return Result.error("Missing permission to open this folder.")
            }

            // List entries
            val entries: List<Path> = Files.newDirectoryStream(canonicalPath.path).use { it.toList() }

            val files: List<FileMetadata> = entries.map { path: Path ->
                val entryPath = FilePath(path)
                return@map filesystemService.getMetadata(entryPath)
                    ?: throw IllegalStateException("Metadata object was null for a known file.")

            }

            return files.toResult()
        } catch (e: AccessDeniedException) {
            return Result.error("Access to folder was denied.")
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get folder entries with metadata.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to get contents of this folder.")
        }
    }
}