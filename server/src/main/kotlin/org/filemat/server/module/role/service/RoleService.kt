package org.filemat.server.module.role.service

import org.filemat.server.common.State
import org.filemat.server.common.util.toBoolean
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.serialize
import org.filemat.server.module.permission.model.toIntList
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.repository.RoleRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class RoleService(
    private val roleRepository: RoleRepository,
    private val logService: LogService,
) {

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
                    permissions = Permission.entries
                )
            )
        } else {
            try {
                roleRepository.updatePermissions(Props.Roles.adminRoleId, Permission.entries.serialize())
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
                    roleId = role.roleId.toString(),
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