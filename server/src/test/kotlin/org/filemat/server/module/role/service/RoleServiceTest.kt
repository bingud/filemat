package org.filemat.server.module.role.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.repository.RoleRepository
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoleServiceTest {

    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val authService = mockk<AuthService>(relaxed = true)
    private val state = State(env = emptyMap())
    private val roleService = RoleService(roleRepository, logService, authService, state)

    @Test
    fun `updatePermissionList rejects newList with higher max level than editor`() {
        val editorRoleId = UlidCreator.getUlid()
        val targetRoleId = UlidCreator.getUlid()
        val userId = UlidCreator.getUlid()
        val now = unixNow()

        state.auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )
        state.auth.roleMap[targetRoleId] = Role(
            roleId = targetRoleId,
            name = "target",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )

        val principal = testPrincipal(userId = userId, username = "editor", roles = listOf(editorRoleId))

        val result = roleService.updatePermissionList(
            user = principal,
            roleId = targetRoleId,
            newList = listOf(SystemPermission.SUPER_ADMIN),
            userAction = UserAction.UPDATE_ROLE_PERMISSIONS,
        )

        assertRejected(result)
        assertEquals("Cannot update role with higher permissions than you have.", result.error)
        verify(exactly = 0) { roleRepository.updatePermissions(any(), any()) }
    }

    @Test
    fun `remove rejects deleting the user system role`() {
        assertSystemRoleCannotBeDeleted(Props.Roles.userRoleId, "user")
    }

    @Test
    fun `remove rejects deleting the admin system role`() {
        assertSystemRoleCannotBeDeleted(Props.Roles.adminRoleId, "admin")
    }

    @Test
    fun `remove deletes a custom role`() {
        val editorRoleId = UlidCreator.getUlid()
        val targetRoleId = UlidCreator.getUlid()
        val now = unixNow()

        state.auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )
        state.auth.roleMap[targetRoleId] = Role(
            roleId = targetRoleId,
            name = "custom",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )

        val result = roleService.remove(
            user = principalWithRole(editorRoleId),
            roleId = targetRoleId,
            userAction = UserAction.DELETE_ROLE,
        )

        assertSuccess(result)
        verify(exactly = 1) { roleRepository.deleteById(targetRoleId) }
        verify(exactly = 1) { authService.removeRoleFromAllPrincipals(targetRoleId) }
    }

    private fun assertSystemRoleCannotBeDeleted(roleId: Ulid, name: String) {
        val editorRoleId = UlidCreator.getUlid()
        val now = unixNow()

        state.auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = SystemPermission.entries,
        )
        state.auth.roleMap[roleId] = Role(
            roleId = roleId,
            name = name,
            createdDate = now,
            permissions = emptyList(),
        )

        val result = roleService.remove(
            user = principalWithRole(editorRoleId),
            roleId = roleId,
            userAction = UserAction.DELETE_ROLE,
        )

        assertRejected(result)
        assertEquals("Cannot delete a system role.", result.error)
        verify(exactly = 0) { roleRepository.deleteById(any<Ulid>()) }
    }

    private fun principalWithRole(roleId: Ulid) = testPrincipal(username = "editor", roles = listOf(roleId))
}
