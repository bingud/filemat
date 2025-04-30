package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.*
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
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
    private val folderVisibilityService: FolderVisibilityService,
    @Lazy private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService,
    private val logService: LogService,
    private val filesystem: FilesystemService,
) {

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
     */
    fun getFileContent(user: Principal, rawPath: FilePath): Result<InputStream> {
        val (pathResult, pathContainsSymlink) = resolvePath(rawPath)

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

        val canonicalPath = pathResult.let {
            if (it.isNotSuccessful) {
                return it.cast()
            }
            it.value
        }

        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (!Files.isRegularFile(canonicalPath.path, LinkOption.NOFOLLOW_LINKS)) return Result.notFound()

        return try {
            Result.ok(Files.newInputStream(canonicalPath.path))
        } catch (e: Exception) {
            Result.error("Failed to stream file.")
        }
    }


    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath): Result<Pair<FileMetadata, List<FileMetadata>?>> {
        val (pathResult, pathHasSymlink) = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        val metadata = getMetadata(user, rawPath = rawPath, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val type = metadata.fileType

        if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymLinks)) {
            val entries = getFolderEntries(
                user = user,
                canonicalPath = canonicalPath,
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
     * Fully verifies if a user is allowed to read a file
     */
    fun isAllowedToAccessFile(user: Principal, canonicalPath: FilePath, hasAdminAccess: Boolean? = null): Result<Unit> {
        // Deny blocked folder
        val isAllowedResult = isPathAllowed(canonicalPath = canonicalPath)
        if (isAllowedResult.isNotSuccessful) return isAllowedResult.cast()

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
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun getFolderEntries(user: Principal, canonicalPath: FilePath): Result<List<FileMetadata>> {
        val hasAdminAccess = hasAdminAccess(user)
        val isAllowed = isAllowedToAccessFile(user, canonicalPath = canonicalPath, hasAdminAccess = hasAdminAccess)
        if (isAllowed.isNotSuccessful) return isAllowed.cast()

        // Get folder entries
        val result = internalGetFolderEntries(canonicalPath = canonicalPath, userAction = UserAction.READ_FOLDER)
        if (result.isNotSuccessful) return result.cast()

        // Filter entries which are allowed and user has sufficient permission
        // Resolve entries which are symlinks
        val entries = result.value.filter { meta: FileMetadata ->
            // Check if `it.fileType` is symlink, resolve if it is
            val entryPath = if (meta.fileType.isSymLink()) {
                val (resolvedResult, hasSymlink) = resolvePath(FilePath.of(meta.path))
                resolvedResult.let {
                    if (it.isNotSuccessful) return@filter false
                    it.value
                }
            } else {
                FilePath.of(meta.path)
            }

            val isPathAllowed = folderVisibilityService.isPathAllowed(entryPath) == null
            if (!isPathAllowed) return@filter false

            // Check permissions for entry
            if (!hasAdminAccess) {
                val permission = entityPermissionService.getUserPermission(
                    canonicalPath = entryPath,
                    userId = user.userId,
                    roles = user.roles
                )
                val hasPermission = permission != null && permission.permissions.contains(FilePermission.READ)
                if (!hasPermission) return@filter false
            }

            true
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
        val result = folderVisibilityService.isPathAllowed(canonicalPath = canonicalPath)
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
            if (!Files.isDirectory(canonicalPath.path, *if (State.App.followSymLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS))) {
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
            entityService.updatePath(
                entityId = entity.entityId,
                newPath = null,
                existingEntity = entity,
                userAction = userAction,
            )

            return Result.ok()
        } finally {
            lock.unlock()
            verifyLocks.remove(path.pathString, lock)
        }
    }
}