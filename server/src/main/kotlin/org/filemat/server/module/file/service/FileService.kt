package org.filemat.server.module.file.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.getFileType
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock


/**
 * Service to interact with files.
 */
@Service
class FileService(
    private val folderVisibilityService: FolderVisibilityService,
    private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService,
    private val logService: LogService,
    private val filesystem: FilesystemService,
) {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun getFolderEntries(folderPath: String, principal: Principal): Result<List<FileMetadata>> {
        val path = folderPath.normalizePath()

        // Deny blocked folder
        val isAllowedResult = isPathAllowed(path)
        if (isAllowedResult.isNotSuccessful) return isAllowedResult.cast()

        // Verify the file location and handle conflicts
        val isFileAvailable = verifyEntityInode(path, UserAction.READ_FOLDER)
        if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

        // Check if user can read any files
        val hasAdminAccess = principal.hasPermission(SystemPermission.ACCESS_ALL_FILES)
        val permissionResult = hasReadPermission(path = path, principal = principal, hasAdminAccess = hasAdminAccess)
        if (permissionResult.isNotSuccessful) return permissionResult.cast()

        // Get folder entries
        val result = internalGetFolderEntries(path, UserAction.READ_FOLDER)
        if (result.isNotSuccessful) return result.cast()
        val unfilteredFolderEntries = result.value

        // Check permissions for folder entries
        val folderEntries = if (hasAdminAccess) {
            unfilteredFolderEntries
        } else {
            filterPermittedFiles(unfilteredFolderEntries, folderPath, principal)
        }

        return folderEntries.toResult()
    }

    /**
     * Filters list of files for which user has read permission
     */
    fun filterPermittedFiles(list: List<FileMetadata>, folderPath: String, principal: Principal): List<FileMetadata> {
        return list.filter { meta ->
            val permission = entityPermissionService.getUserPermission(filePath = "$folderPath/${meta.filename}", isNormalized = true, userId = principal.userId, roles = principal.roles)
            return@filter permission != null && permission.permissions.contains(FilePermission.READ)
        }
    }

    /**
     * Returns whether user has sufficient read permission for path.
     */
    fun hasReadPermission(path: String, principal: Principal, hasAdminAccess: Boolean): Result<Unit> {
        if (!hasAdminAccess) {
            val permissions = entityPermissionService.getUserPermission(filePath = path, isNormalized = true, userId = principal.userId, roles = principal.roles)
                ?: return Result.reject("You do not have permission to open this folder.")

            if (!permissions.permissions.contains(FilePermission.READ)) return Result.reject("You do not have permission to open this folder.")
        }

        return Result.ok(Unit)
    }

    /**
     * Returns null if path is allowed. Otherwise returns string error.
     */
    fun isPathAllowed(path: String): Result<Unit> {
        val result = folderVisibilityService.isPathAllowed(folderPath = path, isNormalized = true)
        return if (result == null) Result.ok() else Result.reject(result)
    }

    /**
     * Directly gets entries from a folder. Is not authenticated.
     */
    private fun internalGetFolderEntries(path: String, userAction: UserAction): Result<List<FileMetadata>> {
        try {
            val file = File(path)
            val filenames = filesystem.listFiles(file)
                ?: return Result.notFound()

            val files = filenames.map {
                getMetadata(it.absolutePath, true) ?: throw IllegalStateException("Metadata object was null for a known file.")
            }

            return files.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get folder entries with metadata.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to get contents of folder.")
        }
    }

    /**
     * Gets metadata for a file.
     */
    private fun getMetadata(rawPath: String, isNormalized: Boolean): FileMetadata? {
        val normalized = if (isNormalized) rawPath else rawPath.normalizePath()
        val path = Paths.get(normalized)

        val attributes = filesystem.readAttributes(path, State.App.followSymLinks)
            ?: return null

        val type = attributes.getFileType()
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            filename = path.fileName.toString(),
            modificationTime = modificationTime,
            creationTime = creationTime,
            fileType = type,
            size = attributes.size(),
        )
    }


    private val verifyLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Verifies whether a file exists.
     *
     * Handles file conflicts like moved files. Can reassign inode or path of an entity.
     */
    fun verifyEntityInode(filePath: String, userAction: UserAction): Result<Unit> {
        val lock = verifyLocks.computeIfAbsent(filePath) { ReentrantLock() }
        lock.lock()
        try {
            val entityR = entityService.getByPath(filePath, UserAction.NONE)
            if (entityR.notFound) return Result.ok()
            if (entityR.isNotSuccessful) return entityR.cast()
            val entity = entityR.value

            // Do not do inode check on unsupported filesystem.
            if (!entity.isFilesystemSupported || entity.inode == null) {
                val path = Paths.get(filePath)
                val exists = filesystem.exists(path, State.App.followSymLinks)
                return if (exists) Result.ok() else Result.reject("Path does not exist.")
            }

            val newInode = filesystem.getInode(filePath)
            if (entity.inode == newInode) return Result.ok()

            // Handle if a file with a different inode exists on the path
            if (newInode != null) {
                val existingEntityR = entityService.getByInodeWithNullPath(newInode, userAction)

                // Check if this inode was already in the database
                if (existingEntityR.isSuccessful) {
                    // Dangling entity exists with this inode.
                    // Associate this path to it.
                    val existingEntity = existingEntityR.value
                    entityService.updatePath(existingEntity.entityId, filePath, existingEntity, userAction)
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
            verifyLocks.remove(filePath, lock)
        }
    }
}