package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.permission.model.Permission
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for managing users
 */
@Authenticated([Permission.MANAGE_USERS])
@RequestMapping("/v1/admin/user")
@RestController
class AdminUserController(
    private val userService: AdminUserService
) : AController() {

    /**
     * Returns a list of all users
     */
    @PostMapping("/list")
    fun adminUserListMapping(
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val result = userService.getUserList()
        if (result.isNotSuccessful) return internal(result.error, "")
        val list = result.value
        val serialized = Json.encodeToString(list)

        return ok(serialized)
    }

}