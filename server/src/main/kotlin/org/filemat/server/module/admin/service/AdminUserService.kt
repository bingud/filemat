package org.filemat.server.module.admin.service

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class AdminUserService(
    private val userRepository: UserRepository,
    private val logService: LogService
) {

    fun getUserList(): Result<List<PublicUser>> {
        try {
            return userRepository.getAllUsers().toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.LIST_USERS,
                description = "Failed to get list of all users",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to get list of all users.")
        }
    }

}