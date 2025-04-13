package org.filemat.server.module.permission.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.classes.Token
import org.filemat.server.common.util.normalizePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.model.*
import org.filemat.server.module.permission.repository.PermissionRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service


/**
 * Service for file permissions
 */
@Service
class EntityPermissionService(
    private val permissionRepository: PermissionRepository,
    private val logService: LogService,
    private val entityService: EntityService,
    private val fileService: FileService,
) {
    /**
     * Holds user and role permissions and owner ID for file paths in a tree.
     */
    private val pathTree = EntityPermissionTree()


    fun deletePermission(user: Principal, permissionId: Ulid): Result<Unit> {
        val action = UserAction.DELETE_ENTITY_PERMISSION

        // Get the entity permission
        val permission = pathTree.getPermissionById(permissionId)
            ?: return Result.reject("This permission ID does not exist.")

        // Get the underlying entity
        val entity = entityService.getById(permission.entityId, action).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        entity.path ?: return Result.error("The path for the indexed file for this permission is null.")

        fileService.isAllowedToAccessFile(user = user, pathObject = FilePath(entity.path)).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check if user has permission to edit file permissions
        val hasPermission = user.canEditEntityPermission(entityOwnerId = entity .ownerId)
        if (!hasPermission) return Result.reject("You do not have permission to modify the permissions of this file.")

        // Delete permission
        db_deletePermission(permissionId, action).let {
            if (it.isNotSuccessful) return it.cast()
        }

        pathTree.removePermissionByPermissionId(permissionId)

        return Result.ok()
    }

    /**
     * Update the list of permissions for a file permission
     */
    fun updatePermission(user: Principal, permissionId: Ulid, newPermissions: List<FilePermission>): Result<Unit> {
        val action = UserAction.UPDATE_ENTITY_PERMISSION

        // Get the entity permission
        val permission = pathTree.getPermissionById(permissionId)
            ?: return Result.reject("This permission ID does not exist.")

        // Get the underlying entity
        val entity = entityService.getById(permission.entityId, action).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        entity.path ?: return Result.error("The path for the indexed file for this permission is null.")

        fileService.isAllowedToAccessFile(user = user, pathObject = FilePath(entity.path)).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check if user has permission to edit file permissions
        val hasPermission = user.canEditEntityPermission(entityOwnerId = entity.ownerId)
        if (!hasPermission) return Result.reject("You do not have permission to modify the permissions of this file.")

        val newPermission = permission.copy(
            permissions = newPermissions
        )

        // Save new permission
        db_updatePermissions(permissionId = permissionId, newPermissions = newPermissions, action = action).let {
            if (it.isNotSuccessful) return it.cast()
        }

        pathTree.addPermission(entity.path, newPermission)

        return Result.ok()
    }

    /**
     * Create an entity permission
     */
    fun createPermission(user: Principal, path: FilePath, targetId: Ulid, mode: PermissionType, permissions: List<FilePermission>): Result<EntityPermission> {
        val action = UserAction.CREATE_ENTITY_PERMISSION
        // Check if user has file permission
        fileService.isAllowedToAccessFile(user = user, pathObject = path).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Verify / validate the file
        fileService.verifyEntityInode(filePath = path.path, userAction = action).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val existingEntity = entityService.getByPath(path = path.path, userAction = action).let {
            if (it.hasError) return it.cast()
            it.valueOrNull
        }

        // Check if user has permission to edit file permissions
        val hasPermission = user.canEditEntityPermission(entityOwnerId = existingEntity?.ownerId)
        if (!hasPermission) return Result.reject("You do not have permission to modify the permissions of this file.")

        // Check existing permission
        val existingPermission = if (mode == PermissionType.USER) {
            pathTree.getDirectPermissionForUser(path.path, targetId)
        } else {
            pathTree.getDirectPermissionForRole(path.path, targetId)
        }
        if (existingPermission != null) return Result.reject("This ${mode.name.lowercase()} already has a permission.")

        val entity = existingEntity
            ?: entityService.create(path, user.userId, action).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        val permission = EntityPermission(
            permissionId = UlidCreator.getUlid(),
            permissionType = mode,
            entityId = entity.entityId,
            userId = if (mode == PermissionType.USER) targetId else null,
            roleId = if (mode == PermissionType.ROLE) targetId else null,
            permissions = permissions,
            createdDate = unixNow()
        )

        // Create and add permission
        db_create(permission, action).let {
            if (it.isNotSuccessful) return it.cast()
        }

        pathTree.addPermission(path.path, permission)

        return permission.toResult()
    }

    /**
     * Returns if user has sufficient permissions to edit an entity permission
     */
    private fun Principal.canEditEntityPermission(entityOwnerId: Ulid?): Boolean {
        val hasManageAll = this.hasPermission(SystemPermission.MANAGE_ALL_FILE_PERMISSIONS)
        if (hasManageAll) return true

        return (this.userId == entityOwnerId) && this.hasPermission(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS)
    }

    /**
     * Insert file permission to database
     */
    private fun db_create(permission: EntityPermission, action: UserAction): Result<Unit> {
        try {
            permissionRepository.insert(
                permissionId = permission.permissionId,
                permissionType = permission.permissionType,
                entityId = permission.entityId,
                userId = permission.userId,
                roleId = permission.roleId,
                permissions = permission.permissions.serialize(),
                createdDate = permission.createdDate,
            )
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = action,
                description = "Failed to create a file permission in database.",
                message = e.stackTraceToString(),
                targetId = permission.userId ?: permission.userId,
                meta = meta("permissionType" to permission.permissionType.toString())
            )
            return Result.error("Failed to create file permission.", source = "entityPermService.db_create-exception")
        }
    }

    private fun db_updatePermissions(permissionId: Ulid, newPermissions: List<FilePermission>, action: UserAction): Result<Unit> {
        try {
            permissionRepository.updatePermissionList(permissionId, newPermissions.serialize())
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = action,
                description = "Failed to update permission list of file permission in database.",
                message = e.stackTraceToString(),
                targetId = permissionId
            )
            return Result.error("Failed to save permissions.")
        }
    }

    private fun db_deletePermission(permissionId: Ulid, action: UserAction): Result<Unit> {
        try {
            permissionRepository.deletePermission(permissionId)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = action,
                description = "Failed to delete file permission from database.",
                message = e.stackTraceToString(),
                targetId = permissionId
            )
            return Result.error("Failed to delete permissions.")
        }
    }

    /**
     * Returns list of permissions for an entity along with affected usernames
     */
    fun getEntityPermissions(user: Principal, path: FilePath): Result<EntityPermissionMeta> {
        fileService.isAllowedToAccessFile(user, path).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val ownerId = entityService.getByPath(path = path.path, userAction = UserAction.GET_ENTITY_PERMISSIONS).let {
            if (it.hasError) return it.cast()
            it.valueOrNull
        }?.ownerId

        val isAllowed = user.hasPermission(SystemPermission.MANAGE_ALL_FILE_PERMISSIONS) || user.userId == ownerId && user.hasPermission(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS)
        if (!isAllowed) return Result.reject("You do not have permission to view the permissions of this file.")

        val permissions = pathTree.getAllPermissionsForPath(path.path)

        val result = EntityPermissionMeta(
            ownerId = ownerId,
            permissions = permissions
        )

        return result.toResult()
    }

    /**
     * Remove permission for an entity ID from a specific file path
     */
    fun memory_removeEntity(path: String, entityId: Ulid) = pathTree.removePermissionByEntityId(path, entityId, null)

    /**
     * Update the file path of an entity
     */
    fun memory_updateEntityPath(oldPath: String, newPath: String?, entityId: Ulid) = pathTree.updatePermissionPath(oldPath, newPath, entityId, null)

    /**
     * Get the closest (inherited) file permission for a user.
     */
    fun getUserPermission(filePath: String, isNormalized: Boolean, userId: Ulid, roles: List<Ulid>): EntityPermission? {
        val path = if (isNormalized) filePath else filePath.normalizePath()

        pathTree.getClosestPermissionForUser(path, userId)
            ?.let { return it }

        pathTree.getClosestPermissionForAnyRole(path, roles)
            ?.let { return it }

        return null
    }

    /**
     * Initialize permission tree from database
     */
    fun loadPermissionsFromDatabase(): Boolean {
        println("Loading file permissions from database...")

        val permissions = try {
            permissionRepository.getAll().map { it.toEntityPermission() }
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to load file permissions from database during startup.",
                message = e.stackTraceToString()
            )
            return false
        }

        val entities = hashMapOf<Ulid, FilesystemEntity?>()

        // Load entities
        runCatching {
            val loggedNotFound = Token()
            permissions.forEach {
                entities.computeIfAbsent(it.entityId) { entityId ->
                    val result = entityService.getById(entityId, UserAction.NONE)
                    if (result.hasError) {
                        throw Exception()
                    } else if (result.notFound) {
                        loggedNotFound.consume {
                            println("${Props.appName} has permissions that arent associated with any files.")
                        }
                    }

                    val entity = result.valueOrNull

                    return@computeIfAbsent entity
                }
            }
        }.onFailure { return false }

        // Add permissions to tree
        permissions.forEach { permission ->
            val entity = entities[permission.entityId]
            if (entity == null || entity.path == null) return@forEach
            pathTree.addPermission(entity.path, permission)
        }

        return true
    }

}