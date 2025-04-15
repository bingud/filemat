package org.filemat.server.module.role.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.toBoolean
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.*
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.toRoleDto
import org.filemat.server.module.role.model.withNewPermissions
import org.filemat.server.module.role.repository.RoleRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class RoleService(
    private val roleRepository: RoleRepository,
    private val logService: LogService,
    private val authService: AuthService,
) {

    fun remove(user: Principal, roleId: Ulid, userAction: UserAction): Result<Unit> {
        val role = State.Auth.roleMap[roleId]
            ?: return Result.notFound()

        // Check if user has permissions to delete role
        val userPermissions = user.getPermissions()
        val rolePermissions = role.permissions
        val hasSufficientPermissions = userPermissions.hasSufficientPermissionsFor(rolePermissions)
        if (!hasSufficientPermissions) return Result.reject("Cannot delete role with higher permissions than you have.")

        // Delete role from database
        deleteRoleFromDatabase(roleId, userAction).let {
            if (it.isNotSuccessful) return it.cast()
        }

        State.Auth.roleMap.remove(roleId)
        authService.removeRoleFromAllPrincipals(roleId)

        logService.info(
            type = LogType.AUDIT,
            action = userAction,
            description = "Deleted role: ${role.name}",
            message = "User '${user.username}' deleted role '${role.name}'.",
            initiatorId = user.userId,
            targetId = roleId
        )

        return Result.ok()
    }

    /**
     * Update the permission list of a role
     */
    fun updatePermissionList(user: Principal, roleId: Ulid, newList: List<SystemPermission>, userAction: UserAction): Result<Unit> {
        val role = State.Auth.roleMap[roleId]
            ?: return Result.notFound()

        // Check if user has sufficient permissions to edit this role's permissions
        val userPermissions = user.getPermissions()
        val rolePermissions = role.permissions
        val hasSufficientPermissions = userPermissions.hasSufficientPermissionsFor(rolePermissions)
        if (!hasSufficientPermissions) return Result.reject("Cannot edit role with higher permissions than you have.")

        // Update permissions in database
        updateDatabasePermissionList(roleId, newList, userAction).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Update permissions in memory
        State.Auth.roleMap.computeIfPresent(roleId) { _, existing ->
            existing.withNewPermissions(newList)
        }

        logService.info(
            type = LogType.AUDIT,
            action = userAction,
            description = "Updated permissions of role: ${role.name}",
            message = "User '${user.username}' updated the permissions of role '${role.name}'. \nNew permissions: \n${newList.joinToString(", ")}",
            initiatorId = user.userId,
            targetId = roleId
        )

        return Result.ok()
    }

    /**
     * Delete a role from the database
     */
    private fun deleteRoleFromDatabase(roleId: Ulid, userAction: UserAction): Result<Unit> {
        try {
            roleRepository.deleteById(roleId)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to delete role from database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to delete role.")
        }
    }

    /**
     * Update the permission list of a role in the database
     */
    private fun updateDatabasePermissionList(roleId: Ulid, newList: List<Permission>, userAction: UserAction): Result<Unit> {
        try {
            roleRepository.updatePermissions(roleId, newList.serialize())
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to update role permissions in database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to save new role permissions.")
        }
    }

    fun create(role: Role): Result<Unit> {
        try {
            val roleDto = role.toRoleDto()

            roleRepository.insert(
                roleId = roleDto.roleId,
                name = roleDto.name,
                createdDate = roleDto.createdDate,
                permissions = roleDto.permissions
            )
            State.Auth.roleMap[role.roleId] = role

            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to create new role in database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to create new role.")
        }
    }

    fun loadRolesToMemory(): Boolean {
        try {
            val list = roleRepository.getAll().map { it.toRole() }
            list.forEach {
                State.Auth.roleMap[it.roleId] = it
            }
            return true
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to load roles to memory.",
                message = e.stackTraceToString(),
            )
            return false
        }
    }

    fun createSystemRoles(): Boolean {
        val now = unixNow()

        // Check if the system roles already exist.
        val adminExists = try {
            roleRepository.exists(Props.Roles.adminRoleId)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to check if admin role exists in the database.",
                message = e.stackTraceToString()
            )
            return false
        }

        val userExists = try {
            roleRepository.exists(Props.Roles.userRoleId)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to check if user role exists in the database.",
                message = e.stackTraceToString()
            )
            return false
        }

        // Prepare the list of roles that need to be inserted.
        val rolesToCreate = mutableListOf<Role>()
        if (!adminExists) {
            rolesToCreate.add(
                Role(
                    roleId = Props.Roles.adminRoleId,
                    name = "admin",
                    createdDate = now,
                    permissions = SystemPermission.entries
                )
            )
        } else {
            try {
                roleRepository.updatePermissions(Props.Roles.adminRoleId, SystemPermission.entries.serialize())
            } catch (e: Exception) {
                logService.error(type = LogType.SYSTEM, action = UserAction.NONE, description = "Failed to update permissions of admin role during initialization", message = e.stackTraceToString())
                return false
            }
        }

        if (!userExists) {
            rolesToCreate.add(
                Role(
                    roleId = Props.Roles.userRoleId,
                    name = "user",
                    createdDate = now,
                    permissions = emptyList()
                )
            )
        }

        // Nothing to do if both roles already exist.
        if (rolesToCreate.isEmpty()) {
            return true
        }

        // Insert roles and keep track of those successfully created.
        val createdRoleNames = mutableListOf<String>()
        rolesToCreate.forEach { role ->
            try {
                val inserted = roleRepository.insert(
                    roleId = role.roleId,
                    name = role.name,
                    createdDate = role.createdDate,
                    permissions = role.permissions.toIntList().toString()
                ).toBoolean()

                if (inserted) {
                    createdRoleNames.add(role.name)
                }

                State.Auth.roleMap[role.roleId] = role
            } catch (e: Exception) {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Failed to create role '${role.name}' in the database.",
                    message = e.stackTraceToString()
                )
                return false
            }
        }

        // Log an audit entry if any role was created.
        if (createdRoleNames.isNotEmpty()) {
            logService.createLog(
                level = LogLevel.INFO,
                type = LogType.AUDIT,
                action = UserAction.NONE,
                createdDate = now,
                description = "Created system roles: ${createdRoleNames.joinToString(", ")}",
                message = "System user roles created.",
                meta = null
            )
        }

        return true
    }


}