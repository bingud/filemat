package org.filemat.server.module.file.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.runIf
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.springframework.stereotype.Service

@Service
class FileService(
    private val folderVisibilityService: FolderVisibilityService,
    private val entityPermissionService: EntityPermissionService
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

            if (!permissions.contains(Permission.READ)) return Result.reject("")
        }



        return TODO()
    }

}