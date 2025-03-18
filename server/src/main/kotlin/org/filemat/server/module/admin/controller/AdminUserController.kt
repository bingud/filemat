package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.FullPublicUser
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for managing users
 */
@Authenticated([Permission.MANAGE_USERS])
@RequestMapping("/v1/admin/user")
@RestController
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val roleService: RoleService,
    private val userRoleService: UserRoleService
) : AController() {

    /**
     * Returns a list of all users
     */
    @PostMapping("/list")
    fun adminUserListMapping(
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val result = adminUserService.getUserList()
        if (result.isNotSuccessful) return internal(result.error, "")
        val list = result.value
        val serialized = Json.encodeToString(list)

        return ok(serialized)
    }

    /**
     * Returns a user by the user ID
     */
    @PostMapping("/get")
    fun adminGetUserMapping(
        @RequestParam("userId") rawUserId: String,
    ): ResponseEntity<String> {
        val userId = parseUlidOrNull(rawUserId) ?: return bad("User ID is in an invalid format.", "validation")

        val user = adminUserService.getUser(userId).let {
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        val roles = userRoleService.getRolesByUserId(userId).let {
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        val fullUser = FullPublicUser.from(user, roles.map { it.roleId })
        val serialized = Json.encodeToString(fullUser)

        return ok(serialized)
    }

}