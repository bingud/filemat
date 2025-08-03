package org.filemat.server.module.admin.service


import org.filemat.server.config.Props
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.classes.wrappers.ArgonHash
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.MiniUser
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

@Service
class AdminUserService(
    private val userRepositoryInterface: UserRepository,
    private val logService: LogService,
    private val publicUserRepository: PublicUserRepository,
    private val userRoleService: UserRoleService,
    private val userService: UserService
) {

    fun createUser(creator: Principal, email: String, username: String, password: ArgonHash): Result<Ulid> {
        // Check if email or password exists already
        userService.checkExistsByEmailOrUsername(email, username).let {
            if (it.isNotSuccessful) return it.cast()
            val pair = it.value
            if (pair.first) return Result.reject("Email is already registered.")
            if (pair.second) return Result.reject("Username is already registered.")
        }

        val userId = UlidCreator.getUlid()

        try {
            userRepositoryInterface.createUser(
                userId = userId,
                email = email,
                username = username,
                password = password.password,
                mfaTotpSecret = null,
                mfaTotpStatus = false,
                mfaTotpCodes = null,
                createdDate = unixNow(),
                lastLoginDate = null,
                isBanned = false
            )
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.CREATE_USER,
                description = "Failed to create user in the database.",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to create user.")
        }

        userRoleService.assign(
            userId = userId,
            roleId = Props.Roles.userRoleId,
            action = UserAction.CREATE_USER,
        )

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.CREATE_USER,
            description = "User ${creator.username} created new user: $username",
            message = "User '${creator.username}' created user '$username' with email '$email'",
            initiatorId = creator.userId,
            initiatorIp = null,
            targetId = userId,
            meta = null
        )

        return userId.toResult()
    }

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
                action = UserAction.GET_USER,
                description = "Failed to get user by ID",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to load user.")
        }
    }

}