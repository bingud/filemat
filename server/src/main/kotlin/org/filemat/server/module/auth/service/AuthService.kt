package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import kotlinx.coroutines.*
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.iterate
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.isExpired
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap


@Service
class AuthService(
    private val authTokenService: AuthTokenService,
    private val userRoleService: UserRoleService,
    private val userService: UserService,
    private val logService: LogService
) {
    private final val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // User auth tokens
    // Cleared every 60 seconds - Lazy cleared when accessed.
    private val tokenToUserIdMap = ConcurrentHashMap<String, AuthToken>()
    // User principals
    private val principalMap = ConcurrentHashMap<Ulid, Principal>()


    @EventListener(ApplicationReadyEvent::class)
    private fun initialize() {
        expireMemoryAuthTokens()
    }

    private fun expireMemoryAuthTokens() {
        scope.launch {
            var loggedFailure = false

            while(true) {
                runCatching {
                    val now = unixNow()

                    tokenToUserIdMap.iterate { key, value, remove ->
                        if (value.isExpired(now)) {
                            remove()
                        }
                    }

                    loggedFailure = false
                }.onFailure {
                    if (!loggedFailure) {
                        logService.error(
                            type = LogType.SYSTEM,
                            action = UserAction.NONE,
                            description = "Failed to remove expired auth tokens from memory.",
                            message = it.stackTraceToString()
                        )
                        loggedFailure = true
                    }
                }

                delay(60000)
            }
        }
    }

    /**
     * Update a users principal in memory
     */
    fun updatePrincipal(userId: Ulid, block: (existing: Principal) -> Principal) {
        principalMap.computeIfPresent(userId) { _: Ulid, existing: Principal ->
            return@computeIfPresent block(existing)
        }
    }

    /**
     * Get a user principal by auth token
     */
    fun getPrincipalByToken(token: String): Result<Principal> {
        return getPrincipalFromMemoryByToken(token)?.toResult() ?: let {
            val p = getPrincipalFromDatabaseByToken(token)
            if (p.isNotSuccessful) return p
            return@let p.value.toResult()
        }
    }

    /**
     * Get user principal from database using auth token
     */
    private fun getPrincipalFromDatabaseByToken(token: String): Result<Principal> {
        val authTokenResult: Result<AuthToken> = authTokenService.getToken(token)
        if (authTokenResult.hasError) return Result.error(authTokenResult.error)
        if (authTokenResult.notFound) return Result.notFound()
        val authToken = authTokenResult.value

        val userR = userService.getUserByUserId(authToken.userId, UserAction.GENERIC_GET_PRINCIPAL)
        if (userR.hasError) return Result.error(userR.error)
        if (userR.notFound) return Result.notFound()
        val user = userR.value

        tokenToUserIdMap[token] = authToken
        val principal = getPrincipalFromDatabaseByUser(user)
        return principal
    }

    /**
     * Get user principal from database using user object
     */
    private fun getPrincipalFromDatabaseByUser(user: User): Result<Principal> {
        val rolesR = userRoleService.getRolesByUserId(user.userId)
        if (rolesR.isNotSuccessful) return Result.error(rolesR.error)
        val roles = rolesR.value


        val principal = let {
            principalMap[user.userId]
        } ?: let {
            val principal = Principal(
                userId = user.userId,
                email = user.email,
                username = user.username,
                mfaTotpStatus = user.mfaTotpStatus,
                isBanned = user.isBanned,
                roles = roles.map { it.roleId }.toMutableList()
            )

            principalMap[user.userId] = principal
            principal
        }

        return principal.toResult()
    }

    /**
     * Get user principal from database using auth token
     */
    private fun getPrincipalFromMemoryByToken(token: String): Principal? {
        val authToken = tokenToUserIdMap[token] ?: return null
        if (authToken.isExpired()) {
            tokenToUserIdMap.remove(token)
            return null
        }
        return principalMap[authToken.userId]
    }

}