package org.filemat.server.module.file.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasAnyPermission
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.absolutePathString


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

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns an input stream for the content of a file
     */
    fun getFileContent(user: Principal, rawPath: FilePath): Result<FileInputStream> {
        isAllowedToAccessFile(user, rawPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val path = try {
            if (State.App.followSymLinks) rawPath.pathObject.toRealPath() else rawPath.pathObject.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return Result.notFound()
        } catch (e: Exception) {
            return Result.error("Failed to load file.")
        }

        val file = File(path.absolutePathString())
        if (!file.isFile) return Result.notFound()
        return runCatching { file.inputStream().toResult() }.getOrElse { Result.error("Failed to stream file.") }
    }

    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, path: FilePath): Result<Pair<FileMetadata, List<FileMetadata>?>>{
        val metadata = getMetadata(user, path).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val type = metadata.fileType

        if (type == FileType.FOLDER || type == FileType.FOLDER_LINK && State.App.followSymLinks) {
            val entries = getFolderEntries(user = user, path).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

            return Result.ok(metadata to entries)
        } else if (type == FileType.FILE || type == FileType.FILE_LINK && State.App.followSymLinks) {
            return Result.ok(metadata to null)
        } else {
            return Result.error("Requested path is not a file or folder.")
        }
    }

    /**
     * Returns file metadata. Authenticates user
     */
    fun getMetadata(user: Principal, path: FilePath): Result<FileMetadata> {
        isAllowedToAccessFile(user, path).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystem.getMetadata(path)?.toResult()
            ?: Result.notFound()
    }

    fun isAllowedToAccessFile(user: Principal, pathObject: FilePath, hasAdminAccess: Boolean? = null): Result<Unit> {
        val path = pathObject.path

        // Deny blocked folder
        val isAllowedResult = isPathAllowed(path)
        if (isAllowedResult.isNotSuccessful) return isAllowedResult.cast()

        // Verify the file location and handle conflicts
        val isFileAvailable = verifyEntityInode(path, UserAction.READ_FOLDER)
        if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

        // Check if user can read any files
        val isAdmin = hasAdminAccess ?: hasAdminAccess(user)
        val permissionResult = hasReadPermission(path = path, principal = user, hasAdminAccess = isAdmin)
        if (permissionResult.isNotSuccessful) return permissionResult.cast()

        return Result.ok()
    }

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun getFolderEntries(user: Principal, path: FilePath): Result<List<FileMetadata>> {
        val hasAdminAccess = hasAdminAccess(user)

        val isAllowed = isAllowedToAccessFile(user, path, hasAdminAccess = hasAdminAccess)
        if (isAllowed.isNotSuccessful) return isAllowed.cast()

        // Get folder entries
        val result = internalGetFolderEntries(path, UserAction.READ_FOLDER)
        if (result.isNotSuccessful) return result.cast()
        val allEntries = result.value.filter { folderVisibilityService.isPathAllowed(it.filename) == null }

        // Check permissions for folder entries
        val folderEntries = if (hasAdminAccess) {
            allEntries
        } else {
            filterPermittedFiles(allEntries, path, user)
        }

        return folderEntries.toResult()
    }

    fun hasAdminAccess(user: Principal): Boolean = user.hasAnyPermission(listOf(SystemPermission.ACCESS_ALL_FILES, SystemPermission.SUPER_ADMIN))

    /**
     * Filters list of files for which user has read permission
     */
    fun filterPermittedFiles(list: List<FileMetadata>, folderPath: FilePath, principal: Principal): List<FileMetadata> {
        return list.filter { meta ->
            val permission = entityPermissionService.getUserPermission(filePath = "${folderPath.path}/${meta.filename}", isNormalized = true, userId = principal.userId, roles = principal.roles)
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
    private fun internalGetFolderEntries(path: FilePath, userAction: UserAction): Result<List<FileMetadata>> {
        try {
            val file = File(path.path)
            val filenames = filesystem.listFiles(file)
                ?: return Result.notFound()

            val files = filenames.map {
                filesystem.getMetadata(FilePath(it.absolutePath)) ?: throw IllegalStateException("Metadata object was null for a known file.")
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