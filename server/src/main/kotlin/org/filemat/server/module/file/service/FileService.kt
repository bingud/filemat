package org.filemat.server.module.file.service

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
import org.filemat.server.module.file.model.*
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock


/**
 * Service to interact with files.
 */
@Service
class FileService(
    private val fileVisibilityService: FileVisibilityService,
    @Lazy private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService,
    private val logService: LogService,
    private val filesystem: FilesystemService,
    private val filesystemService: FilesystemService,
) {

    fun getPermittedFileList(user: Principal): Result<List<FullFileMetadata>> {
        val permissions = entityPermissionService.getPermittedEntities(user)
        val fullMeta = permissions.mapNotNull { entityPermission ->
            val entity = entityService.getById(entityPermission.entityId, UserAction.GET_USER).valueOrNull ?: return@mapNotNull null
            if (entity.path == null) return@mapNotNull null

            val meta =  filesystemService.getMetadata(FilePath.of(entity.path)) ?: return@mapNotNull null
            val fullMeta = FullFileMetadata.from(meta, entityPermission.permissions)
            return@mapNotNull fullMeta
        }

        return fullMeta.toResult()
    }

    /**
     * Moves multiple files, returns inputted paths of successfully moved files
     */
    fun moveMultipleFiles(user: Principal, rawPaths: List<FilePath>, rawNewParentPath: FilePath): Result<List<FilePath>> {
        val (canonicalResult, parentPathHasSymlink) = resolvePath(rawNewParentPath)
        if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
        val newParentPath = canonicalResult.value

        val movedFiles: MutableList<FilePath> = mutableListOf()
        rawPaths.forEach {
            val (currentPathResult, pathHasSymlink) = resolvePath(it)
            if (currentPathResult.isNotSuccessful) return@forEach
            val currentPath = currentPathResult.value

            val newPath = FilePath.ofAlreadyNormalized(newParentPath.path.resolve(currentPath.path.fileName))
            if (newPath == newParentPath) return@forEach

            val op = moveFile(user, it, newPath)
            if (op.isSuccessful) {
                movedFiles.add(it)
            }
        }

        return Result.ok(movedFiles)
    }

    fun moveFile(user: Principal, rawPath: FilePath, rawNewPath: FilePath): Result<Unit> {
        // Resolve the target path
        val (canonicalResult, pathHasSymlink) = resolvePath(rawPath)
        if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
        val canonicalPath = canonicalResult.value

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

        if (newPathParent.pathString == "/") return Result.reject("Cannot change file path to root.")

        // Get the target path
        val newPath = FilePath.ofAlreadyNormalized(newPathParent.path.resolve(rawNewPath.path.fileName))

        // Check if file is being moved into itself
        if (newPath.path.startsWith(canonicalPath.path))

        // Check target parent folder `WRITE` permission
        isAllowedToEditFolder(user = user, canonicalPath = newPathParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Move the file in filesystem
        filesystem.moveFile(source = canonicalPath, destination = newPath, overwriteDestination = false).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update the entity
        entityService.move(path = canonicalPath, newPath = newPath.pathString, userAction = UserAction.MOVE_FILE).let {
            if (it.isNotSuccessful) {
                // Revert file move
                filesystem.moveFile(source = newPath, destination = canonicalPath, overwriteDestination = false)
                return it.cast()
            }
        }

        return Result.ok()
    }

    fun deleteFile(user: Principal, rawPath: FilePath): Result<Unit> {
        // Resolve the target path
        val (canonicalResult, pathHasSymlink) = resolvePath(rawPath)
        if (canonicalResult.isNotSuccessful) return canonicalResult.cast()
        val canonicalPath = canonicalResult.value

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
        filesystem.deleteFile(canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (entity != null) {
            entityService.delete(entity.entityId, UserAction.DELETE_FILE)
            if (entity.path != null) {
                entityPermissionService.memory_removeEntity(entity.path, entity.entityId)
            }
        }

        return Result.ok()
    }

    /**
     * Deletes a list of file paths.
     * Returns the number of successfully deleted files.
     */
    fun deleteFiles(user: Principal, rawPathList: List<FilePath>): Int {
        var deleted = 0
        var failed = 0
        rawPathList.forEach { path ->
            val result = deleteFile(user, path)
            if (result.isNotSuccessful) failed++ else deleted++
        }
        return deleted
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
    fun getFileContent(user: Principal, rawPath: FilePath, existingCanonicalPath: FilePath? = null, existingPathContainsSymlink: Boolean? = false, range: LongRange? = null,): Result<InputStream> {
        val (canonicalPath, pathContainsSymlink) = if (existingCanonicalPath != null && existingPathContainsSymlink != null) {
            existingCanonicalPath to existingPathContainsSymlink
        } else {
            resolvePath(rawPath).let { (path, containsSymlink) ->
                if (path.isNotSuccessful) {
                    return path.cast()
                }
                path.value to containsSymlink
            }
        }

        // Return content of symlink file itself
        // if following symlinks is disabled
        if (pathContainsSymlink) {
            isAllowedToAccessFile(user, rawPath).let {
                if (it.isNotSuccessful) return it.cast()
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

        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
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

    fun getMetadata(user: Principal, rawPath: FilePath): Result<FileMetadata> {
        val (pathResult, pathHasSymlink) = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        return getMetadata(user, rawPath, canonicalPath)
    }

    /**
     * Returns file metadata. Authenticates user
     */
    fun getMetadata(user: Principal, rawPath: FilePath, canonicalPath: FilePath): Result<FileMetadata> {
        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystem.getMetadata(rawPath)?.toResult()
            ?: Result.notFound()
    }

    /**
     * Returns file metadata along with applied file permissions. Authenticates user
     */
    fun getFullMetadata(user: Principal, rawPath: FilePath, canonicalPath: FilePath): Result<FullFileMetadata> {
        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val meta = filesystem.getMetadata(rawPath)
            ?: return Result.notFound()

        val permissions = getActualFilePermissions(user, canonicalPath)

        val fullMeta = FullFileMetadata.from(meta, permissions)
        return fullMeta.toResult()
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
    fun isAllowedToAccessFile(user: Principal, canonicalPath: FilePath, hasAdminAccess: Boolean? = null): Result<Unit> {
        // Deny blocked folder
        isPathAllowed(canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Verify the file location and handle conflicts
        val isFileAvailable = verifyEntityInode(canonicalPath, UserAction.READ_FOLDER)
        if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

        // Check if user can read any files
        val isAdmin = hasAdminAccess ?: hasAdminAccess(user)
        if (isAdmin != true) {
            val permissionResult = hasFilePermission(canonicalPath = canonicalPath, principal = user, permission = FilePermission.READ)
            if (permissionResult == false) return Result.reject("You do not have permission to access this file.")
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to write to a folder.
     */
    fun isAllowedToEditFolder(user: Principal, canonicalPath: FilePath): Result<Unit> {
        val isAdmin = hasAdminAccess(user)

        if (isAdmin == false) {
            // Check access permissions
            isAllowedToAccessFile(user, canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }

            // Check delete permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.WRITE).let {
                if (it == false) return Result.reject("You do not have permission to edit this folder.")
            }
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to read and delete a file.
     */
    fun isAllowedToDeleteFile(user: Principal, canonicalPath: FilePath): Result<Unit> {
        val isAdmin = hasAdminAccess(user)

        if (isAdmin == false) {
            // Check access permissions
            isAllowedToAccessFile(user, canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }

            // Check delete permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.DELETE).let {
                if (it == false) return Result.reject("You do not have permission to delete this file.")
            }
        }

        // Check is path is blocked from being deleted
        isPathDeletable(canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to read and delete a file.
     */
    fun isAllowedToMoveFile(user: Principal, canonicalPath: FilePath): Result<Unit> {
        val isAdmin = hasAdminAccess(user)
        if (isAdmin) return Result.ok()

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check delete permissions
        hasFilePermission(canonicalPath, user, false, FilePermission.MOVE).let {
            if (it == false) return Result.reject("You do not have permission to move this file.")
        }

        return Result.ok()
    }

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun getFolderEntries(user: Principal, canonicalPath: FilePath, foldersOnly: Boolean = false): Result<List<FullFileMetadata>> {
        val hasAdminAccess = hasAdminAccess(user)
        val isAllowed = isAllowedToAccessFile(user, canonicalPath = canonicalPath, hasAdminAccess = hasAdminAccess)
        if (isAllowed.isNotSuccessful) return isAllowed.cast()

        // Get folder entries
        val rawAllEntries = internalGetFolderEntries(canonicalPath = canonicalPath, userAction = UserAction.READ_FOLDER).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val rawEntries = if (!foldersOnly) rawAllEntries else rawAllEntries.filter { it.fileType == FileType.FOLDER || (it.fileType == FileType.FOLDER_LINK && State.App.followSymlinks) }

        // Filter entries which are allowed and user has sufficient permission
        // Resolve entries which are symlinks
        val entries = rawEntries.mapNotNull { meta: FileMetadata ->
            // Check if `it.fileType` is symlink, resolve if it is
            val entryPath = if (meta.fileType.isSymLink()) {
                val (resolvedResult, hasSymlink) = resolvePath(FilePath.of(meta.path))
                resolvedResult.let {
                    if (it.isNotSuccessful) return@mapNotNull null
                    it.value
                }
            } else {
                FilePath.of(meta.path)
            }

            val isPathAllowed = fileVisibilityService.isPathAllowed(entryPath) == null
            if (!isPathAllowed) return@mapNotNull null

            val permissions = getActualFilePermissions(
                canonicalPath = entryPath,
                user = user,
            )

            // Check permissions for entry
            val hasPermission = permissions.contains(FilePermission.READ)
            if (!hasPermission) return@mapNotNull null

            val fullMeta = FullFileMetadata.from(meta, permissions)
            return@mapNotNull fullMeta
        }

        return entries.toResult()
    }

    private fun hasAdminAccess(user: Principal): Boolean = user.hasAnyPermission(listOf(SystemPermission.ACCESS_ALL_FILES, SystemPermission.SUPER_ADMIN))

    fun hasFilePermission(canonicalPath: FilePath, principal: Principal, hasAdminAccess: Boolean? = null, permission: FilePermission): Boolean {
        if (hasAdminAccess == true) return true
        if (hasAdminAccess == null) {
            // Check for admin perms
            principal.getPermissions().let { perms ->
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
        return if (result == null) Result.ok() else Result.reject(result, source = "isPathAllowed")
    }

    fun isPathDeletable(canonicalPath: FilePath): Result<Unit> {
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

            // List entries
            val entries = Files.newDirectoryStream(canonicalPath.path).use { it.toList() }

            val files = entries.map {
                val entryPath = FilePath(it)
                filesystem.getMetadata(entryPath)
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
            val entity = entityService.getByPath(path.pathString, UserAction.NONE).let {
                if (it.notFound) return Result.ok()
                if (it.isNotSuccessful) return it.cast()
                it.value
            }
            val followSymlinks = entity.followSymlinks

            // Do not do inode check on unsupported filesystem.
            if (!entity.isFilesystemSupported || entity.inode == null) {
                val exists = filesystem.exists(path.path, followSymbolicLinks = followSymlinks)
                return if (exists) Result.ok() else Result.reject("Path does not exist.", source = "verifyEntityByInode-notSupported-notFound")
            }

            val newInode = filesystem.getInode(path.path, followSymbolicLinks = followSymlinks)
            // Inode matches normally
            if (entity.inode == newInode) return Result.ok()

            // Handle if a file with a different inode exists on the path
            if (newInode != null) {
                val existingEntityR = entityService.getByInodeWithNullPath(newInode, userAction)

                // Check if this inode was already in the database
                if (existingEntityR.isSuccessful) {
                    // Dangling entity exists with this inode.
                    // Associate this path to it.
                    val existingEntity = existingEntityR.value
                    entityService.updatePath(existingEntity.entityId, path.pathString, existingEntity, userAction)
                } else if (existingEntityR.hasError){
                    return existingEntityR.cast()
                }
            }

            // Path has unexpected Inode, so remove the path from the entity in database.
            entityService.move(
                path = path,
                newPath = null,
                userAction = userAction,
            )

            return Result.ok()
        } finally {
            lock.unlock()
            verifyLocks.remove(path.pathString, lock)
        }
    }
}
