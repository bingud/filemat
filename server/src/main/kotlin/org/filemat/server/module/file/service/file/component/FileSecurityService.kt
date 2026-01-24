package org.filemat.server.module.file.service.file.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getEffectiveFilePermissions
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.auth.model.Principal.Companion.hasAnyPermission
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Service
class FileSecurityService(private val fileVisibilityService: FileVisibilityService, private val entityPermissionService: EntityPermissionService, private val entityService: EntityService, private val filesystemService: FilesystemService) {

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
     * Fully verifies if a user is allowed to read and delete a file.
     */
    fun isAllowedToRenameFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePerms).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val editable = isPathEditable(canonicalPath)
        if (editable != null) return Result.reject(editable)

        if (!ignorePerms) {
            // Check delete permissions
            hasFilePermission(canonicalPath, user, false, FilePermission.RENAME).let {
                if (it == false) return Result.reject("You do not have permission to rename this file.")
            }
        }

        return Result.ok()
    }

    fun hasAdminAccess(user: Principal): Boolean = user.hasAnyPermission(listOf(SystemPermission.ACCESS_ALL_FILES, SystemPermission.SUPER_ADMIN))

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
                val exists = filesystemService.exists(path.path, followSymbolicLinks = false)
                return if (exists) Result.ok() else Result.reject("Path does not exist.")
            }

            val newInode = filesystemService.getInode(path.path, followSymbolicLinks = false)
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