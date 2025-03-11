package org.filemat.server.module.permission.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.classes.Token
import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.EntityPermission
import org.filemat.server.module.permission.model.EntityPermissionTree
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
) {

    /**
     * Holds user and role permissions for file paths in a tree.
     */
    private val pathTree = EntityPermissionTree()


    /**
     * Remove permission for an entity ID from a specific file path
     */
    fun removeEntity(path: String, entityId: Ulid) = pathTree.removePermissionByEntityId(path, entityId, null)

    /**
     * Update the file path of an entity
     */
    fun updateEntityPath(oldPath: String, newPath: String?, entityId: Ulid) = pathTree.updatePermissionPath(oldPath, newPath, entityId, null)

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