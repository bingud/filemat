package org.filemat.server.module.admin.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Test

class AdminUserRoleServiceTest {

    private val userRoleService = mockk<UserRoleService>(relaxed = true)
    private val authService = mockk<AuthService>(relaxed = true)
    private val state = State(env = emptyMap()).apply {
        auth.roleMap[Props.Roles.adminRoleId] = Role(Props.Roles.adminRoleId, "admin", unixNow(), SystemPermission.entries)
    }
    private val service = AdminUserRoleService(userRoleService, authService, mockk<LogService>(relaxed = true), state)

    private val admin = testPrincipal(username = "admin", roles = listOf(Props.Roles.adminRoleId))

    @Test
    fun `the admin role can't be removed from the last admin, not even by themselves`() {
        every { authService.getPrincipalByUserId(admin.userId, any()) } returns Result.ok(admin)
        every { userRoleService.getRoleUsers(Props.Roles.adminRoleId) } returns Result.ok(listOf(admin.userId))

        assertRejected(service.removeRoles(admin, admin.userId, listOf(Props.Roles.adminRoleId)))
        verify(exactly = 0) { userRoleService.removeList(any(), any(), any()) }
    }

    @Test
    fun `the admin role can be removed while another admin is left`() {
        val other = testPrincipal(username = "other", roles = listOf(Props.Roles.adminRoleId))
        every { authService.getPrincipalByUserId(other.userId, any()) } returns Result.ok(other)
        every { userRoleService.getRoleUsers(Props.Roles.adminRoleId) } returns Result.ok(listOf(admin.userId, other.userId))
        every { userRoleService.removeList(any(), any(), any()) } returns Result.ok()

        assertSuccess(service.removeRoles(admin, other.userId, listOf(Props.Roles.adminRoleId)))
        verify { userRoleService.removeList(other.userId, listOf(Props.Roles.adminRoleId), any()) }
    }

    @Test
    fun `removing another role from the last admin is allowed`() {
        val roleId = UlidCreator.getUlid()
        state.auth.roleMap[roleId] = Role(roleId, "viewers", unixNow(), emptyList())
        every { authService.getPrincipalByUserId(admin.userId, any()) } returns Result.ok(admin)
        every { userRoleService.removeList(any(), any(), any()) } returns Result.ok()

        assertSuccess(service.removeRoles(admin, admin.userId, listOf(roleId)))
        verify(exactly = 0) { userRoleService.getRoleUsers(any()) }
    }
}
