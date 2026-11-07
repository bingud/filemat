package org.filemat.server.module.admin.controller

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.admin.service.AdminUserRoleService
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.testutil.principalWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AdminUserRoleControllerTest {

    private val state = State(env = emptyMap())
    private val service = AdminUserRoleService(mockk<UserRoleService>(), mockk<AuthService>(), mockk<LogService>(relaxed = true), state)
    private val controller = AdminUserRoleController(service, mockk(relaxed = true))

    @Test
    fun `assigning a role above your own rank is forbidden with a reason, not a 404`() {
        val manager = state.principalWith(SystemPermission.MANAGE_USERS)
        val adminRoleId = UlidCreator.getUlid()
        state.auth.roleMap[adminRoleId] = Role(adminRoleId, "admin", unixNow(), listOf(SystemPermission.SUPER_ADMIN))
        val request = MockHttpServletRequest().apply { setAttribute("auth", manager) }

        val response = controller.adminAssignUserRoleMapping(request, UlidCreator.getUlid().toString(), adminRoleId.toString())

        assertEquals(403, response.statusCode.value())
        assertTrue(response.body!!.contains("You can't assign a role with higher permissions than your own."))
    }
}
