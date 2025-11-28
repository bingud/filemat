package org.filemat.server.module.user.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.MiniUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.springframework.stereotype.Service

@Service
class UserUtilService(
    private val publicUserRepository: PublicUserRepository,
    private val logService: LogService
) {

    fun getUserMiniList(list: List<Ulid>?, allUsers: Boolean = false): Result<List<MiniUser>> {
        try {
            val result = if (allUsers) {
                publicUserRepository.getAllMiniUserList()
            } else if (list != null) {
                publicUserRepository.getMiniUserList(list)
            } else {
                return Result.error("User ID list cannot be null if not requesting a list of all users.")
            }

            return result?.toResult()
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

}