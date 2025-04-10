package org.filemat.server.module.permission

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.json
import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.common.util.valueOfOrNull
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
            path = FilePath(rawPath),
            targetId = id,
            mode = mode,
            permissions = permissionList
        ).let {
            if (it.notFound) return bad("This file was not found.", "")
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
        val path = FilePath(rawPath)
        val includeUsernames = rawIncludeUsernames?.toBooleanStrictOrNull() ?: false

        val meta = entityPermissionService.getEntityPermissions(user = user, path = path).let {
            if (it.notFound) return bad("This file was not found.", "")
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