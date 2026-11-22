package org.filemat.server.module.admin.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.config.Props
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.MfaService
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.savedFile.SavedFileService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.PublicUserRepository
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.principalWith
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder

class AdminUserServiceTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val userRoleService = mockk<UserRoleService>()
    private val userService = mockk<UserService>()
    private val authService = mockk<AuthService>(relaxed = true)
    private val mfaService = mockk<MfaService>(relaxed = true)
    private val state = State(env = emptyMap())
    private val adminUserService = AdminUserService(
        userRepositoryInterface = userRepository,
        logService = logService,
        publicUserRepository = mockk<PublicUserRepository>(relaxed = true),
        userRoleService = userRoleService,
        userService = userService,
        authService = authService,
        passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
        mfaService = mfaService,
        entityService = mockk<EntityService>(relaxed = true),
        savedFileService = mockk<SavedFileService>(relaxed = true),
        entityPermissionService = mockk<EntityPermissionService>(relaxed = true),
        state = state,
    )

    @Test
    fun `createUser still succeeds when role assignment fails`() {
        every { userService.checkExistsByEmailOrUsername(any(), any()) } returns Result.ok(false to false)
        every { userRoleService.assign(any(), any(), any()) } returns Result.error("Failed to assign role to user.")

        val result = adminUserService.createUser(
            admin = testPrincipal(),
            email = "user@test",
            username = "newuser",
            password = ArgonHash("hashed"),
        )

        assertSuccess(result)
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
            admin = testPrincipal(),
            email = "user@test",
            username = "newuser",
            password = ArgonHash("hashed"),
        )

        assertSuccess(result)
        verify(exactly = 1) { userRoleService.assign(result.value, Props.Roles.userRoleId, UserAction.CREATE_USER) }
    }

    private fun requireMfa(admin: Principal, target: Principal): Result<Unit> {
        every { authService.getPrincipalByUserId(target.userId, any()) } returns Result.ok(target)
        every { mfaService.setRequired(any(), any()) } returns Result.ok()
        val meta = RequestMeta(initiatorId = admin.userId, targetId = target.userId, action = UserAction.SET_TOTP_MFA_REQUIRED)
        return adminUserService.setTotpMfaRequired(admin, meta, isRequired = false)
    }

    @Test
    fun `a user manager can't change whether the super admin must have 2FA`() {
        val result = requireMfa(admin = state.principalWith(SystemPermission.MANAGE_USERS), target = state.principalWith(SystemPermission.SUPER_ADMIN))

        assertRejected(result)
        verify(exactly = 0) { mfaService.setRequired(any(), any()) }
    }

    @Test
    fun `a user manager can require 2FA for a user of the same rank`() {
        val result = requireMfa(admin = state.principalWith(SystemPermission.MANAGE_USERS), target = state.principalWith(SystemPermission.MANAGE_USERS))

        assertSuccess(result)
        verify(exactly = 1) { mfaService.setRequired(any(), false) }
    }

}
