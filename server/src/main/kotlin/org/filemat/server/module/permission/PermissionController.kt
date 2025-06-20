package org.filemat.server.module.permission

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.PermissionType
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/permission")
@Authenticated
class PermissionController(
    private val entityPermissionService: EntityPermissionService,
    private val userService: UserService,
    private val adminUserService: AdminUserService
) : AController() {

    @PostMapping("/delete-entity")
    fun deleteEntityPermissionsMapping(
        request: HttpServletRequest,
        @RequestParam("permissionId") rawPermissionId: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val permissionId = parseUlidOrNull(rawPermissionId)
            ?: return bad("Invalid permission ID.", "validation")

        entityPermissionService.deletePermission(
            user = user,
            permissionId = permissionId,
        ).let {
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.hasError) return internal(it.error, "")
            return ok()
        }
    }

    @PostMapping("/update-entity")
    fun updateEntityPermissionsMapping(
        request: HttpServletRequest,
        @RequestParam("permissionId") rawPermissionId: String,
        @RequestParam("newPermissionList") rawNewPermissionList: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val permissionId = parseUlidOrNull(rawPermissionId)
            ?: return bad("Invalid permission ID.", "validation")
        val newPermissionList = rawNewPermissionList.parseJsonOrNull<List<FilePermission>>()
            ?: return bad("Invalid permission list.", "validation")

        entityPermissionService.updatePermission(
            user = user,
            permissionId = permissionId,
            newPermissions = newPermissionList,
        ).let {
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.hasError) return internal(it.error, "")
            return ok()
        }
    }

    @PostMapping("/create-entity")
    fun createEntityPermissionsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("mode") rawMode: String,
        @RequestParam("id") rawId: String,
        @RequestParam("permissionList") rawPermissionList: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val mode = valueOfOrNull<PermissionType>(rawMode)
            ?: return bad("Invalid permission type.", "validation")
        val id = parseUlidOrNull(rawId)
            ?: return bad("Invalid target ID.", "validation")

        val permissionList = runCatching {
            Json.decodeFromString<List<FilePermission>>(rawPermissionList)
        }.getOrElse { return bad("Invalid list of permissions", "validation") }

        entityPermissionService.createPermission(
            user = user,
            rawPath = FilePath.of(rawPath),
            targetId = id,
            mode = mode,
            permissions = permissionList
        ).let {
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.hasError) return internal(it.error, "")
            return ok(Json.encodeToString(it.value))
        }
    }

    @PostMapping("/entity")
    fun getEntityPermissionsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("include-mini-user-list", required = false) rawIncludeUsernames: String?,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val includeUsernames = rawIncludeUsernames?.toBooleanStrictOrNull() ?: false

        val meta = entityPermissionService.getEntityPermissions(user = user, rawPath = path).let {
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        if (includeUsernames) {
            val userPermissions = meta.permissions.mapNotNull { it.userId }
            val miniUsers = adminUserService.getUserMiniList(userPermissions).let {
                if (it.isNotSuccessful) return bad(it.error, "")
                it.value
            }
            meta.miniUserList = miniUsers
        }

        val json = Json.encodeToString(meta)
        return ok(json)
    }

}