package org.filemat.server.module.file.service

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.desair.tus.server.upload.UploadInfo
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.*
import org.filemat.server.common.util.classes.RequestPathOverrideWrapper
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasAnyPermission
import org.filemat.server.module.file.model.*
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock


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

    fun createFolder(user: Principal, rawPath: FilePath): Result<Unit> {
        val (pathResult, pathHasSymlink) = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        TODO()
        isAllowedToAccessFile(user = user, canonicalPath = canonicalPath).let {

        }

    }

    /**
     * Uses TUS to handle a file upload request
     */
    fun handleTusUpload(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val tusService = filesystem.tusFileService
        if (tusService == null) {
            response.writer.write("File upload service is not running.")
            response.status = 500
            return
        }

        val user = request.getPrincipal()!!

        // Check file permissions
        if (request.method == "POST") {
            val rawMeta: String? = request.getHeader("Upload-Metadata")
            if (rawMeta == null) {
                response.writer.write("Invalid upload metadata")
                response.status = 400
                return
            }
            val meta = parseTusHttpHeader(rawMeta)

            // Authenticate destination path
            val rawFilename = meta["filename"]?.toFilePath()
            if (rawFilename == null) {
                response.writer.write("Invalid filename")
                response.status = 400
                return
            }
            val rawDestinationPath = rawFilename.path.parent.toString()
            val canonicalDestinationPath = resolvePath(FilePath.of(rawDestinationPath)).let { (result, hasSymlink) ->
                if (result.notFound) {
                    response.writer.write("The target folder does not exist.")
                    response.status = 400
                    return
                } else if (result.isNotSuccessful) {
                    response.writer.write("Failed to save the uploaded file.")
                    response.status = 500
                }
                result.value
            }

            val isAllowed = isAllowedToAccessFile(user = user, canonicalPath = canonicalDestinationPath)
            if (isAllowed.isNotSuccessful) {
                response.writer.write(isAllowed.errorOrNull ?: "You do not have permission to access this folder.")
                response.status = 400
                return
            }
        }

        // Wrap request to change the path, so that TUS receives the api prefix
        val wrappedRequest = RequestPathOverrideWrapper(request, "/api${request.requestURI}")
        // Make TUS handle uploads
        tusService.process(wrappedRequest, response)

        // Required to commit response headers/status
        if (!response.isCommitted) {
            response.flushBuffer()
        }

        if (request.method == "PATCH") {
            val info: UploadInfo? = tusService.getUploadInfo(wrappedRequest.requestURI)
            if (info != null && !info.isUploadInProgress) {
                val isUploaded = info.length == info.offset

                // Handle when the file was successfully uploaded
                if (isUploaded) {
                    // Move the file from the uploads folder to the target destination
                    val result = run<Result<Unit>> {
                        val sourceFolder = "${State.App.uploadFolderPath}/uploads/${info.id}"

                        val dataSource = "$sourceFolder/data".toFilePath()
                        val rawDataDestination = info.metadata["filename"]?.toFilePath() ?: return@run Result.error("Destination filename is not in upload metadata.")
                        val dataDestination = resolvePath(rawDataDestination).let { (result, hadSymlink) ->
                            if (result.isNotSuccessful) return@run result.cast();
                            result.value
                        }

                        // Move the file to the target folder
                        val fileMoved = filesystem.moveFile(source = dataSource, destination = dataDestination, overwriteDestination = false)
                        if (fileMoved.isNotSuccessful) return@run Result.error("Failed to move file from uploads folder.")

                        // Delete the TUS upload folder
                        filesystem.deleteFile(sourceFolder.toFilePath(), recursive = true)

                        // Create an entity
                        entityService.create(
                            canonicalPath = dataDestination,
                            ownerId = user.userId,
                            userAction = UserAction.UPLOAD_FILE,
                            followSymLinks = State.App.followSymLinks
                        )

                        Result.ok()
                    }
                }
            }
        }
    }

    fun deleteFile(user: Principal, path: FilePath): Result<Unit> {

        return TODO()
    }

    /**
     * Returns an input stream for the content of a file
     */
    fun getFileContent(user: Principal, rawPath: FilePath): Result<InputStream> {
        val (pathResult, pathContainsSymlink) = resolvePath(rawPath)

        // Return content of symlink file itself
        // if following symlinks is disabled
        if (pathContainsSymlink) {
            isAllowedToAccessFile(user, rawPath).let {
                if (it.isNotSuccessful) return it.cast()
            }

            if (Files.isSymbolicLink(rawPath.path)) {
                // stream the link itself
                return try {
                    Result.ok(Files.readSymbolicLink(rawPath.path).toString().toByteArray().inputStream())
                } catch (e: Exception) {
                    Result.error("Failed to read the symlink target path.")
                }
            } else {
                return Result.notFound()
            }
        }

        val canonicalPath = pathResult.let {
            if (it.isNotSuccessful) {
                return it.cast()
            }
            it.value
        }

        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (!Files.isRegularFile(canonicalPath.path, LinkOption.NOFOLLOW_LINKS)) return Result.notFound()

        return try {
            Result.ok(Files.newInputStream(canonicalPath.path))
        } catch (e: Exception) {
            Result.error("Failed to stream file.")
        }
    }


    /**
     * Returns file metadata
     *
     * if file is a folder, also returns entries
     */
    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath): Result<Pair<FileMetadata, List<FileMetadata>?>> {
        val (pathResult, pathHasSymlink) = resolvePath(rawPath)
        if (pathResult.isNotSuccessful) return pathResult.cast()
        val canonicalPath = pathResult.value

        val metadata = getMetadata(user, rawPath = rawPath, canonicalPath = canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val type = metadata.fileType

        if (type == FileType.FOLDER || (type == FileType.FOLDER_LINK && State.App.followSymLinks)) {
            val entries = getFolderEntries(
                user = user,
                canonicalPath = canonicalPath,
            ).let {
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
    fun getMetadata(user: Principal, rawPath: FilePath, canonicalPath: FilePath): Result<FileMetadata> {
        isAllowedToAccessFile(user, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return filesystem.getMetadata(rawPath)?.toResult()
            ?: Result.notFound()
    }

    fun isAllowedToAccessFile(user: Principal, canonicalPath: FilePath, hasAdminAccess: Boolean? = null): Result<Unit> {
        // Deny blocked folder
        val isAllowedResult = isPathAllowed(canonicalPath = canonicalPath)
        if (isAllowedResult.isNotSuccessful) return isAllowedResult.cast()

        // Verify the file location and handle conflicts
        val isFileAvailable = verifyEntityInode(canonicalPath, UserAction.READ_FOLDER)
        if (isFileAvailable.isNotSuccessful) return Result.error("This folder is not available.")

        // Check if user can read any files
        val isAdmin = hasAdminAccess ?: hasAdminAccess(user)
        val permissionResult = hasReadPermission(canonicalPath = canonicalPath, principal = user, hasAdminAccess = isAdmin)
        if (permissionResult.isNotSuccessful) return permissionResult.cast()

        return Result.ok()
    }

    /**
     * Returns list of entries in a folder.
     *
     * Verifies user permissions.
     */
    fun getFolderEntries(user: Principal, canonicalPath: FilePath): Result<List<FileMetadata>> {
        val hasAdminAccess = hasAdminAccess(user)
        val isAllowed = isAllowedToAccessFile(user, canonicalPath = canonicalPath, hasAdminAccess = hasAdminAccess)
        if (isAllowed.isNotSuccessful) return isAllowed.cast()

        // Get folder entries
        val result = internalGetFolderEntries(canonicalPath = canonicalPath, userAction = UserAction.READ_FOLDER)
        if (result.isNotSuccessful) return result.cast()

        // Filter entries which are allowed and user has sufficient permission
        // Resolve entries which are symlinks
        val entries = result.value.filter { meta: FileMetadata ->
            // Check if `it.fileType` is symlink, resolve if it is
            val entryPath = if (meta.fileType.isSymLink()) {
                val (resolvedResult, hasSymlink) = resolvePath(FilePath.of(meta.filename))
                resolvedResult.let {
                    if (it.isNotSuccessful) return@filter false
                    it.value
                }
            } else {
                FilePath.of(meta.filename)
            }

            val isPathAllowed = folderVisibilityService.isPathAllowed(entryPath) == null
            if (!isPathAllowed) return@filter false

            // Check permissions for entry
            if (!hasAdminAccess) {
                val permission = entityPermissionService.getUserPermission(
                    canonicalPath = entryPath,
                    userId = user.userId,
                    roles = user.roles
                )
                val hasPermission = permission != null && permission.permissions.contains(FilePermission.READ)
                if (!hasPermission) return@filter false
            }

            true
        }

        return entries.toResult()
    }

    fun hasAdminAccess(user: Principal): Boolean = user.hasAnyPermission(listOf(SystemPermission.ACCESS_ALL_FILES, SystemPermission.SUPER_ADMIN))

    /**
     * Returns whether user has sufficient read permission for path.
     */
    fun hasReadPermission(canonicalPath: FilePath, principal: Principal, hasAdminAccess: Boolean): Result<Unit> {
        if (!hasAdminAccess) {
            val permissions = entityPermissionService.getUserPermission(canonicalPath = canonicalPath, userId = principal.userId, roles = principal.roles)
                ?: return Result.reject("You do not have permission to open this folder.")

            if (!permissions.permissions.contains(FilePermission.READ)) return Result.reject("You do not have permission to open this folder.")
        }

        return Result.ok(Unit)
    }

    /**
     * Returns null if path is allowed. Otherwise returns string error.
     */
    fun isPathAllowed(canonicalPath: FilePath): Result<Unit> {
        val result = folderVisibilityService.isPathAllowed(canonicalPath = canonicalPath)
        return if (result == null) Result.ok() else Result.reject(result, source = "isPathAllowed")
    }

    /**
     * Directly gets entries from a folder. Is not authenticated.
     */
    private fun internalGetFolderEntries(canonicalPath: FilePath, userAction: UserAction): Result<List<FileMetadata>> {
        try {
            // Check if the resolved path is a folder
            if (!Files.isDirectory(canonicalPath.path, *if (State.App.followSymLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                return Result.notFound()
            }

            // List entries
            val entries = Files.newDirectoryStream(canonicalPath.path).use { it.toList() }

            val files = entries.map {
                val entryPath = FilePath(it)
                filesystem.getMetadata(entryPath)
                    ?: throw IllegalStateException("Metadata object was null for a known file.")
            }

            return files.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to get folder entries with metadata.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to get contents of this folder.")
        }
    }


    private val verifyLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Verifies whether a file exists.
     *
     * Handles file conflicts like moved files. Can reassign inode or path of an entity.
     */
    fun verifyEntityInode(path: FilePath, userAction: UserAction): Result<Unit> {
        val lock = verifyLocks.computeIfAbsent(path.pathString) { ReentrantLock() }
        lock.lock()
        try {
            val entity = entityService.getByPath(path.pathString, UserAction.NONE).let {
                if (it.notFound) return Result.ok()
                if (it.isNotSuccessful) return it.cast()
                it.value
            }
            val followSymlinks = entity.followSymlinks

            // Do not do inode check on unsupported filesystem.
            if (!entity.isFilesystemSupported || entity.inode == null) {
                val exists = filesystem.exists(path.path, followSymbolicLinks = followSymlinks)
                return if (exists) Result.ok() else Result.reject("Path does not exist.", source = "verifyEntityByInode-notSupported-notFound")
            }

            val newInode = filesystem.getInode(path.path, followSymbolicLinks = followSymlinks)
            // Inode matches normally
            if (entity.inode == newInode) return Result.ok()

            // Handle if a file with a different inode exists on the path
            if (newInode != null) {
                val existingEntityR = entityService.getByInodeWithNullPath(newInode, userAction)

                // Check if this inode was already in the database
                if (existingEntityR.isSuccessful) {
                    // Dangling entity exists with this inode.
                    // Associate this path to it.
                    val existingEntity = existingEntityR.value
                    entityService.updatePath(existingEntity.entityId, path.pathString, existingEntity, userAction)
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
            verifyLocks.remove(path.pathString, lock)
        }
    }
}