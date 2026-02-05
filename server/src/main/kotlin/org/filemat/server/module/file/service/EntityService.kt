package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.repository.EntityRepository
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.sharedFile.service.FileShareService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.pathString

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
    @Lazy private val filesystemService: FilesystemService,
    platformTransactionManager: PlatformTransactionManager,
    @Lazy private val fileShareService: FileShareService
) {
    private val transactionTemplate = TransactionTemplate(platformTransactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    private val entityMap = ConcurrentHashMap<Ulid, FilesystemEntity>()
    private val pathMap = ConcurrentHashMap<String, Ulid>()
    private val mapLock = ReentrantReadWriteLock()

    fun map_put(entity: FilesystemEntity, lock: Boolean = true) {
        val action = {
            entity.path?.let { path ->
                val currentOwnerId = pathMap[path]
                check(currentOwnerId == null || currentOwnerId == entity.entityId) {
                    "Path cache collision: '$path' is already owned by $currentOwnerId"
                }
            }

            val previousEntity = entityMap.put(entity.entityId, entity)
            if (previousEntity != null) {
                previousEntity.path?.let { pathMap.remove(it, previousEntity.entityId) }
            }

            if (entity.path != null) {
                pathMap.put(entity.path, entity.entityId)
            }
        }

        if (lock) {
            mapLock.write {
                action()
            }
        } else {
            action()
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

    fun getEntityIdByPath(path: FilePath, action: UserAction): Result<Ulid> {
        pathMap[path.pathString]?.let { return it.toResult() }
        getByPath(path.pathString, action).let {
            if (it.isNotSuccessful) return it.cast()
            return it.value.entityId.toResult()
        }
    }

    fun delete(entity: FilesystemEntity, userAction: UserAction): Result<Unit> {
        return try {
            entityRepository.delete(entity.entityId)
            map_remove(entity.entityId)

            if (entity.path != null) {
                entityPermissionService.memory_removeEntity(entity.path, entity.entityId)
            }

            Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to delete file entity from database",
                message = e.stackTraceToString(),
                targetId = entity.entityId
            )
            Result.error("Failed to delete file from database.")
        }
    }

    fun duplicateEntity(canonicalPath: FilePath, canonicalDestinationPath: FilePath, userAction: UserAction = UserAction.DUPLICATE_ENTITY): Result<FilesystemEntity> {
        val sourceEntity = getByPath(canonicalPath.pathString, userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        val newEntity = create(canonicalPath = canonicalDestinationPath, ownerId = sourceEntity.ownerId, userAction = userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        val permissions = entityPermissionService.getAllEntityPermissions(canonicalPath)

        permissions.forEach {
            entityPermissionService.createPermission(
                user = null,
                rawPath = canonicalDestinationPath,
                targetId = it.userId ?: it.roleId ?: throw IllegalStateException("UserID and RoleID are null for file permission."),
                mode = it.permissionType,
                permissions = it.permissions,
                existingCanonicalPath = canonicalDestinationPath,
                userId = sourceEntity.ownerId,
                ignorePermissions = true,
            )
        }

        return Result.ok(newEntity)
    }

    fun create(canonicalPath: FilePath, ownerId: Ulid?, userAction: UserAction): Result<FilesystemEntity> {
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
        path: FilePath,
        newPath: FilePath?,
        userAction: UserAction
    ): Result<Unit> {
        val oldBase = path.path

        if (newPath != null && (path == newPath || newPath.startsWith(path))) return Result.error("Cannot move a folder into itself.")

        // Do all operations in a DB transaction
        return runCatching {
            transactionTemplate.execute<Result<Unit>> { status ->
                // Get entities that start with the current path
                val entities = getAllByPathPrefix(oldBase.pathString, userAction).let {
                    if (it.isNotSuccessful) return@execute it.cast()
                    it.value
                }.sortedBy { it.path?.length }

                // move entities
                data class NewPair(val entity: FilesystemEntity, val newPath: String?)
                val newEntities: List<NewPair> = entities.map { entity: FilesystemEntity ->
                    val suffix = entity.path!!
                        .removePrefix(oldBase.pathString)
                        .trimStart('/')

                    val newEntityPath = newPath?.let { "$it/$suffix" }?.removeSuffix("/")

                    updatePath(entity.entityId, newEntityPath, entity, userAction, updateEntityMap = false).let {
                        if (it.isNotSuccessful) {
                            status.setRollbackOnly()
                            return@execute it
                        }
                    }

                    return@map NewPair(entity = entity, newPath = newEntityPath)
                }

                // after all entities moved successfully, change the paths in memory cache
                TransactionSynchronizationManager
                    .registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            mapLock.write {
                                newEntities.forEach { entity: NewPair ->
                                    val newEntity = entity.entity.copy(path = entity.newPath)

                                    map_put(entity = newEntity, lock = false)
                                    updatePermissionPath(entity = entity.entity, newPath = entity.newPath)
                                }
                            }
                        }
                    })
                Result.ok()
            }!!
        }.getOrElse {
            Result.error("Failed to move file.")
        }
    }

    fun getAllByPathPrefix(prefix: String, userAction: UserAction): Result<List<FilesystemEntity>> {
        try {
            return entityRepository.getAllByPathPrefix(prefix).toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get list of entities by path prefix from database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load files.")
        }
    }

    fun updatePath(entityId: Ulid, newPath: String?, existingEntity: FilesystemEntity?, userAction: UserAction, updateEntityMap: Boolean = true): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updatePath(entityId, newPath)

            val newEntity = entity.copy(path = newPath)
            if (updateEntityMap) map_put(newEntity)
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
        if (updateEntityMap) updatePermissionPath(entity, newPath)

        return Result.ok(Unit)
    }

    private fun updatePermissionPath(entity: FilesystemEntity, newPath: String?) {
        if (entity.path != null) {
            if (newPath != null) {
                entityPermissionService.memory_movePath(entity.path, newPath, entity.entityId)
            } else {
                entityPermissionService.memory_removeEntity(entity.path, entity.entityId)
            }
        }
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

    fun getByShareId(shareId: String, userAction: UserAction = UserAction.GET_SHARED_FILE): Result<FilesystemEntity> {
        val share = fileShareService.getSharesByShareId(shareId, userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        return getById(share.fileId, userAction)
    }

    fun getByShareToken(shareToken: String, userAction: UserAction = UserAction.GET_SHARED_FILE): Result<FilesystemEntity> {
        val share = fileShareService.getByShareToken(shareToken, userAction).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        return getById(share.fileId, userAction)
    }

    fun getByInode(inode: Long, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            entityRepository.getByInode(inode)?.toResult() ?: Result.notFound()
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