package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.repository.EntityRepository
import org.filemat.server.module.file.service.file.component.FileSecurityService
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
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
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
    @Lazy private val fileShareService: FileShareService,
    @Lazy private val fileSecurityService: FileSecurityService,
) {
    private val transactionTemplate = TransactionTemplate(platformTransactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    private val entityMap = ConcurrentHashMap<Ulid, FilesystemEntity>()
    // Path key to entity ID
    private val pathMap = ConcurrentHashMap<String, Ulid>()
    private val mapLock = ReentrantReadWriteLock()
    private val repairLock = ReentrantLock()

    fun <T> withEntityRepairLock(block: () -> T): T = repairLock.withLock(block)

    fun map_put(entity: FilesystemEntity, lock: Boolean = true) {
        val action = {
            entity.pathKey?.let { key ->
                val currentOwnerId = pathMap[key]
                check(currentOwnerId == null || currentOwnerId == entity.entityId) {
                    "Path cache collision: '$key' is already owned by $currentOwnerId"
                }
            }

            val previousEntity = entityMap.put(entity.entityId, entity)
            if (previousEntity != null) {
                previousEntity.pathKey?.let { pathMap.remove(it, previousEntity.entityId) }
            }

            if (entity.pathKey != null) {
                pathMap.put(entity.pathKey, entity.entityId)
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
            entity.pathKey?.let { pathMap.remove(it) }
        }
    }

    fun clearOwnerFromCache(ownedEntities: List<FilesystemEntity>) {
        ownedEntities.forEach { entity ->
            map_put(entity.copy(ownerId = null))
        }
    }

    fun getByOwnerUserId(userId: Ulid): List<FilesystemEntity> = entityRepository.getByOwnerUserId(userId)
    fun map_getByPath(pathKey: String): FilesystemEntity? {
        mapLock.read {
            return pathMap[pathKey]?.let { entityId ->
                entityMap[entityId]
            }
        }
    }

    fun getEntityIdByPath(path: FilePath, action: UserAction): Result<Ulid> {
        pathMap[path.pathKey]?.let { return it.toResult() }
        getByPath(path.pathKey, action).let {
            if (it.isNotSuccessful) return it.cast()
            return it.value.entityId.toResult()
        }
    }

    fun delete(entity: FilesystemEntity, userAction: UserAction): Result<Unit> {
        return try {
            entityRepository.delete(entity.entityId)
            map_remove(entity.entityId)

            if (entity.pathKey != null) {
                entityPermissionService.memory_removeEntity(entity.pathKey, entity.entityId)
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
        val sourceEntity = getByPath(canonicalPath.pathKey, userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        fileSecurityService.ensureEntityIndexed(
            path = canonicalDestinationPath,
            ownerId = sourceEntity.ownerId,
            userAction = userAction,
            reusePathEntity = false,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val newEntity = getByPath(canonicalDestinationPath.pathKey, userAction).let {
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
        var isFilesystemSupported = filesystemService.isSupportedFilesystem(canonicalPath)
            ?: return Result.notFound()

        var fileKey = if (isFilesystemSupported) {
            // The key of the file or link itself, because permissions are path-based
            filesystemService.getFileKey(canonicalPath.path)
                ?: return Result.notFound()
        } else null

        // Hard links share a key, which stays with the first path. The other paths follow their path only.
        if (fileKey != null) {
            val keyEntity = getByFileKey(fileKey, userAction)
            if (keyEntity.hasError) return Result.error("Failed to check if this file has been indexed yet.")
            if (keyEntity.isSuccessful) {
                fileKey = null
                isFilesystemSupported = false
            }
        }

        val existingEntity = getByPath(canonicalPath.pathKey, userAction)
        if (existingEntity.notFound == false) return Result.reject("A file with this path has already been indexed.")
        if (existingEntity.notFound != true && existingEntity.isNotSuccessful) return Result.error("Failed to check if this file has been indexed yet.")

        val entity = FilesystemEntity(
            entityId = UlidCreator.getUlid(),
            path = canonicalPath.pathString,
            fileKey = fileKey,
            isFilesystemSupported = isFilesystemSupported,
            ownerId = ownerId,
            pathKey = canonicalPath.pathKey,
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
                pathKey = entity.pathKey,
                fileKey = entity.fileKey,
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
        // Keys are equal for a case-only rename in a case-insensitive folder, which is allowed
        if (newPath != null && (path == newPath || (newPath.pathKey != path.pathKey && PathPolicy.isKeyWithin(newPath.pathKey, path.pathKey)))) {
            return Result.error("Cannot move a folder into itself.")
        }

        // Do all operations in a DB transaction
        return runCatching {
            transactionTemplate.execute<Result<Unit>> { status ->
                // Get entities that start with the current path
                val entities = getAllByPathPrefix(path.pathKey, userAction).let {
                    if (it.isNotSuccessful) return@execute it.cast()
                    it.value
                }.sortedBy { it.pathKey?.length }
                val depth = PathPolicy.segments(path.pathKey).size

                // move entities
                data class NewPair(val entity: FilesystemEntity, val newEntity: FilesystemEntity)
                val newEntities: List<NewPair> = entities.map { entity: FilesystemEntity ->
                    // Names below the moved file keep their case and their key, because their folders move with them
                    val suffix = PathPolicy.segments(entity.path.orEmpty()).drop(depth)
                    val keySuffix = PathPolicy.segments(entity.pathKey.orEmpty()).drop(depth)
                    val newEntityPath = newPath?.let { PathPolicy.join(PathPolicy.segments(it.pathString) + suffix) }
                    val newEntityKey = newPath?.let { PathPolicy.join(PathPolicy.segments(it.pathKey) + keySuffix) }

                    updatePath(entity.entityId, newEntityPath, entity, userAction, updateEntityMap = false, newPathKey = newEntityKey).let {
                        if (it.isNotSuccessful) {
                            status.setRollbackOnly()
                            return@execute it
                        }
                    }

                    val movedEntity = entity.copy(path = newEntityPath, pathKey = newEntityKey)
                    val newEntity = if (newEntityPath == null) movedEntity else {
                        db_refreshFileKey(movedEntity, newEntityPath, userAction).let {
                            if (it.isNotSuccessful) {
                                status.setRollbackOnly()
                                return@execute it.cast()
                            }
                            it.value
                        }
                    }

                    return@map NewPair(entity = entity, newEntity = newEntity)
                }

                // after all entities moved successfully, change the paths in memory cache
                TransactionSynchronizationManager
                    .registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            mapLock.write {
                                newEntities.forEach { entity: NewPair ->
                                    map_put(entity = entity.newEntity, lock = false)
                                    updatePermissionPath(entity = entity.entity, newPathKey = entity.newEntity.pathKey)
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

    /**
     * Reads the file key again after Filemat moved a file, because a move to another volume gives the file a new key (§3.4).
     * Keeps the stored key when the file can't be read.
     */
    private fun db_refreshFileKey(entity: FilesystemEntity, apiPath: String, userAction: UserAction): Result<FilesystemEntity> {
        val path = runCatching { PathPolicy.parse(apiPath) }.getOrNull() ?: return entity.toResult()
        var isSupported = filesystemService.isSupportedFilesystem(FilePath.ofAlreadyNormalized(path)) ?: return entity.toResult()
        var fileKey = if (isSupported) filesystemService.getFileKey(path) ?: return entity.toResult() else null

        try {
            // Another path of a hard-linked file keeps the key
            val keyEntity = fileKey?.let { entityRepository.getByFileKey(it) }
            if (keyEntity != null && keyEntity.entityId != entity.entityId) {
                fileKey = null
                isSupported = false
            }
            if (fileKey == entity.fileKey && isSupported == entity.isFilesystemSupported) return entity.toResult()

            entityRepository.updateFileKey(entity.entityId, fileKey, isSupported)
            return entity.copy(fileKey = fileKey, isFilesystemSupported = isSupported).toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update file key in database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update file key in database.")
        }
    }

    fun getAllByPathPrefix(prefixKey: String, userAction: UserAction): Result<List<FilesystemEntity>> {
        try {
            return entityRepository.getAllByPathPrefix(prefixKey).toResult()
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

    fun updatePath(
        entityId: Ulid,
        newPath: String?,
        existingEntity: FilesystemEntity?,
        userAction: UserAction,
        updateEntityMap: Boolean = true,
        newPathKey: String? = newPath?.let { PathPolicy.pathKey(it) },
    ): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updatePath(entityId, newPath, newPathKey)

            val newEntity = entity.copy(path = newPath, pathKey = newPathKey)
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
        if (updateEntityMap) updatePermissionPath(entity, newPathKey)

        return Result.ok(Unit)
    }

    /**
     * Sets the file key of an entity. [isFilesystemSupported] null keeps the stored value.
     */
    fun updateFileKey(
        entityId: Ulid,
        newFileKey: String?,
        existingEntity: FilesystemEntity?,
        userAction: UserAction,
        isFilesystemSupported: Boolean? = null,
    ): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            val supported = isFilesystemSupported ?: entity.isFilesystemSupported
            entityRepository.updateFileKey(entityId, newFileKey, supported)
            map_put(entity.copy(fileKey = newFileKey, isFilesystemSupported = supported))
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update file key in database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update file key in database.")
        }

        return Result.ok(Unit)
    }

    fun updateOwner(entityId: Ulid, ownerId: Ulid?, existingEntity: FilesystemEntity?, userAction: UserAction): Result<Unit> {
        val entity = existingEntity ?: let {
            val entityR = getById(entityId, userAction)
            if (entityR.isNotSuccessful) return Result.error(entityR.error)
            entityR.value
        }

        try {
            entityRepository.updateOwner(entityId, ownerId)
            map_put(entity.copy(ownerId = ownerId))
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update file entity owner in database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update file owner in database.")
        }

        return Result.ok(Unit)
    }

    private fun updatePermissionPath(entity: FilesystemEntity, newPathKey: String?) {
        if (entity.pathKey != null) {
            if (newPathKey != null) {
                entityPermissionService.memory_movePath(entity.pathKey, newPathKey, entity.entityId)
            } else {
                entityPermissionService.memory_removeEntity(entity.pathKey, entity.entityId)
            }
        }
    }

    /**
     * Returns the entity of a path key (`FilePath.pathKey`)
     */
    fun getByPath(pathKey: String, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            // Get from cache
            map_getByPath(pathKey)?.let { return it.toResult() }

            // Get from DB
            val result = entityRepository.getByPathKey(pathKey) ?: return Result.notFound()
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

    fun getByFileKey(fileKey: String, userAction: UserAction): Result<FilesystemEntity> {
        return try {
            entityRepository.getByFileKey(fileKey)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get file by file key from database.",
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