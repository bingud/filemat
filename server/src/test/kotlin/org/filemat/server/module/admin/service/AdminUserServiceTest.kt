package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.MfaService
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder

class AdminUserServiceTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val userRoleService = mockk<UserRoleService>()
    private val userService = mockk<UserService>()
    private val adminUserService = AdminUserService(
        userRepositoryInterface = userRepository,
        logService = logService,
        publicUserRepository = mockk<PublicUserRepository>(relaxed = true),
        userRoleService = userRoleService,
        userService = userService,
        authService = mockk<AuthService>(relaxed = true),
        passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
        mfaService = mockk<MfaService>(relaxed = true),
        entityService = mockk<EntityService>(relaxed = true),
        savedFileService = mockk<SavedFileService>(relaxed = true),
        entityPermissionService = mockk<EntityPermissionService>(relaxed = true),
    )

    @Test
    fun `createUser still succeeds when role assignment fails`() {
        every { userService.checkExistsByEmailOrUsername(any(), any()) } returns Result.ok(false to false)
        every { userRoleService.assign(any(), any(), any()) } returns Result.error("Failed to assign role to user.")

        val result = adminUserService.createUser(
            admin = admin(),
            email = "user@test",
            username = "newuser",
            password = ArgonHash("hashed"),
        )

        assertTrue(result.isSuccessful)
        verify(exactly = 1) { userRepository.createUser(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { userRoleService.assign(result.value, Props.Roles.userRoleId, UserAction.CREATE_USER) }
        verify(exactly = 1) {
            logService.info(
                type = any(),
                action = UserAction.CREATE_USER,
                description = any(),
                message = any(),
                initiatorId = any(),
                initiatorIp = any(),
                targetId = result.value,
                meta = any(),
            )
        }
    }

    @Test
    fun `createUser returns user id when insert and role assignment succeed`() {
        every { userService.checkExistsByEmailOrUsername(any(), any()) } returns Result.ok(false to false)
        every { userRoleService.assign(any(), any(), any()) } returns Result.ok()

        val result = adminUserService.createUser(
            admin = admin(),
            email = "user@test",
            username = "newuser",
            password = ArgonHash("hashed"),
        )

        assertTrue(result.isSuccessful)
        verify(exactly = 1) { userRoleService.assign(result.value, Props.Roles.userRoleId, UserAction.CREATE_USER) }
    }

    private fun admin() = Principal(
        userId = UlidCreator.getUlid(),
        email = "admin@test",
        username = "admin",
        mfaTotpStatus = false,
        mfaTotpRequired = false,
        isBanned = false,
        roles = mutableListOf(),
        homeFolderPath = null,
    )
}
