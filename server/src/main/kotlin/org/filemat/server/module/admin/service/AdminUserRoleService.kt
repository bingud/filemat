package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.role.service.UserRoleService
import org.springframework.stereotype.Service

@Service
class AdminUserRoleService(private val userRoleService: UserRoleService) {

    fun assignRole(principal: Principal, targetId: Ulid, roleId: Ulid): Result<Unit> {
        val role = State.Auth.roleMap[roleId]
            ?: return Result.error("This role does not exist.")

        val userPermissions = principal.getPermissions()
        if (!userPermissions.containsAll(role.permissions)) return Result.reject("Cannot assign a role with higher permissions.")

        val targetRoles = userRoleService.getRolesByUserId(targetId).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val targetPermissions = targetRoles.map { State.Auth.roleMap[it.roleId]?.permissions }.filterNotNull().flatten()
        TODO()
    }

}