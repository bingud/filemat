package org.filemat.server.module.role.service

import org.filemat.server.common.util.toBoolean
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.Permission
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

    fun createSystemRoles(): Boolean {
        val now = unixNow()

        val userRoleExists = try {
            roleRepository.existsById(Props.userRoleId) to null
        } catch (e: Exception) {
            null to e
        }
        val adminRoleExists = try {
            roleRepository.existsById(Props.adminRoleId) to null
        } catch (e: Exception) {
            null to e
        }

        val statusException = userRoleExists.second ?: adminRoleExists.second
        if (statusException != null) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to check if system role exists in database.",
                message = statusException.stackTraceToString(),
            )
        }

        val admin = Role(
            roleId = Props.adminRoleId,
            name = "admin",
            createdDate = now,
            permissions = Permission.entries,
        )
        val user = Role(
            roleId = Props.userRoleId,
            name = "user",
            createdDate = now,
            permissions = emptyList()
        )

        try {
            val a = roleRepository.insert(
                roleId = admin.roleId.toString(),
                name = admin.name,
                createdDate = admin.createdDate,
                permissions = admin.permissions.toIntList().toString()
            ).toBoolean()

            val u = roleRepository.insert(
                roleId = user.roleId.toString(),
                name = user.name,
                createdDate = user.createdDate,
                permissions = user.permissions.toIntList().toString()
            ).toBoolean()

            if (a || u) {
                logService.createLog(
                    level = LogLevel.INFO,
                    type = LogType.AUDIT,
                    action = UserAction.NONE,
                    createdDate = now,
                    description = "Created system roles: ${if (a) admin.name else ""}, ${if (u) user.name else ""}",
                    message = "System user roles created."
                )
            }

            return true
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to create system role in database",
                message = e.stackTraceToString(),
            )

            return false
        }
    }

}