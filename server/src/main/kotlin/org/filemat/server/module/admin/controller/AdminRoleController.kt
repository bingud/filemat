package org.filemat.server.module.admin.controller

import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.hasSufficientPermissionsFor
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.RoleMeta
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.UserAction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for managing roles
 */
@Authenticated([SystemPermission.EDIT_ROLES])
@RestController
@RequestMapping("/v1/admin/role")
class AdminRoleController(
    private val roleService: RoleService,
    private val userRoleService: UserRoleService,
    private val logService: LogService,
) : AController() {

    /**
     * Returns new role ID
     */
    @PostMapping("/create")
    fun adminCreateRoleMapping(
        request: HttpServletRequest,
        @RequestParam("name") name: String,
        @RequestParam("permissions") rawPermissions: String,
    ): ResponseEntity<String> {
        Validator.roleName(name)?.let { return bad(it, "validation") }

        val permissions = Json.decodeFromStringOrNull<List<SystemPermission>>(rawPermissions)
            ?: return bad("List of permissions is invalid.", "validation")

        val principal = request.getPrincipal()!!
        if (principal.getPermissions().hasSufficientPermissionsFor(permissions) == false) return bad("Cannot create role with higher permissions than you have.", "")

        val role = Role(
            roleId = UlidCreator.getUlid(),
            name = name,
            createdDate = unixNow(),
            permissions = permissions
        )

        roleService.create(role = role).let {
            if (it.isNotSuccessful) return bad(it.error, "")
        }

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.ASSIGN_ROLE,
            description = "Role created: $name",
            message = "Role '$name' created by user '${principal.username}' with permissions:\n${permissions.joinToString(", ")}",
            initiatorId = principal.userId,
            initiatorIp = request.realIp()
        )

        return ok(role.roleId.toString())
    }

    /**
     * Returns a role and its user IDs
     */
    @PostMapping("/get")
    fun adminGetRoleMapping(
        @RequestParam("roleId") rawRoleId: String
    ): ResponseEntity<String> {
        val roleId = parseUlidOrNull(rawRoleId) ?: return bad("Invalid role ID.", "validation")

        val role = State.Auth.roleMap[roleId] ?: return bad("This role does not exist.", "")

        val userIds = userRoleService.getRoleUsers(roleId).let {
            if (it.notFound) return bad("This role does not exist.", "")
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        val roleMeta = RoleMeta.from(role, userIds)
        val serialized = Json.encodeToString(roleMeta)

        return ok(serialized)
    }

}