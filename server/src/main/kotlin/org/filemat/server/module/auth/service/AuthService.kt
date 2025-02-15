package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val authTokenService: AuthTokenService,
    private val roleService: RoleService,
    private val userRoleService: UserRoleService,
    private val userService: UserService
) {

    fun getPrincipalByToken(token: String): Result<Principal> {
        return getPrincipalFromMemory(token)?.toResult() ?: let {
            val p = getPrincipalFromDatabase(token)
            if (p.isNotSuccessful) return p
            return@let p.value.toResult()
        }
    }

    fun getPrincipalByUserId(userId: Ulid): Result<Principal> {
        State.Auth.principalMap[userId]?.let {
            return it.toResult()
        }

        val userR = userService.getUserByUserId(userId, UserAction.GENERIC_GET_PRINCIPAL)
        if (userR.hasError) return Result.error(userR.error)
        if (userR.notFound) return Result.notFound()
        val user = userR.value

        return TODO()
    }

    private fun getPrincipalFromDatabase(token: String): Result<Principal> {
        val userR = authTokenService.getUserByToken(token)
        if (userR.hasError) return Result.error(userR.error)
        if (userR.notFound) return Result.notFound()
        val user = userR.value

        State.Auth.tokenToUserIdMap[token] = user.userId
        val principal = getPrincipalFromDatabaseByUser(user)
        return principal
    }

    private fun getPrincipalFromDatabaseByUser(user: User): Result<Principal> {
        val rolesR = userRoleService.getRolesByUserId(user.userId)
        if (rolesR.isNotSuccessful) return Result.error(rolesR.error)
        val roles = rolesR.value


        val principal = let {
            State.Auth.principalMap[user.userId]
        } ?: let {
            val principal = Principal(
                userId = user.userId,
                email = user.email,
                username = user.username,
                mfaTotpStatus = user.mfaTotpStatus,
                isBanned = user.isBanned,
                roles = roles.map { it.roleId }.toMutableList()
            )

            State.Auth.principalMap[user.userId] = principal
            principal
        }

        return principal.toResult()
    }

    private fun getPrincipalFromMemory(token: String): Principal? {
        val userId = State.Auth.tokenToUserIdMap[token] ?: return null
        return State.Auth.principalMap[userId]
    }

}