package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.model.hasSufficientPermissionsFor
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class AdminUserRoleService(private val userRoleService: UserRoleService, private val authService: AuthService, private val logService: LogService) {

    /**
     * Remove a list of roles from a user
     *
     * @return List of removed roles
     */
    fun removeRoles(principal: Principal, targetId: Ulid, allRoles: List<Ulid>): Result<List<Ulid>> {
        val userPermissions = principal.getPermissions()

        val roles = allRoles.map { roleId ->
            val role = State.Auth.roleMap[roleId]
                ?: return@map null

            if (userPermissions.hasSufficientPermissionsFor(role.permissions) == false) return@map null
            role
        }.filterNotNull()
        val roleIds = roles.map { it.roleId }

        val targetPrincipal = authService.getPrincipalByUserId(targetId, false).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        userRoleService.removeList(
            userId = targetId,
            roles = roleIds,
            action = UserAction.UNASSIGN_ROLES,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        authService.updatePrincipal(targetId) { currentPrincipal ->
            currentPrincipal.roles.removeAll(roleIds)
            currentPrincipal
        }

        // Log
        if (roles.isNotEmpty()) {
            logService.info(
                LogType.AUDIT,
                action = UserAction.UNASSIGN_ROLES,
                description = "Unassigned ${roles.size} roles from user ${targetPrincipal.username}",
                message = "User ${principal.username} unassigned roles from user ${targetPrincipal.username}:\n${roles.joinToString(", ") { it.name }}",
                initiatorId = principal.userId,
                initiatorIp = null,
                targetId = targetId,
                meta = null
            )
        }

        return Result.ok(roleIds)
    }

    /**
     * Assign a role to a user
     */
    fun assignRole(principal: Principal, targetId: Ulid, roleId: Ulid): Result<Unit> {
        val role = State.Auth.roleMap[roleId]
            ?: return Result.error("This role does not exist.")

        val userPermissions = principal.getPermissions()

        // Check if user has high enough roles to assign role
        if (userPermissions.hasSufficientPermissionsFor(role.permissions) == false) return Result.reject("")

        // Get targets principal
        val targetPrincipal = authService.getPrincipalByUserId(targetId, false).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val targetRoles = targetPrincipal.roles

        // Check if user already has role
        if (targetRoles.any { it == roleId }) return Result.error("User already has this role.")

        // Add role in database
        userRoleService.assign(
            userId = targetId,
            roleId = roleId,
            action = UserAction.ASSIGN_ROLE,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update principal in memory
        authService.updatePrincipal(targetId) {
            it.roles.add(roleId)
            it
        }

        // Log
        logService.info(
            LogType.AUDIT,
            action = UserAction.ASSIGN_ROLE,
            description = "Assigned role: ${role.name} to user ${targetPrincipal.username}",
            message = "User ${principal.username} assigned role ${role.name} to user ${targetPrincipal.username}",
            initiatorId = principal.userId,
            initiatorIp = null,
            targetId = targetId,
            meta = meta("roleId" to roleId.toString())
        )

        return Result.ok()
    }

}