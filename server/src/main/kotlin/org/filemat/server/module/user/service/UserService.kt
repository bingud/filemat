package org.filemat.server.module.user.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.Validator
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.common.util.toJsonOrNull
import org.filemat.server.module.auth.service.AuthService
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
    private val authService: AuthService,
) {
    fun changeEmail(meta: RequestMeta, email: String): Result<Unit> {
        Validator.email(email)
            ?.let { return Result.reject(it) }

        getUserByEmail(email, meta.action).let {
            if (it.isSuccessful) return Result.reject("This email is already used.")
            if (it.hasError) return Result.error("Failed to check if this email is already used.")
        }

        try {
            userRepository.updateUsername(meta.userId, email)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = meta.action,
                description = "Failed to update email in the database.",
                message = e.stackTraceToString(),
                targetId = meta.userId,
                initiatorIp = meta.ip,
                initiatorId = meta.adminId
            )
            return Result.error("Failed to update email.")
        }

        authService.updatePrincipal(userId = meta.userId) {
            it.copy(email = email)
        }

        return Result.ok()
    }

    fun changeUsername(meta: RequestMeta, username: String): Result<Unit> {
        Validator.username(username)
            ?.let { return Result.reject(it) }

        getUserByUsername(username, meta.action).let {
            if (it.isSuccessful) return Result.reject("This username is already used.")
            if (it.hasError) return Result.error("Failed to check if this username is already used.")
        }

        try {
            userRepository.updateUsername(meta.userId, username)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = meta.action,
                description = "Failed to update username in the database.",
                message = e.stackTraceToString(),
                targetId = meta.userId,
                initiatorIp = meta.ip,
                initiatorId = meta.adminId
            )
            return Result.error("Failed to update username.")
        }

        authService.updatePrincipal(userId = meta.userId) {
            it.copy(username = username)
        }

        return Result.ok()
    }

    /**
     * @return Email existence, Username existence
     */
    fun checkExistsByEmailOrUsername(email: String, username: String): Result<Pair<Boolean, Boolean>> {
        try {
            return userRepository.exists(email = email, username = username).let { r ->
                if (r == "email") return@let true to false
                if (r == "username") return@let false to true
                return@let false to false
            }.toResult()
        } catch (e: Exception) {
            logService.util.logServiceException("Failed to check whether user exists by email or username", e, UserAction.CHECK_USER_EXISTENCE)
            return Result.error("Failed to check whether user exists already.")
        }
    }

    fun setLastLoginDate(userId: Ulid, date: Long) {
        try {
            userRepository.updateLastLoginDate(userId, date)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.LOGIN,
                description = "Failed to set last login date",
                message = e.stackTraceToString()
            )
        }
    }

    fun createUser(user: User, action: UserAction?): Result<Unit> {
        try {
            userRepository.createUser(
                userId = user.userId,
                email = user.email,
                username = user.username,
                password = user.password,
                mfaTotpSecret = user.mfaTotpSecret,
                mfaTotpStatus = user.mfaTotpStatus,
                mfaTotpCodes = user.mfaTotpCodes.toJsonOrNull(),
                createdDate = user.createdDate,
                lastLoginDate = user.lastLoginDate,
                isBanned = user.isBanned,
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

    fun changePassword(userId: Ulid, password: ArgonHash, userAction: UserAction?): Result<Unit> {
        try {
            userRepository.updatePassword(userId, password.password)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ?: UserAction.CHANGE_PASSWORD,
                description = "Failed to get user from by user ID",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }
}