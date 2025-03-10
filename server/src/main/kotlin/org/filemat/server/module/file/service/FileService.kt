package org.filemat.server.module.file.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FileUtils
import org.filemat.server.common.util.getFileType
import org.filemat.server.common.util.runIf
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.exists

/**
 * Service to interact with files.
 */
@Service
class FileService(
    private val folderVisibilityService: FolderVisibilityService,
    private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService,
    private val logService: LogService
) {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns list of entries in a folder.
     */
    fun getFolderEntries(folderPath: String, principal: Principal): Result<List<FileMetadata>> {
        val path = folderPath.normalizePath()
        println("Getting $path")

        val isFileAvailable = verifyEntityInode(path, UserAction.READ_FOLDER)
        if (!isFileAvailable) return Result.error("This folder is not available.")

        // Deny blocked folder
        val isAllowed = folderVisibilityService.isPathAllowed(folderPath = path, isNormalized = true)
        if (isAllowed != null) return Result.reject(isAllowed)

        val hasAdminAccess = principal.hasPermission(Permission.ACCESS_ALL_FILES)
        println("has admin $hasAdminAccess")
        // Check permissions
        runIf(!hasAdminAccess) {
            val permissions = entityPermissionService.getUserPermission(filePath = path, isNormalized = true, userId = principal.userId, roles = principal.roles)
                ?: return Result.reject("You do not have permission to open this folder.")

            println("Got permissions")
            if (!permissions.permissions.contains(Permission.READ)) return Result.reject("You do not have permission to open this folder.")
        }
        println("user has sufficient permissoins")

        println("getting folder entries")
        val result = internalGetFolderEntries(path, UserAction.READ_FOLDER)
        println("got entries")
        if (result.notFound) return Result.notFound()
        if (result.hasError) return Result.error(result.error)
        if (result.rejected) return Result.reject(result.error)
        val folderEntries = result.value

        println("Serve folder")
        return folderEntries.toResult()
    }

    /**
     * Directly gets entries from a folder. Is not authenticated.
     */
    private fun internalGetFolderEntries(path: String, userAction: UserAction): Result<List<FileMetadata>> {
        try {
            val file = File(path)
            val filenames = file.listFiles()?.toList()
                ?: return Result.notFound()

            val files = filenames.map {
                getMetadata(it.absolutePath, true)
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
    private fun getMetadata(rawPath: String, isNormalized: Boolean): FileMetadata {
        val normalized = if (isNormalized) rawPath else rawPath.normalizePath()
        val path = Paths.get(normalized)

        val attributes = if (!State.App.followSymLinks) {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } else {
            val realPath = path.toRealPath()
            Files.readAttributes(realPath, BasicFileAttributes::class.java)
        }

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
    fun verifyEntityInode(filePath: String, userAction: UserAction): Boolean {
        val lock = verifyLocks.computeIfAbsent(filePath) { ReentrantLock() }
        lock.lock()
        return try {
            val entityR = entityService.getByPath(filePath, UserAction.NONE)
            if (entityR.notFound) return true
            if (entityR.isNotSuccessful) return false
            val entity = entityR.value

            // Only check path of unsupported filesystem
            if (!entity.isFilesystemSupported || entity.inode == null) {
                val path = Paths.get(filePath)
                val exists = if (State.App.followSymLinks) path.exists()
                else path.exists(LinkOption.NOFOLLOW_LINKS)
                return exists
            }

            val newInode = FileUtils.getInode(filePath)
            if (entity.inode == newInode) return true

            // Check if this inode was already in the database
            if (newInode != null) {
                val existingEntityR = entityService.getByInodeWithNullPath(newInode, userAction)
                // Dangling entity exists with this inode.
                // Associate this path to it.
                if (existingEntityR.isSuccessful) {
                    val existingEntity = existingEntityR.value
                    entityService.updatePath(existingEntity.entityId, filePath, existingEntity, userAction)
                }
            }

            // Path has unexpected Inode, so remove the path from the entity in database.
            entityService.updatePath(
                entityId = entity.entityId,
                newPath = null,
                existingEntity = entity,
                userAction = userAction,
            )

            true
        } finally {
            lock.unlock()
            verifyLocks.remove(filePath, lock)
        }
    }


}