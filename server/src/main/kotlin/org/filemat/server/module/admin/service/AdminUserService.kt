package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.MiniUser
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.filemat.server.module.user.repository.UserRepository
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

@Service
class AdminUserService(
    private val userRepository: UserRepository,
    private val logService: LogService,
    private val publicUserRepository: PublicUserRepository
) {

    fun getUserMiniList(list: List<Ulid>): Result<List<MiniUser>> {
        try {
            return publicUserRepository.getMiniUserList(list)?.toResult()
                ?: throw IllegalStateException("Database result for user mini list by user ID list is null instead of empty list.")
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

    fun getUserList(): Result<List<PublicUser>> {
        try {
            return publicUserRepository.findAll().toList().toResult()
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

    fun getUser(userId: Ulid): Result<PublicUser> {
        try {
            return publicUserRepository.findById(userId).getOrNull()?.toResult() ?: Result.notFound()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.LIST_USERS,
                description = "Failed to get user by ID",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }

}