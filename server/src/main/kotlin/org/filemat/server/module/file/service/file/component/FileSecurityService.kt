package org.filemat.server.module.file.service.file.component

import com.github.f4b6a3.ulid.Ulid
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
     * Fully verifies if a user is allowed to read a file
     */
    fun isAllowedToAccessFile(user: Principal?, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        // Share-link flows pass ignorePermissions=true after the path was scoped by [shareToken]
        // through FileService.resolvePathWithOptionalShare (share symlink policy + root confine).
        // there may be no logged-in user, but blocked/sensitive paths must still be rejected.
        val ignorePerms = ignorePermissions ?: user?.let { hasAdminAccess(it) } ?: false

        if (user == null && !ignorePerms) {
            return Result.reject("Unauthenticated")
        }

        // Deny blocked folder
        isPathAllowed(canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (ignorePerms) {
            return Result.ok()
        } else {
            hasPermissionToAccessFile(user, canonicalPath).let {
                if (it.isNotSuccessful) return it
            }
        }

        return Result.ok()
    }

    fun hasPermissionToAccessFile(user: Principal?, canonicalPath: FilePath): Result<Unit> {
        val u = user ?: return Result.reject("Unauthenticated")
        // Verify the file location and handle conflicts
        val isFileAvailable = verifyEntityInode(canonicalPath, UserAction.READ_FOLDER)
        if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

        val permissionResult = hasFilePermission(canonicalPath = canonicalPath, principal = u, permission = FilePermission.READ)
        if (permissionResult == false) return Result.reject("You do not have permission to access this file.")
        return Result.ok()
    }

    /**
     * Fully verifies if a user is allowed to write to a folder.
     */
    fun isAllowedToEditFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit> {
        val ignorePerms = ignorePermissions ?: hasAdminAccess(user)

        // Check access permissions
        isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePerms).let {
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
        isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePerms).let {
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
        isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePerms).let {
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

    /**
     * Returns the total list of file permissions currently available for a user on a file
     */
    fun getActualFilePermissions(user: Principal, canonicalPath: FilePath): Set<FilePermission> {
        val globalPermissions = user.getEffectiveFilePermissions()
        // Now returns a merged set from the tree, respecting inheritance depth and type priority
        val permissions = entityPermissionService.getEffectivePermissions(canonicalPath = canonicalPath, userId = user.userId, roles = user.roles)
        return globalPermissions + permissions
    }

    fun hasFilePermission(canonicalPath: FilePath, principal: Principal, hasAdminAccess: Boolean? = null, permission: FilePermission): Boolean {
        if (hasAdminAccess == true) return true
        if (hasAdminAccess == null) {
            // Check for admin perms
            principal.getPermissions().let { perms: List<SystemPermission> ->
                if (perms.any { it == SystemPermission.SUPER_ADMIN || it == SystemPermission.ACCESS_ALL_FILES }) return true
            }
        }

        val permissions = entityPermissionService.getEffectivePermissions(canonicalPath = canonicalPath, userId = principal.userId, roles = principal.roles)
        return permissions.contains(permission)
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
     * Does not create entities for unindexed paths.
     */
    fun verifyEntityInode(path: FilePath, userAction: UserAction): Result<Unit> {
        return withVerifyPathLock(path) {
            repairEntityInode(path = path, userAction = userAction, reusePathEntity = true)
        }
    }

    /**
     * Ensures that a file has a DB entity.
     *
     * [reusePathEntity] = true keeps the path entity identity (overwrite / claim path slot).
     * [reusePathEntity] = false is for new objects: reconnect by inode only; orphan a path entity
     * whose inode does not match, then create a new entity if still missing.
     *
     * Holds the path verify lock across repair and create to avoid unique-inode races.
     */
    fun ensureEntityIndexed(
        path: FilePath,
        ownerId: Ulid?,
        userAction: UserAction,
        reusePathEntity: Boolean = false,
    ): Result<Unit> {
        return withVerifyPathLock(path) {
            repairEntityInode(path = path, userAction = userAction, reusePathEntity = reusePathEntity).let {
                if (it.isNotSuccessful) return@withVerifyPathLock it
            }

            entityService.getByPath(path.pathString, userAction).let {
                if (it.hasError) return@withVerifyPathLock it.cast()
                if (it.isSuccessful) return@withVerifyPathLock Result.ok()
            }

            entityService.withEntityRepairLock {
                entityService.getByPath(path.pathString, userAction).let {
                    if (it.hasError) return@withEntityRepairLock it.cast()
                    if (it.isSuccessful) return@withEntityRepairLock Result.ok()
                }

                entityService.create(
                    canonicalPath = path,
                    ownerId = ownerId,
                    userAction = userAction,
                ).let {
                    if (it.isSuccessful || it.rejected) return@withEntityRepairLock Result.ok()
                    it.cast()
                }
            }
        }
    }

    private fun <T> withVerifyPathLock(path: FilePath, block: () -> T): T {
        val lock = verifyLocks.computeIfAbsent(path.pathString) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
            verifyLocks.remove(path.pathString, lock)
        }
    }

    /**
     * Tries to repair dangling entities and match them with a file. Caller must hold [withVerifyPathLock].
     */
    private fun repairEntityInode(path: FilePath, userAction: UserAction, reusePathEntity: Boolean): Result<Unit> {
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
            return entityService.withEntityRepairLock {
                val existingEntityR = entityService.getByInode(newInode, userAction)
                if (existingEntityR.hasError) return@withEntityRepairLock existingEntityR.cast()

                // Re-read path entity under the repair lock to close races with concurrent indexers.
                val pathEntityResult = entityService.getByPath(path.pathString, userAction)
                if (pathEntityResult.hasError) return@withEntityRepairLock pathEntityResult.cast()
                val pathEntity = pathEntityResult.valueOrNull

                if (pathEntity != null) {
                    if (pathEntity.inode == newInode) return@withEntityRepairLock Result.ok()

                    if (reusePathEntity) {
                        if (existingEntityR.isSuccessful && existingEntityR.value.entityId != pathEntity.entityId) {
                            entityService.updateInode(existingEntityR.value.entityId, null, existingEntityR.value, userAction).let {
                                if (it.isNotSuccessful) return@withEntityRepairLock it.cast()
                            }
                        }
                        return@withEntityRepairLock entityService.updateInode(pathEntity.entityId, newInode, pathEntity, userAction)
                    }

                    // New object at this path: orphan the stale path entity instead of reusing it.
                    entityService.updatePath(
                        entityId = pathEntity.entityId,
                        newPath = null,
                        existingEntity = pathEntity,
                        userAction = userAction,
                    ).let {
                        if (it.isNotSuccessful) return@withEntityRepairLock it.cast()
                    }
                }

                // No entity at path (or just orphaned): reconnect a dangling inode entity if one exists.
                if (existingEntityR.isSuccessful) {
                    val inodeEntity = existingEntityR.value
                    if (inodeEntity.path != path.pathString) {
                        return@withEntityRepairLock entityService.updatePath(
                            entityId = inodeEntity.entityId,
                            newPath = path.pathString,
                            existingEntity = inodeEntity,
                            userAction = userAction,
                        )
                    }
                    return@withEntityRepairLock Result.ok()
                }

                if (existingEntityR.notFound) {
                    return@withEntityRepairLock Result.ok()
                }

                Result.ok()
            }
        }

        // Path has unexpected Inode, so remove the path from the entity in database.
        return entityService.withEntityRepairLock {
            entityService.move(
                path = path,
                newPath = null,
                userAction = userAction,
            )
        }
    }
}