package org.filemat.server.module.file.service.file.component

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.emptyFlow
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.safeWalk
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.sharedFile.resolveSharedDescendant
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.sharedFile.service.FileShareService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import kotlin.io.path.pathString

@Service
class FileEntryListsService(
    private val fileLockService: FileLockService,
    private val fileService: FileService,
    private val fileShareService: FileShareService,
    private val entityService: EntityService,
    private val filesystemService: FilesystemService,
    private val savedFileService: SavedFileService,
    private val entityPermissionService: EntityPermissionService,
    private val state: State,
) {

    /**
     * Searches file names below [canonicalPath]. The folder itself is checked before the walk starts,
     * because the walk reaches folders the user can't open (only matching files are checked one by one).
     */
    fun searchFiles(
        user: Principal?,
        canonicalPath: FilePath,
        text: String,
        isShared: Boolean = false,
        shareRelativePath: FilePath? = null,
        shareToken: String? = null,
        userAction: UserAction
    ): Result<Flow<Result<FullFileMetadata>>> {
        // Every user can open the Windows drive list, but searching it would walk every drive
        if (PathPolicy.isVirtualRoot(canonicalPath.path)) return Result.reject("The drive list can't be searched. Open a drive instead.")
        fileService.isAllowedToAccessFile(user, canonicalPath, ignorePermissions = if (isShared) true else null).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val lowercaseText = text.lowercase()

        val walk = if (shareToken != null) {
            val shareRoot = fileService.resolvePathWithOptionalShare(FilePath.of(PathPolicy.root), shareToken)
            if (shareRoot.isNotSuccessful) return Result.ok(emptyFlow())
            canonicalPath.path.safeWalk(
                with = fileLockService,
                include = { resolveSharedDescendant(it, shareRoot.value, state.app.followSymlinks).isSuccessful },
            )
        } else {
            canonicalPath.path.safeWalk(with = fileLockService)
        }

        return Result.ok(walk
            .mapNotNull { path ->
                try {
                    // Check searched text
                    if (path.fileName?.pathString?.lowercase()?.contains(lowercaseText) == true) {
                        val filePath = FilePath.ofAlreadyNormalized(path)

                        // Get metadata
                        fileService.getFullMetadata(user, filePath, filePath, ignorePermissions = isShared).let {
                            if (it.isNotSuccessful) return@mapNotNull null
                            val meta = it.value
                            if (shareRelativePath == null) return@mapNotNull it

                            val relativePath = canonicalPath.path.relativize(path)
                            val clientPath = shareRelativePath.path.resolve(relativePath)
                            return@mapNotNull Result.ok(meta.copy(path = PathPolicy.toApiString(clientPath)))
                        }
                    } else return@mapNotNull null
                } catch (e: Exception) {
                    return@mapNotNull null
                }
            }
            .take(10_000)
            .flowOn(Dispatchers.IO))
    }

    /**
     * @return List of files that are shared by a user, or globally
     */
    fun getSharedFileList(user: Principal, getAll: Boolean, userAction: UserAction): Result<List<FullFileMetadata>> {
        if (getAll && !user.hasPermission(state, SystemPermission.MANAGE_ALL_FILE_SHARES)) return Result.reject("You do not have permission to view all shared files.")

        val shares = let {
            if (getAll) {
                fileShareService.getAllShares(userAction).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            } else {
                fileShareService.getSharesByUserId(user.userId, userAction).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            }
        }

        val entities = shares
            .distinctBy { it.fileId }
            .mapNotNull { entityService.getById(entityId = it.fileId, userAction).valueOrNull }
            .let { entities ->
                if (!getAll) return@let entities

                return@let entities.filter { entity ->
                    entity.path ?: return@filter false
                    fileService.isAllowedToAccessFile(
                        user = user,
                        canonicalPath = FilePath.of(entity.path),
                        ignorePermissions = null,
                    ).let { permissionResult ->
                        return@filter permissionResult.isSuccessful
                    }
                }
            }


        val files = entities.mapNotNull {
            it.path ?: return@mapNotNull null
            val path = FilePath.of(it.path)

            fileService.getFullMetadata(
                user = user,
                rawPath = path,
                canonicalPath = path,
            ).valueOrNull
        }

        return files.toResult()
    }

    /**
     * @return List of top-level files that a user has access to
     */
    fun getPermittedFileList(user: Principal): Result<List<FullFileMetadata>> {
        val entityIds = entityPermissionService.getPermittedEntities(user)

        val fileMetadataList = entityIds.mapNotNull { (entityId: Ulid, permissions: Set<FilePermission>) ->
            // Get entity
            val entity = entityService.getById(entityId, UserAction.GET_PERMITTED_ENTITIES).valueOrNull ?: return@mapNotNull null
            if (entity.path == null) return@mapNotNull null

            // Get metadata
            val meta = filesystemService.getMetadata(FilePath.of(entity.path)) ?: return@mapNotNull null

            // Get saved status
            val isSaved = savedFileService.isSaved(user.userId, entity.path)

            val fullMeta = FullFileMetadata.from(
                meta,
                isSaved = isSaved,
                permissions = permissions
            )

            return@mapNotNull fullMeta
        }

        return fileMetadataList.toResult()
    }

}