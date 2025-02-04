package org.filemat.server.module.user.service

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

    fun createUser(user: User, action: UserAction?) {
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
        } catch (e: Exception) {
            logService.error(type = LogType.SYSTEM, action = action ?: UserAction.GENERIC_ACCOUNT_CREATION, description = "Failed to insert user to database", message = e.stackTraceToString())
        }
    }

}