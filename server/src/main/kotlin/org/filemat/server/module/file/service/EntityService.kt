package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.getAll
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.repository.EntityRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
    private val filesystemService: FilesystemService,
    private val transactionTemplate: TransactionTemplate,
) {

    private val entityMap = ConcurrentHashMap<Ulid, FilesystemEntity>()
    private val pathMap = ConcurrentHashMap<String, Ulid>()
    private val mapLock = ReentrantReadWriteLock()

    fun map_put(entity: FilesystemEntity) {
        mapLock.write {
            val previousEntity = entityMap.put(entity.entityId, entity)
            if (previousEntity != null) {
                previousEntity.path?.let { pathMap.remove(it) }
            }

            if (entity.path != null) {
                pathMap.put(entity.path, entity.entityId)
            }
        }
    }
    fun map_remove(entityId: Ulid) {
        mapLock.write {
            val entity = entityMap.remove(entityId) ?: return
            entity.path?.let { pathMap.remove(it) }
        }
    }
    fun map_getByPath(path: String): FilesystemEntity? {
        mapLock.read {
            return pathMap[path]?.let { entityId ->
                entityMap[entityId]
            }
        }
    }
    fun map_getByPathPrefix(prefix: String): List<FilesystemEntity> {
        mapLock.read {
            val ids = pathMap.filterKeys { it.startsWith(prefix) }.values
            return entityMap.getAll(ids)
        }
    }

    fun delete(entityId: Ulid, userAction: UserAction): Result<Unit> {
        return try {
            entityRepository.delete(entityId)
            map_remove(entityId)
            Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to delete file entity from database",
                message = e.stackTraceToString(),
                targetId = entityId
            )
            Result.error("Failed to delete file from database.")
        }
    }

    fun create(canonicalPath: FilePath, ownerId: Ulid, userAction: UserAction, followSymLinks: Boolean): Result<FilesystemEntity> {
        val isFilesystemSupported = filesystemService.isSupportedFilesystem(canonicalPath)
            ?: return Result.notFound()

        val inode = if (isFilesystemSupported == true) {
            // Get the Inode of file or symlink due to path-based permissions
            filesystemService.getInode(canonicalPath.path, false)
                ?: return Result.notFound()
        } else null

        val existingEntity = getByPath(canonicalPath.pathString, userAction)
        if (existingEntity.notFound == false) return Result.reject("A file with this path has already been indexed.")
        if (existingEntity.notFound != true && existingEntity.isNotSuccessful) return Result.error("Failed to check if this file has been indexed yet.")

        val entity = FilesystemEntity(
            entityId = UlidCreator.getUlid(),
            path = canonicalPath.pathString,
            inode = inode,
            isFilesystemSupported = isFilesystemSupported,
            ownerId = ownerId,
            followSymlinks = followSymLinks
        )

        db_create(entity, userAction).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return entity.toResult()
    }

    private fun db_create(entity: FilesystemEntity, userAction: UserAction): Result<Unit> {
        try {
            entityRepository.insert(
                entityId = entity.entityId,
                path = entity.path,
                inode = entity.inode,
                isFilesystemSupported = entity.isFilesystemSupported,
                ownerId = entity.ownerId,
                followSymlinks = entity.followSymlinks,
            )
            map_put(entity)

            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to insert file entity to database.",
                message = e.stackTraceToString(),
                meta = meta("ownerId" to entity.ownerId.toString()),
            )
            return Result.error("Failed to index file.")
        }
    }

    /**
     * Moves an entity and all children (by path)
     */
    fun move(
        entityId: Ulid,
        newPath: String?,
        existingEntity: FilesystemEntity?,
        userAction: UserAction
    ): Result<Unit> {
        val rootEntity = existingEntity ?: run {
            val r = getById(entityId, userAction)
            if (r.isNotSuccessful) return Result.error(r.error)
            r.value
        }
        val oldBase = rootEntity.path ?: return Result.reject("null path")
        val children = map_getByPathPrefix(oldBase)

        val result: Result<Unit> = transactionTemplate.execute<Result<Unit>> { status ->
            // move root
            updatePath(rootEntity.entityId, newPath, rootEntity, userAction).let {
                if (it.isNotSuccessful) return@execute it
            }

            // move children
            children.forEach { child ->
                val suffix = child.path!!
                    .removePrefix(oldBase)
                    .trimStart('/')

                val childNewPath = newPath?.let { "$it/$suffix" }

                updatePath(child.entityId, childNewPath, child, userAction).let {
                    if (it.isNotSuccessful) {
                        status.setRollbackOnly()
                        return@execute it
                    }
                }
            }

            Result.ok()
        }!!

        return result
    }

    fun updatePath(entityId: Ulid, newPath: String?, existingEntity: FilesystemEntity?, userAction: UserAction): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updatePath(entityId, newPath)

            val newEntity = entity.copy(path = newPath)
            map_put(newEntity)
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
            if (newPath != null) {
                entityPermissionService.memory_movePath(entity.path, newPath, entity.entityId)
            } else {
                entityPermissionService.memory_removeEntity(entity.path, entity.entityId)
            }
        }
        return Result.ok(Unit)
    }

    fun getByPath(path: String, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            // Get from cache
            map_getByPath(path)?.let { return it.toResult() }

            // Get from DB
            val result = entityRepository.getByPath(path) ?: return Result.notFound()
            map_put(result)

            return result.toResult()
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
            entityMap[entityId]?.let { return it.toResult() }

            val result = entityRepository.getById(entityId) ?: return Result.notFound()
            map_put(result)

            return result.toResult()
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