package org.filemat.server.module.auth.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.repository.AuthTokenRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class AuthTokenService(private val logService: LogService, private val authTokenRepository: AuthTokenRepository) {

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

}