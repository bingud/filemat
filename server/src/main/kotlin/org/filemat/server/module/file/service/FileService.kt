package org.filemat.server.module.file.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.FileUtils
import org.filemat.server.common.util.runIf
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.LinkOption
import java.nio.file.Paths
import kotlin.io.path.exists

@Service
class FileService(
    private val folderVisibilityService: FolderVisibilityService,
    private val entityPermissionService: EntityPermissionService,
    private val entityService: EntityService
) {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getFolderEntries(folderPath: String, principal: Principal): Result<Any> {
        val path = normalizePath(folderPath)

        // Deny blocked folder
        val isAllowed = folderVisibilityService.isPathAllowed(folderPath = path, isNormalized = true)
        if (!isAllowed) return Result.reject("This folder is not accessible.")

        val hasAdminAccess = principal.hasPermission(Permission.ACCESS_ALL_FILES)
        // Check permissions
        runIf(!hasAdminAccess) {
            val permissions = entityPermissionService.getUserPermission(filePath = path, isNormalized = true, userId = principal.userId, roles = principal.roles)
                ?: return@runIf

            if (!permissions.permissions.contains(Permission.READ)) return Result.reject("")
        }



        return TODO()
    }

    /**
     * Checks if entity Inode matches actual file Inode
     */
    fun verifyEntityInode(filePath: String, userAction: UserAction): Boolean {
        val entityR = entityService.getByPath(filePath, UserAction.NONE)
        if (entityR.isNotSuccessful) return false
        val entity = entityR.value

        // Only check path of unsupported filesystem
        if (!entity.isFilesystemSupported || entity.inode == null) {
            val path = Paths.get(filePath)
            val exists = if (State.App.followSymLinks) path.exists() else path.exists(LinkOption.NOFOLLOW_LINKS)
            return exists
        }

        val currentInode = FileUtils.getInode(filePath)
        if (entity.inode == currentInode) return true

        // Search for file by inode
        val parentPath = filePath.substringBeforeLast("/")
        val newPath = FileUtils.findFilePathByInode(entity.inode, parentPath)

        // Change entity path to new path
        if (newPath != null) {
            entityService.removeInodeAndPath()
            TODO()
            return true
        }

        if (currentInode == null) {
            // File does not exist anymore
            // Remove path and inode from entity
            // Entity will remain dangling, with permissions unchanged.
            if (newPath == null) {
                entityService.removeInodeAndPath(entity.entityId, userAction).let {
                    if (it.isNotSuccessful) return false
                }
            }
        }
    }

}













