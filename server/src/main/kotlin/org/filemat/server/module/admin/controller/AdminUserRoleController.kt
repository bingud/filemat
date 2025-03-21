package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserRoleService
import org.filemat.server.module.permission.model.SystemPermission
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@Authenticated([SystemPermission.MANAGE_USERS])
@RestController
@RequestMapping("/v1/admin/user-role")
class AdminUserRoleController(private val adminUserRoleService: AdminUserRoleService) : AController() {

    @PostMapping("/assign")
    fun adminAssignUserRoleMapping(
        request: HttpServletRequest,
        @RequestParam("userId") rawUserId: String,
        @RequestParam("roleId") rawRoleId: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val userId = parseUlidOrNull(rawUserId)
            ?: return bad("Invalid user ID.", "validation")
        val roleId = parseUlidOrNull(rawRoleId)
            ?: return bad("Invalid role ID.", "validation")

        val r = adminUserRoleService.assignRole(principal = principal, targetId = userId, roleId = roleId)
        if (r.rejected || r.notFound) return bad(r.error, "")
        if (r.isNotSuccessful) return internal(r.error, "")
        return ok()
    }

}