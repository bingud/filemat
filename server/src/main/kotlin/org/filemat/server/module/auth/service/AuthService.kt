package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import kotlinx.coroutines.*
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.iterate
import org.filemat.server.common.util.removeIf
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.isExpired
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap


@Service
class AuthService(
    private val authTokenService: AuthTokenService,
    private val userRoleService: UserRoleService,
    @Lazy private val userService: UserService,
    private val logService: LogService
) {
    private final val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // User auth tokens
    // Cleared every 120 seconds - Lazy cleared when accessed.
    private val tokenToUserIdMap = ConcurrentHashMap<String, AuthToken>()

    // Cached for 6 hours, cleared every 240 seconds.
    // UserID => Principal and CachedDate
    private val principalMap = ConcurrentHashMap<Ulid, Pair<Principal, Long>>()


    @EventListener(ApplicationReadyEvent::class)
    private fun initialize() {
        expireMemoryAuthTokens()
        expireMemoryPrincipals()
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

                delay(120_000)
            }
        }
    }

    private fun expireMemoryPrincipals() = scope.launch {
        scope.launch {
            var loggedFailure = false

            while(true) {
                runCatching {
                    val now = unixNow()

                    principalMap.iterate { userId: Ulid, (principal: Principal, cachedAt: Long), remove ->
                        // 6 hours cache
                        if ((now - cachedAt) > 21600) {
                            remove()
                        }
                    }

                    loggedFailure = false
                }.onFailure {
                    if (!loggedFailure) {
                        logService.error(
                            type = LogType.SYSTEM,
                            action = UserAction.NONE,
                            description = "Failed to remove expired user authentication cache from memory.",
                            message = it.stackTraceToString()
                        )
                        loggedFailure = true
                    }
                }

                delay(240_000)
            }
        }
    }


    fun removeRoleFromAllPrincipals(roleId: Ulid) {
        principalMap.forEach { userId, (principal, cachedDate) ->
            principal.roles.remove(roleId)
        }
    }

    // -----

    /**
     * Update a users principal in memory
     */
    fun updatePrincipal(userId: Ulid, block: (existing: Principal) -> Principal) {
        principalMap.computeIfPresent(userId) { _: Ulid, (existing: Principal, cachedDate: Long) ->
            return@computeIfPresent block(existing) to cachedDate
        }
    }

    /**
     * Get a user principal by auth token
     */
    fun getPrincipalByToken(token: String, cacheInMemory: Boolean): Result<Principal> {
        return getPrincipalFromMemoryByToken(token)?.toResult() ?: let {
            val p = getPrincipalFromDatabaseByToken(token, cacheInMemory)
            if (p.isNotSuccessful) return p
            return@let p.value.toResult()
        }
    }

    /**
     * Get a user principal by user ID
     */
    fun getPrincipalByUserId(userId: Ulid, cacheInMemory: Boolean): Result<Principal> {
        return getPrincipalFromMemoryByUserId(userId)?.toResult() ?: let {
            val p = getPrincipalFromDatabaseByUserId(userId, cacheInMemory)
            if (p.isNotSuccessful) return p
            return@let p.value.toResult()
        }
    }

    /**
     * Get user principal from database using auth token
     */
    private fun getPrincipalFromDatabaseByToken(token: String, cacheInMemory: Boolean): Result<Principal> {
        val authTokenResult: Result<AuthToken> = authTokenService.getToken(token)
        if (authTokenResult.hasError) return Result.error(authTokenResult.error)
        if (authTokenResult.notFound) return Result.notFound()
        val authToken = authTokenResult.value

        val principalResult = getPrincipalFromDatabaseByUserId(authToken.userId, cacheInMemory)

        tokenToUserIdMap[token] = authToken
        return principalResult
    }

    /**
     * Get principal from database with the user ID
     */
    private fun getPrincipalFromDatabaseByUserId(userId: Ulid, cacheInMemory: Boolean): Result<Principal> {
        val userR = userService.getUserByUserId(userId, UserAction.GENERIC_GET_PRINCIPAL)
        if (userR.hasError) return Result.error(userR.error)
        if (userR.notFound) return Result.notFound()
        val user = userR.value

        val principal = createPrincipalFromUser(user, cacheInMemory)
        return principal
    }

    /**
     * Get user principal from database using user object
     */
    fun createPrincipalFromUser(user: User, cacheInMemory: Boolean): Result<Principal> {
        val rolesR = userRoleService.getRolesByUserId(user.userId)
        if (rolesR.isNotSuccessful) return Result.error(rolesR.error)
        val roles = rolesR.value


        val principal = let {
            principalMap[user.userId]?.first
        } ?: let {
            val principal = Principal(
                userId = user.userId,
                email = user.email,
                username = user.username,
                mfaTotpStatus = user.mfaTotpStatus,
                mfaTotpRequired = user.mfaTotpRequired,
                isBanned = user.isBanned,
                roles = roles.map { it.roleId }.toMutableList()
            )

            if (cacheInMemory) principalMap[user.userId] = (principal to unixNow())
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
        return getPrincipalFromMemoryByUserId(authToken.userId)
    }

    private fun getPrincipalFromMemoryByUserId(userId: Ulid): Principal? = principalMap[userId]?.first

    fun logoutUser(token: String): Result<Unit> {
        authTokenService.deleteToken(token, UserAction.LOGIN).let {
            if (it.isNotSuccessful) return it
        }

        tokenToUserIdMap.remove(token)
        return Result.ok()
    }

    fun logoutUserByUserId(userId: Ulid): Result<Unit> {
        authTokenService.removeTokensByUserId(userId).let {
            if (it.isNotSuccessful) return it
        }

        tokenToUserIdMap.removeIf { key, value -> value.userId == userId }
        return Result.ok()
    }
}