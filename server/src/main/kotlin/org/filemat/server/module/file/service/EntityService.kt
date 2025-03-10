package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.repository.EntityRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * Service for file entities in the database
 *
 * Entity is a database entry for a file
 */
@Service
class EntityService(
    private val entityRepository: EntityRepository,
    private val logService: LogService,
    @Lazy private val entityPermissionService: EntityPermissionService,
) {
    fun updateInode(entityId: Ulid, newInode: Long?, userAction: UserAction): Result<Unit> {
        try {
            entityRepository.updateInode(entityId, newInode)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update file path in database.",
                message = e.stackTraceToString(),
                meta = meta("newInode" to "$newInode", "entityId" to "$entityId"),
            )
            return Result.error("Failed to update file path in database.")
        }

        return Result.ok(Unit)
    }

    fun updatePath(entityId: Ulid, newPath: String?, existingEntity: FilesystemEntity?, userAction: UserAction): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updatePath(entityId, newPath)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update file path in database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update file path in database.")
        }

        // Update path of permissions that are tied to this entity ID
        if (entity.path != null) {
            entityPermissionService.updateEntityPath(entity.path, newPath, entity.entityId)
        }
        return Result.ok(Unit)
    }

    fun removeInodeAndPath(entityId: Ulid, existingEntity: FilesystemEntity?, userAction: UserAction): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updateInodeAndPath(entityId, null, null)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to remove file path and inode from database.",
                message = e.stackTraceToString(),
                meta = meta("entityId" to "$entityId")
            )
            return Result.error("Failed to remove file from database.")
        }

        entity.path?.let { path ->
            entityPermissionService.removeEntity(path, entityId)
        }

        return Result.ok(Unit)
    }

    fun getByPath(path: String, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            entityRepository.getByPath(path)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get filesystem entity by path",
                message = e.stackTraceToString()
            )
            Result.error("Failed to get file from database.")
        }
    }

    fun getByInodeWithNullPath(inode: Long, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            entityRepository.getByInodeWithNullPath(inode)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get file by inode from database.",
                message = e.stackTraceToString()
            )
            Result.error("Failed to get file from database.")
        }
    }

    fun getById(entityId: Ulid, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            entityRepository.getById(entityId)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get filesystem entity by ID",
                message = e.stackTraceToString(),
                meta = meta("entityId" to "$entityId")
            )
            Result.error("Failed to get file from database.")
        }
    }

}