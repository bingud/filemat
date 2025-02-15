package org.filemat.server.module.user.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.toInt
import org.filemat.server.common.util.toJsonOrNull
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val logService: LogService,
) {

    fun createUser(user: User, action: UserAction?): Result<Unit> {
        try {
            userRepository.createUser(
                userId = user.userId.toString(),
                email = user.email,
                username = user.username,
                password = user.password,
                mfaTotpSecret = user.mfaTotpSecret,
                mfaTotpStatus = user.mfaTotpStatus,
                mfaTotpCodes = user.mfaTotpCodes.toJsonOrNull(),
                createdDate = user.createdDate,
                lastLoginDate = user.lastLoginDate,
                isBanned = user.isBanned.toInt(),
            )

            return Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(type = LogType.SYSTEM, action = action ?: UserAction.GENERIC_ACCOUNT_CREATION, description = "Failed to insert user to database", message = e.stackTraceToString())
            return Result.error("Failed to save user account.")
        }
    }

    fun getUserByUsername(username: String, userAction: UserAction?): Result<User> {
        try {
            return userRepository.getByUsername(username)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ?: UserAction.NONE,
                description = "Failed to get user from database by username",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }

    fun getUserByEmail(email: String, userAction: UserAction?): Result<User> {
        try {
            return userRepository.getByEmail(email)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ?: UserAction.NONE,
                description = "Failed to get user from by email",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }

    fun getUserByUserId(userId: Ulid, userAction: UserAction?): Result<User> {
        try {
            return userRepository.getByUserId(userId)?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ?: UserAction.NONE,
                description = "Failed to get user from by user ID",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }
}