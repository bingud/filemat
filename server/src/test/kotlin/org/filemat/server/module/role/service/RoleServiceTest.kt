package org.filemat.server.module.role.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.repository.RoleRepository
import org.filemat.server.module.user.model.UserAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoleServiceTest {

    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val authService = mockk<AuthService>(relaxed = true)
    private val roleService = RoleService(roleRepository, logService, authService)

    @AfterEach
    fun cleanup() {
        State.Auth.roleMap.clear()
    }

    @Test
    fun `updatePermissionList rejects newList with higher max level than editor`() {
        val editorRoleId = UlidCreator.getUlid()
        val targetRoleId = UlidCreator.getUlid()
        val userId = UlidCreator.getUlid()
        val now = unixNow()

        State.Auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )
        State.Auth.roleMap[targetRoleId] = Role(
            roleId = targetRoleId,
            name = "target",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )

        val principal = Principal(
            userId = userId,
            email = "e@test",
            username = "editor",
            mfaTotpStatus = false,
            mfaTotpRequired = false,
            isBanned = false,
            roles = mutableListOf(editorRoleId),
            homeFolderPath = null,
        )

        val result = roleService.updatePermissionList(
            user = principal,
            roleId = targetRoleId,
            newList = listOf(SystemPermission.SUPER_ADMIN),
            userAction = UserAction.UPDATE_ROLE_PERMISSIONS,
        )

        assertTrue(result.rejected)
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

        State.Auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = listOf(SystemPermission.EDIT_ROLES),
        )
        State.Auth.roleMap[targetRoleId] = Role(
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

        assertTrue(result.isSuccessful)
        verify(exactly = 1) { roleRepository.deleteById(targetRoleId) }
        verify(exactly = 1) { authService.removeRoleFromAllPrincipals(targetRoleId) }
    }

    private fun assertSystemRoleCannotBeDeleted(roleId: Ulid, name: String) {
        val editorRoleId = UlidCreator.getUlid()
        val now = unixNow()

        State.Auth.roleMap[editorRoleId] = Role(
            roleId = editorRoleId,
            name = "editor",
            createdDate = now,
            permissions = SystemPermission.entries,
        )
        State.Auth.roleMap[roleId] = Role(
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

        assertTrue(result.rejected)
        assertEquals("Cannot delete a system role.", result.error)
        verify(exactly = 0) { roleRepository.deleteById(any<Ulid>()) }
    }

    private fun principalWithRole(roleId: Ulid) = Principal(
        userId = UlidCreator.getUlid(),
        email = "e@test",
        username = "editor",
        mfaTotpStatus = false,
        mfaTotpRequired = false,
        isBanned = false,
        roles = mutableListOf(roleId),
        homeFolderPath = null,
    )
}
