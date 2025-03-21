package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.permission.model.hasSufficientPermissionsFor
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class AdminUserRoleService(private val userRoleService: UserRoleService) {

    fun assignRole(principal: Principal, targetId: Ulid, roleId: Ulid): Result<Unit> {
        val role = State.Auth.roleMap[roleId]
            ?: return Result.error("This role does not exist.")

        val userPermissions = principal.getPermissions()

        // Check if user has high enough roles to assign role
        if (!userPermissions.hasSufficientPermissionsFor(role.permissions)) return Result.reject("")

        // Get roles of target user
        val targetRoles = userRoleService.getRolesByUserId(targetId).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        // Check if user already has role
        if (targetRoles.any { it.roleId == roleId }) return Result.error("User already has this role.")

        userRoleService.assign(
            userId = targetId,
            roleId = roleId,
            action = UserAction.ASSIGN_ROLE,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return Result.ok()
    }

}