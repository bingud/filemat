package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.role.model.Role
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class AuthService(
    private val authTokenService: AuthTokenService
) {

    fun getAuthByToken(token: String): Result<Principal> {
        return getPrincipalFromMemory(token)?.toResult() ?: let {
            val p = getPrincipalFromDatabase(token)
            if (p.isNotSuccessful) return p
            return@let p.value.toResult()
        }
    }

    private fun getPrincipalFromDatabase(token: String): Result<Principal> {
        val userR = authTokenService.getUserByToken(token)
        if (userR.hasError) return Result.error(userR.error)
        if (userR.notFound) return Result.notFound()
        val user = userR.value

        State.Auth.tokenToUserIdMap[token] = user.userId

        val principal = let {
            State.Auth.principalMap[user.userId]
        } ?: let {
            val principal = Principal(
                userId = user.userId,
                email = user.email,
                username = user.username,
                mfaTotpStatus = user.mfaTotpStatus,
                isBanned = user.isBanned,
                roles = State.Auth.userToRoleMap[user.userId]
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