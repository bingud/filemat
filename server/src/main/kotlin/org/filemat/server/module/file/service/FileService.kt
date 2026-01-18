package org.filemat.server.module.file.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import org.apache.commons.io.input.BoundedInputStream
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.*
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getEffectiveFilePermissions
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.auth.model.Principal.Companion.hasAnyPermission
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.component.FileContentService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.sharedFile.service.FileShareService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipOutputStream
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.pathString


/**
 * Service to interact with files.
 */
@Service
class FileService(
    private val fileVisibilityService: FileVisibilityService,
    @Lazy private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService,
    private val logService: LogService,
    @Lazy private val filesystem: FilesystemService,
    private val fileShareService: FileShareService,
    private val savedFileService: SavedFileService,
    @Lazy private val fileContentService: FileContentService,
) {
    fun isFileStoreMatching(
        one: Path,
        two: Path
    ): Boolean? {
        try {
            val oneStore = Files.getFileStore(one)
            val twoStore = Files.getFileStore(two)

            return oneStore == twoStore
        } catch (e: Exception) {
            return null
        }
    }

    fun addFileToZip(
        zip: ZipOutputStream,
        rawPath: FilePath,
        existingBaseZipPath: Path?,
        principal: Principal?,
        shareToken: String?
    ) = fileContentService.addFileToZip(
        zip = zip,
        rawPath = rawPath,
        existingBaseZipPath = existingBaseZipPath,
        principal = principal,
        shareToken = shareToken
    )

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
        isAllowedToAccessFile(user = user, canonicalPath = actualCanonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check if user is permitted to write to destination folder
        isAllowedToEditFile(user = user, canonicalPath = canonicalParentDestinationPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Get destination path
        val canonicalDestinationPath = FilePath.ofAlreadyNormalized(
            canonicalParentDestinationPath.path.resolve(rawDestinationPath.path.fileName)
        )

        // Move the file
        filesystem.copyFile(
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

        val fileMeta = getFullMetadata(
            user = user,
            rawPath = rawDestinationPath,
            canonicalPath = canonicalDestinationPath,
        ).let {
            if (it.isNotSuccessful) return Result.error("File was copied. Server failed to provide file metadata.")
            it.value
        }

        return Result.ok(fileMeta)
    }

    fun resolvePathWithOptionalShare(path: FilePath, shareToken: String?, withPathContainsSymlink: Boolean): Pair<Result<FilePath>, Boolean> {
        val sharedPath = if (shareToken != null) {
            entityService.getByShareToken(shareToken = shareToken)
                .let {
                    if (it.isNotSuccessful) return Pair(it.cast(), false)

                    val sharePathStr = it.value.path ?: return Pair(Result.notFound(), false)
                    val sharePath = FilePath.of(sharePathStr)
                    val fullPath = sharePath.path.resolve(path.pathString.removePrefix("/"))
                    return@let FilePath.ofAlreadyNormalized(fullPath)
                }
        } else null

        return resolvePath(sharedPath ?: path)
    }

    fun resolvePathWithOptionalShare(path: FilePath, shareToken: String?): Result<FilePath> {
        val result = resolvePathWithOptionalShare(path, shareToken, true)
        return result.first
    }

    data class EditFileResult(val modifiedDate: Long, val size: Long)
    fun editFile(user: Principal, rawPath: FilePath, newContent: String): Result<EditFileResult> {
        val (canonicalResult, pathHasSymlink) = resolvePath(rawPath)
        val canonicalPath = canonicalResult.let {
            if (it.isNotSuccessful) return canonicalResult.cast()
            it.value
        }

        isAllowedToEditFile(
            user = user,
            canonicalPath = canonicalPath
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val file = canonicalPath.path.toFile()
        if (!file.exists()) {
            return Result.notFound()
        }

        try {
            file.writeText(newContent)
        } catch (e: Exception) {
            return Result.error("An error occurred while saving file.")
        }

        val modifiedDate = runCatching { file.lastModified() }.getOrElse { System.currentTimeMillis() }
        val size = filesystem.getSize(canonicalPath).let {
            if (it.isNotSuccessful) return@let StringUtils.measureByteSize(newContent)
            it.value
        }

        return Result.ok(
            EditFileResult(modifiedDate = modifiedDate, size = size)
        )
    }

    /**
     * @return List of files that are shared by a user, or globally
     */
    fun getSharedFileList(user: Principal, getAll: Boolean, userAction: UserAction): Result<List<FullFileMetadata>> {
        if (getAll && !user.hasPermission(SystemPermission.MANAGE_ALL_FILE_SHARES)) return Result.reject("You do not have permission to view all shared files.")

        val shares = let {
            if (getAll) {
                fileShareService.getAllShares(userAction).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            } else {
                fileShareService.getSharesByUserId(user.userId, userAction).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            }
        }

        val entities = shares
            .distinctBy { it.fileId }
            .mapNotNull { entityService.getById(entityId = it.fileId, userAction).valueOrNull }
            .let { entities ->
                if (!getAll) return@let entities

                return@let entities.filter { entity ->
                    entity.path ?: return@filter false
                    isAllowedToAccessFile(
                        user = user,
                        canonicalPath = FilePath.of(entity.path),
                        ignorePermissions = null,
                    ).let { permissionResult ->
                        return@filter permissionResult.isSuccessful
                    }
                }
            }


        val files = entities.mapNotNull {
            it.path ?: return@mapNotNull null
            val path = FilePath.of(it.path)

            getFullMetadata(
                user = user,
                rawPath = path,
                canonicalPath = path,
            ).valueOrNull
        }

        return files.toResult()
    }

    /**
     * @return List of top-level files that a user has access to
     */
    fun getPermittedFileList(user: Principal): Result<List<FullFileMetadata>> {
        val entityPermissions = entityPermissionService.getPermittedEntities(user)

        val fileMetadataList = entityPermissions.mapNotNull { entityPermission ->
            // Get entity
            val entity = entityService.getById(entityPermission.entityId, UserAction.GET_PERMITTED_ENTITIES).valueOrNull ?: return@mapNotNull null
            if (entity.path == null) return@mapNotNull null

            // Get metadata
            val meta =  filesystem.getMetadata(FilePath.of(entity.path)) ?: return@mapNotNull null

            // Get saved status
            val isSaved = savedFileService.isSaved(user.userId, entity.path)

            val fullMeta = FullFileMetadata.from(
                meta,
                isSaved = isSaved,
                permissions = entityPermission.permissions
            )

            return@mapNotNull fullMeta
        }

        return fileMetadataList.toResult()
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

    fun moveFile(user: Principal, rawPath: FilePath, rawNewPath: FilePath): Result<Unit> {
        val isSymlink = rawPath.path.isSymbolicLink()

        // Resolve the target path
        val canonicalPath = if (isSymlink) rawPath else resolvePath(rawPath)
            .let { (canonicalResult, pathHasSymlink) ->
                canonicalResult.isNotSuccessful
                canonicalResult.value
            }

        if (canonicalPath.pathString == "/") return Result.reject("Cannot move root folder.")

        // Check if user is permitted to move the file
        isAllowedToMoveFile(user = user, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

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

        // Check target parent folder `WRITE` permission
        isAllowedToEditFile(user = user, canonicalPath = newPathParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Move the file in filesystem
        filesystem.moveFile(user = user, source = canonicalPath, destination = newPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update the entity
        entityService.move(path = canonicalPath, newPath = newPath, userAction = UserAction.MOVE_FILE).let {
            if (it.isNotSuccessful) {
                // Revert file move
                filesystem.moveFile(user = user, source = newPath, destination = canonicalPath)
                return it.cast()
            }
        }

        // Update saved files
        savedFileService.changePath(path = canonicalPath, newPath = newPath)

        return Result.ok()
    }

    fun deleteFile(user: Principal, rawPath: FilePath): Result<Unit> {
        val isSymlink = rawPath.path.isSymbolicLink()

        // Resolve the target path
        val canonicalPath = if (isSymlink) {
            rawPath
        } else {
            resolvePath(rawPath).let { (canonicalResult, pathHasSymlink) ->
                if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
                canonicalResult.value
            }
        }
        println("raw: ${rawPath.path}")
        println("canonical: $canonicalPath")

        if (canonicalPath.pathString == "/") return Result.reject("Cannot delete root folder.")

        // Check basic access
        isAllowedToDeleteFile(user = user, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Verify file exists
        val exists = filesystem.exists(canonicalPath.path, followSymbolicLinks = false)
        if (!exists) return Result.reject("File not found.")

        val entity = entityService.getByPath(canonicalPath.pathString, UserAction.DELETE_FILE).let {
            if (it.hasError) return it.cast()
            it.valueOrNull
        }

        // Perform deletion
        filesystem.deleteFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (entity != null) {
            entityService.delete(entity.entityId, UserAction.DELETE_FILE)
            if (entity.path != null) {
                entityPermissionService.memory_removeEntity(entity.path, entity.entityId)
            }
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

    fun createFolder(user: Principal, rawPath: FilePath): Result<Unit> {
        val rawParentPath = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        // Get folder parent path
        val (canonicalParentResult, pathHasSymlink) = resolvePath(rawParentPath)
        if (canonicalParentResult.isNotSuccessful) return canonicalParentResult.cast()
        val canonicalParent = canonicalParentResult.value

        // Check permissions
        isAllowedToAccessFile(user = user, canonicalPath = canonicalParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        hasFilePermission(
            canonicalPath = canonicalParent,
            principal = user,
            permission = FilePermission.WRITE
        ).let {
            if (it == false) return Result.reject("You do not have permission to modify this folder.")
        }

        // Get folder canonical path
        val folderName = rawPath.path.fileName
        val canonicalPath = FilePath.ofAlreadyNormalized(canonicalParent.path.resolve(folderName))

        // Check if folder already exists
        val alreadyExists = filesystem.exists(canonicalPath.path, false)
        if (alreadyExists) return Result.reject("This folder already exists.")

        // Create folder
        filesystem.createFolder(canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        entityService.create(
            canonicalPath = canonicalPath,
            ownerId = user.userId,
            userAction = UserAction.CREATE_FOLDER,
            followSymLinks = false
        )

        return Result.ok()
    }

    /**
     * Returns an input stream for the content of a file
     *
     * Optionally returns a byte range
     */
    fun getFileContent(
        user: Principal?,
        rawPath: FilePath,
        existingCanonicalPath: FilePath? = null,
        existingPathContainsSymlink: Boolean? = false,
        range: LongRange? = null,
        ignorePermissions: Boolean = false
    ): Result<InputStream> {
        val (canonicalPath, pathContainsSymlink) = let {
            if (existingCanonicalPath != null && existingPathContainsSymlink != null) {
                existingCanonicalPath to existingPathContainsSymlink
            } else {
                resolvePath(rawPath).let { (path, containsSymlink) ->
                    if (path.isNotSuccessful) {
                        return path.cast()
                    }
                    path.value to containsSymlink
                }
            }
        }

        // Return content of symlink file itself
        // if following symlinks is disabled
        if (pathContainsSymlink) {
            if (!ignorePermissions) {
                isAllowedToAccessFile(user, rawPath).let {
                    if (it.isNotSuccessful) return it.cast()
                }
            }

            if (Files.isSymbolicLink(rawPath.path)) {
                // stream the link itself
                return try {
                    Result.ok(Files.readSymbolicLink(rawPath.path).toString().toByteArray().inputStream())
                } catch (e: Exception) {
                    Result.error("Failed to read the symlink target path.")
                }
            } else {
                return Result.notFound()
            }
        }

        if (!ignorePermissions) {
            isAllowedToAccessFile(user, canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        if (!Files.isRegularFile(canonicalPath.path, LinkOption.NOFOLLOW_LINKS)) return Result.notFound()

        return try {
            val fileInputStream = Files.newInputStream(canonicalPath.path)
            if (range == null) return fileInputStream.toResult()

            safeStreamSkip(fileInputStream, range.first)
                .let { if (!it) return Result.error("Failed to get the requested range from a stream.") }

            val bounded = BoundedInputStream.builder()
                .setInputStream(fileInputStream)
                .setMaxCount((range.last - range.first) + 1)
                .get()

            return bounded.toResult()
        } catch (e: Exception) {
            Result.error("Failed to stream file.")
        }
    }


    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath, foldersOnly: Boolean = false): Result<Pair<FullFileMetadata, List<FullFileMetadata>?>> {
        val (pathResult, pathHasSymlink) = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        val metadata: FullFileMetadata = getFullMetadata(user, rawPath = rawPath, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val type = metadata.fileType

        if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymlinks)) {
            val entries = getFolderEntries(
                user = user,
                canonicalPath = canonicalPath,
                foldersOnly = foldersOnly
            ).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

            return Result.ok(metadata to entries)
        } else if (type == FileType.FILE || type == FileType.FILE_LINK || type == FileType.FOLDER_LINK) {
            return Result.ok(metadata to null)
        } else {
            return Result.error("Requested path is not a file or folder.")
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
        val (canonicalSharePathResult, parentPathHasSymlink) = resolvePath(rawSharePath)
        if (canonicalSharePathResult.isNotSuccessful) return canonicalSharePathResult.cast()
        val canonicalSharePath = canonicalSharePathResult.value

        // Get path of requested file
        val canonicalPath = FilePath.ofAlreadyNormalized(
            canonicalSharePath.path.resolve(rawPath.path.toString().removePrefix("/"))
        )

        // Get file metadata, change its path to be relative
        val rawMetadata: FileMetadata = filesystem.getMetadata(canonicalPath) ?: return Result.notFound()

        val metadata = rawMetadata.copy(path = rawPath.pathString)
        val type = metadata.fileType

        if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymlinks)) {
            // Get folder entries, change paths back to relative
            val entries = getFolderEntries(
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
    }

    fun getMetadata(user: Principal?, rawPath: FilePath, isPathCanonical: Boolean = false): Result<FileMetadata> {
        val canonicalPath = if (isPathCanonical) {
            rawPath
        } else {
            val (pathResult, pathHasSymlink) = resolvePath(rawPath)
            if (pathResult.isNotSuccessful) return pathResult.cast()
            pathResult.value
        }

        return getMetadata(user, rawPath, canonicalPath)
    }

    /**
     * Returns file metadata. Authenticates user
     */
    fun getMetadata(user: Principal?, rawPath: FilePath, canonicalPath: FilePath): Result<FileMetadata> {
        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystem.getMetadata(rawPath)?.toResult()
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
            isAllowedToAccessFile(user, canonicalPath).let { it: Result<Unit> ->
                if (it.isNotSuccessful) return it.cast()
            }
        }

        val meta = filesystem.getMetadata(rawPath)
            ?: return Result.notFound()

        val permissions: Set<FilePermission> = user?.let { getActualFilePermissions(user, canonicalPath) } ?: setOf(FilePermission.READ)
        val isSaved = if (user != null) savedFileService.isSaved(user.userId, meta.path) else null

        val fullMeta = metadataMapper(meta, isSaved, permissions)
        return fullMeta.toResult()
    }

    fun searchFiles(
        user: Principal?,
        canonicalPath: FilePath,
        text: String,
        isShared: Boolean = false,
        userAction: UserAction
    ): Flow<Result<FullFileMetadata>> {
        val lowercaseText = text.lowercase()

        // Symlinks permanently disabled to prevent loops
        return canonicalPath.path.safeWalk()
            .mapNotNull { path ->
                try {
                    // Check searched text
                    if (path.fileName?.pathString?.lowercase()?.contains(lowercaseText) == true) {
                        val filePath = FilePath.ofAlreadyNormalized(path)

                        // Get metadata
                        getFullMetadata(user, filePath, filePath, ignorePermissions = isShared).let {
                            if (it.isNotSuccessful) return@mapNotNull null
                            return@mapNotNull it
                        }
                    } else return@mapNotNull null
                } catch (e: Exception) {
                    return@mapNotNull null
                }
            }
            .take(10_000)
            .flowOn(Dispatchers.IO)
    }

    /**
     * Returns the total list of file permissions currently available for a user on a file
     */
    fun getActualFilePermissions(user: Principal, canonicalPath: FilePath): Set<FilePermission> {
        val globalPermissions = user.getEffectiveFilePermissions()
        val permissions = entityPermissionService.getUserPermission(canonicalPath = canonicalPath, userId = user.userId, roles = user.roles)
            ?.permissions ?: emptyList()

        return globalPermissions + permissions
    }

    /**
     * Fully verifies if a user is allowed to read a file
     */
    fun isAllowedToAccessFile(user: Principal?, canonicalPath: FilePath, checkPermissionOnly: Boolean = false, ignorePermissions: Boolean? = null): Result<Unit> {
        user ?: return Result.reject("Unauthenticated")

        if (!checkPermissionOnly) {
            // Deny blocked folder
            isPathAllowed(canonicalPath = canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        // Check if user can read all files
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        if (ignorePerms == false) {
            // Verify the file location and handle conflicts
            val isFileAvailable = verifyEntityInode(canonicalPath, UserAction.READ_FOLDER)
            if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

            val permissionResult = hasFilePermission(canonicalPath = canonicalPath, principal = user, permission = FilePermission.READ)
            if (permissionResult == false) return Result.reject("You do not have permission to access this file.")
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to write to a folder.
     */
    fun isAllowedToEditFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePerms).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val editable = isPathEditable(canonicalPath)
        if (editable != null) return Result.reject(editable)

        if (ignorePerms == false) {
            // Check write permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.WRITE).let {
                if (it == false) return Result.reject("You do not have permission to edit this file.")
            }
        }

        return Result.ok()
    }

    fun isAllowedToShareFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePerms).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (ignorePerms == false) {
            // Check write permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.SHARE).let {
                if (it == false) return Result.reject("You do not have permission to share this file.")
            }
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to read and delete a file.
     */
    fun isAllowedToDeleteFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePerms).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check is path is blocked from being deleted
        isPathDeletable(canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (ignorePerms == false) {
            // Check delete permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.DELETE).let {
                if (it == false) return Result.reject("You do not have permission to delete this file.")
            }
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to read and delete a file.
     */
    fun isAllowedToMoveFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePerms).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val editable = isPathEditable(canonicalPath)
        if (editable != null) return Result.reject(editable)

        if (!ignorePerms) {
            // Check delete permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.MOVE).let {
                if (it == false) return Result.reject("You do not have permission to move this file.")
            }
        }

        return Result.ok()
    }

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
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
        val hasAdminAccess = user?.let { hasAdminAccess(user) } ?: false
        if (!ignorePermissions) {
            val isAllowed = isAllowedToAccessFile(user, canonicalPath = canonicalPath, ignorePermissions = hasAdminAccess)
            if (isAllowed.isNotSuccessful) return isAllowed.cast()
        }

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

            val isPathAllowed = fileVisibilityService.isPathAllowed(entryPath) == null
            if (!isPathAllowed) return@mapNotNull null

            val fullMeta: T? = metaMapper(meta) { mappedMeta ->
                val permissions: Set<FilePermission> = user?.let { getActualFilePermissions(canonicalPath = entryPath, user = user) } ?: setOf(FilePermission.READ)

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

    private fun hasAdminAccess(user: Principal): Boolean = user.hasAnyPermission(listOf(SystemPermission.ACCESS_ALL_FILES, SystemPermission.SUPER_ADMIN))

    fun hasFilePermission(canonicalPath: FilePath, principal: Principal, hasAdminAccess: Boolean? = null, permission: FilePermission): Boolean {
        if (hasAdminAccess == true) return true
        if (hasAdminAccess == null) {
            // Check for admin perms
            principal.getPermissions().let { perms: List<SystemPermission> ->
                if (perms.any { it == SystemPermission.SUPER_ADMIN || it == SystemPermission.ACCESS_ALL_FILES }) return true
            }
        }

        val permissions = entityPermissionService.getUserPermission(canonicalPath = canonicalPath, userId = principal.userId, roles = principal.roles)
            ?: return false

        return permissions.permissions.contains(permission)
    }


    /**
     * Returns null if path is allowed. Otherwise returns string error.
     */
    fun isPathAllowed(canonicalPath: FilePath): Result<Unit> {
        val result = fileVisibilityService.isPathAllowed(canonicalPath = canonicalPath)
        return if (result == null) Result.ok() else Result.reject(result)
    }

    fun isPathEditable(canonicalPath: FilePath): String? {
        if (!State.App.allowWriteDataFolder) {
            if (canonicalPath.path.startsWith(Props.dataFolderPath)) return "Cannot edit ${Props.appName} data folder."
        }
        return null
    }

    fun isPathDeletable(canonicalPath: FilePath): Result<Unit> {
        val editable = isPathEditable(canonicalPath)
        if (editable != null) return Result.reject(editable)

        // Is path a system folder
        val isProtected = Props.nonDeletableFolders.isProtected(canonicalPath.pathString, true)
        // Was path made deletable
        val isForcedDeletable = State.App.forceDeletableFolders.contains(canonicalPath.pathString)

        if (isProtected && !isForcedDeletable) return Result.reject("This system folder cannot be deleted.")
        return Result.ok()
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
                return Result.error("Insufficient permissions to open folder.")
            }

            // List entries
            val entries: List<Path> = Files.newDirectoryStream(canonicalPath.path).use { it.toList() }

            val files: List<FileMetadata> = entries.map { path: Path ->
                val entryPath = FilePath(path)
                return@map filesystem.getMetadata(entryPath)
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


    private val verifyLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Verifies whether a file exists.
     *
     * Handles file conflicts like moved files. Can reassign inode or path of an entity.
     */
    fun verifyEntityInode(path: FilePath, userAction: UserAction): Result<Unit> {
        val lock = verifyLocks.computeIfAbsent(path.pathString) { ReentrantLock() }
        lock.lock()
        try {
            // Get indexed entity
            val entityResult = entityService.getByPath(path.pathString, UserAction.NONE)
            if (entityResult.hasError) return entityResult.cast()
            val entity = entityResult.valueOrNull

            // Do not do inode check on unsupported filesystem.
            if (entity != null && (!entity.isFilesystemSupported || entity.inode == null)) {
                val exists = filesystem.exists(path.path, followSymbolicLinks = false)
                return if (exists) Result.ok() else Result.reject("Path does not exist.")
            }

            val newInode = filesystem.getInode(path.path, followSymbolicLinks = false)
            // Inode matches normally
            if (entity?.inode == newInode) return Result.ok()

            // Handle if a file with a different inode exists on the path
            if (newInode != null) {
                val existingEntityR = entityService.getByInode(newInode, userAction)

                // Check if this inode was already in the database
                if (existingEntityR.isSuccessful) {
                    // Dangling entity exists with this inode.
                    // Associate this path to it.
                    val existingEntity = existingEntityR.value

                    return entityService.updatePath(existingEntity.entityId, path.pathString, existingEntity, userAction)
                } else if (existingEntityR.hasError){
                    return existingEntityR.cast()
                } else if (existingEntityR.notFound) {
                    return Result.ok()
                }
            }

            // Path has unexpected Inode, so remove the path from the entity in database.
            return entityService.move(
                path = path,
                newPath = null,
                userAction = userAction,
            )
        } finally {
            lock.unlock()
            verifyLocks.remove(path.pathString, lock)
        }
    }
}