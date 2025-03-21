package org.filemat.server.module.admin.controller

import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.RoleMeta
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
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
) : AController() {

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