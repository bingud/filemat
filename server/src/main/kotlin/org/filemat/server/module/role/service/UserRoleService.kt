package org.filemat.server.module.role.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.UserRole
import org.filemat.server.module.role.repository.UserRoleRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class UserRoleService(
    private val userRoleRepository: UserRoleRepository,
    private val logService: LogService,
) {

    fun getRoleUsers(roleId: Ulid): Result<List<Ulid>> {
        try {
            return userRoleRepository.getUserIdsByRole(roleId)?.toResult() ?: return Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to load user IDs by role",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get users with this role.")
        }
    }

    fun getRolesByUserId(userId: Ulid): Result<List<UserRole>> {
        try {
            val result = userRoleRepository.getRolesByUserId(userId)
            return result.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.GENERIC_GET_ACCOUNT_ROLES,
                description = "Failed to get user roles from database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get account roles.")
        }
    }

    fun assign(userId: Ulid, roleId: Ulid, action: UserAction): Result<Unit> {
        try {
            userRoleRepository.insert(userId = userId.toString(), roleId = roleId.toString(), now = unixNow())
            return Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = action,
                description = "Failed to assign role to user in database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to assign role to user.")
        }
    }

}