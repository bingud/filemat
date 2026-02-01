package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import jakarta.servlet.http.Cookie
import kotlinx.coroutines.*
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.repository.AuthTokenRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class AuthTokenService(private val logService: LogService, private val authTokenRepository: AuthTokenRepository) {
    private final val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @EventListener(ApplicationReadyEvent::class)
    private fun initialize() {
        task_clearExpiredTokens()
    }

    private fun task_clearExpiredTokens() {
        scope.launch {
            var loggedFailure = false

            while (true) {
                runCatching {
                    val now = unixNow()
                    authTokenRepository.clearExpiredTokens(now)
                    loggedFailure = false
                }.onFailure {
                    if (!loggedFailure) {
                        logService.error(
                            type = LogType.SYSTEM,
                            action = UserAction.NONE,
                            description = "Failed to clear expired auth tokens",
                            message = it.stackTraceToString(),
                        )
                        loggedFailure = true
                    }
                }

                // 10 minutes
                delay(600000)
            }
        }
    }

    fun getToken(token: String): Result<AuthToken> {
        try {
            val result = authTokenRepository.getToken(token, unixNow())
                ?: return Result.notFound()
            return result.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.AUTH,
                description = "Failed to get auth token from database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to load authentication token.")
        }
    }

    fun removeTokensByUserId(userId: Ulid): Result<Unit> {
        try {
            val result = authTokenRepository.removeTokensByUserId(userId.toString())
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.LOGOUT_USER_SESSIONS,
                description = "Failed to get auth token from database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to remove auth tokens from the database.")
        }
    }

    fun createToken(userId: Ulid, userAgent: String?, userAction: UserAction?): Result<AuthToken> {
        val token = AuthToken(
            authToken = StringUtils.randomString(128),
            userId = userId,
            createdDate = unixNow(),
            userAgent = userAgent ?: "",
            maxAge = 31557600, // 1 Year
        )

        try {
            authTokenRepository.insertToken(token = token.authToken, userId = userId.toString(), date = token.createdDate, ua = token.userAgent, maxAge = token.maxAge)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ?: UserAction.NONE,
                description = "Failed to save auth token in database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to save auth token.")
        }

        return token.toResult()
    }

    fun createCookie(token: String, maxAge: Long): Cookie {
        return Cookie(Props.Cookies.authToken, token).apply {
            secure = true
            isHttpOnly = true
            path = "/"
            this.maxAge = maxAge.toInt()
            setAttribute("SameSite", "Lax")
        }
    }

    fun deleteToken(token: String, userAction: UserAction): Result<Unit> {
        try {
            authTokenRepository.deleteToken(token)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to delete auth token from database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to delete login from the database.")
        }
    }
}