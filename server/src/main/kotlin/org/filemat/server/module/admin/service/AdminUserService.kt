package org.filemat.server.module.admin.service


import org.filemat.server.config.Props
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.onFailure
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.Validator
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.MfaService
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.log.service.meta
import org.filemat.server.module.permission.model.hasSufficientPermissionsFor
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

@Service
class AdminUserService(
    private val userRepositoryInterface: UserRepository,
    private val logService: LogService,
    private val publicUserRepository: PublicUserRepository,
    private val userRoleService: UserRoleService,
    private val userService: UserService,
    private val authService: AuthService,
    private val passwordEncoder: PasswordEncoder,
    private val mfaService: MfaService,
    private val entityService: EntityService,
    private val savedFileService: SavedFileService,
    private val entityPermissionService: EntityPermissionService,
) {
    fun updateProperty(meta: RequestMeta, property: String, value: String): Result<Unit> {
        // Check if user exists
        userService.getUserByUserId(meta.targetUserId, meta.action)
            .let {
                if (it.notFound) return Result.reject("This user does not exist.")
                if (it.hasError) return it.cast()
                it.value
            }

        // Update property
        val result: Result<Unit> =
            when (property) {
                "username" -> {
                    meta.action = UserAction.UPDATE_USERNAME
                    userService.changeUsername(meta, value)
                }

                "email" -> {
                    meta.action = UserAction.UPDATE_EMAIL
                    userService.changeEmail(meta, value)
                }

                "homeFolderPath" -> {
                    meta.action = UserAction.UPDATE_HOME_FOLDER_PATH
                    userService.changeHomeFolderPath(meta, FilePath.of(value), true).cast()
                }

                else -> return Result.reject("Invalid property.")
            }

        if (result.isSuccessful) {
            logService.info(
                type = LogType.AUDIT,
                action = UserAction.UPDATE_ACCOUNT_PROPERTY,
                description = "Admin updated user property: $property",
                message = "",
                initiatorId = meta.initiatorId,
                initiatorIp = meta.ip,
                targetId = meta.targetId,
                meta = meta("property" to property, "value" to value)
            )
        }

        return result
    }

    fun resetTotpMfa(meta: RequestMeta, enforce: Boolean): Result<Unit> {
        return mfaService.updateUserMfa(
            meta = meta,
            status = false,
            secret = null,
            codes = null,
            isRequired = enforce
        ).also {
            if (it.isSuccessful) {
                authService.logoutUserByUserId(meta.targetUserId)

                logService.info(
                    type = LogType.AUDIT,
                    action = meta.action,
                    description = "Admin reset user TOTP",
                    initiatorId = meta.initiatorId,
                    initiatorIp = meta.ip,
                    targetId = meta.targetId,
                )
            } else {
                logService.error(
                    type = LogType.AUDIT,
                    action = meta.action,
                    description = "Admin reset user TOTP (failed)",
                    message = it.errorOrNull ?: "No error message",
                    initiatorId = meta.initiatorId,
                    initiatorIp = meta.ip,
                    targetId = meta.targetId,
                )
            }
        }
    }

    fun createUser(admin: Principal, email: String, username: String, password: ArgonHash): Result<Ulid> {
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
                mfaTotpRequired = false,
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
        ).let {
            if (it.isNotSuccessful) {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.CREATE_USER,
                    description = "Created user '$username' but failed to assign the default user role.",
                    message = it.errorOrNull ?: "No error message",
                    initiatorId = admin.userId,
                    targetId = userId,
                )
            }
        }

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.CREATE_USER,
            description = "User ${admin.username} created new user: $username",
            message = "User '${admin.username}' created user '$username' with email '$email'",
            initiatorId = admin.userId,
            initiatorIp = null,
            targetId = userId,
            meta = null
        )

        return userId.toResult()
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

    fun changeUserPassword(adminId: Ulid, adminIp: String, userId: Ulid, rawPassword: String, logout: Boolean, userAction: UserAction): Result<Unit> {
        return let {
            Validator.password(rawPassword)?.let { return Result.reject(it) }
            val password = ArgonHash(passwordEncoder.encode(rawPassword))

            userService.changePassword(userId, password, userAction).onFailure {
                return@let it
            }

            if (logout) {
                authService.logoutUserByUserId(userId).onFailure { return it }
            }
            return@let Result.ok()
        }.also { result ->
            if (result.isSuccessful) {
                logService.info(
                    type = LogType.AUDIT,
                    action = userAction,
                    description = "Changed user password",
                    initiatorId = adminId,
                    initiatorIp = adminIp,
                    targetId = userId,
                    meta = meta("logout" to logout.toString())
                )
            } else {
                logService.error(
                    type = LogType.AUDIT,
                    action = userAction,
                    description = "Failed to change user password",
                    message = result.errorOrNull ?: "No error message",
                    initiatorId = adminId,
                    initiatorIp = adminIp,
                    targetId = userId,
                    meta = meta("logout" to logout.toString()),
                )
            }
        }
    }

    fun deleteUser(admin: Principal, adminIp: String?, targetUserId: Ulid): Result<Unit> {
        if (admin.userId == targetUserId) return Result.reject("You cannot delete your own account.")

        val targetUser = getUser(targetUserId).let {
            if (it.notFound) return Result.notFound()
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val targetPrincipal = authService.getPrincipalByUserId(targetUserId, cacheInMemory = false).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        if (!admin.getPermissions().hasSufficientPermissionsFor(targetPrincipal.getPermissions())) {
            return Result.reject("Cannot delete a user with higher permissions than you have.")
        }

        userRoleService.getRolesByUserId(targetUserId).let {
            if (it.isNotSuccessful) return it.cast()
            if (it.value.any { role -> role.roleId == Props.Roles.adminRoleId }) {
                val adminUsers = userRoleService.getRoleUsers(Props.Roles.adminRoleId)
                val adminCount = if (adminUsers.isSuccessful) adminUsers.value.size else 0
                if (adminCount <= 1) return Result.reject("Cannot delete the last admin user.")
            }
        }

        val ownedEntities = try {
            entityService.getByOwnerUserId(targetUserId)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.DELETE_USER,
                description = "Failed to load owned file entities before user deletion.",
                message = e.stackTraceToString(),
                initiatorId = admin.userId,
                targetId = targetUserId,
            )
            return Result.error("Failed to delete user.")
        }

        try {
            userRepositoryInterface.deleteById(targetUserId)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.DELETE_USER,
                description = "Failed to delete user from database.",
                message = e.stackTraceToString(),
                initiatorId = admin.userId,
                initiatorIp = adminIp,
                targetId = targetUserId,
            )
            return Result.error("Failed to delete user.")
        }

        authService.logoutUserByUserId(targetUserId).onFailure { return it }
        authService.removePrincipalFromMemory(targetUserId)
        savedFileService.removeAllByUserIdFromCache(targetUserId)
        entityPermissionService.removeAllPermissionsForUser(targetUserId)
        entityService.clearOwnerFromCache(ownedEntities)

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.DELETE_USER,
            description = "Deleted user account: ${targetUser.username}",
            message = "User '${admin.username}' deleted user '${targetUser.username}'.",
            initiatorId = admin.userId,
            initiatorIp = adminIp,
            targetId = targetUserId,
        )

        return Result.ok()
    }
}