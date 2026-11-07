package org.filemat.server.module.admin.controller

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder

class AdminUserControllerTest {

    private val adminUserService = mockk<AdminUserService>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val controller = AdminUserController(adminUserService, mockk<UserRoleService>(relaxed = true), passwordEncoder)

    private fun request() = MockHttpServletRequest().apply { setAttribute("auth", testPrincipal(username = "admin")) }

    @Test
    fun `create refuses an invalid e-mail`() {
        val response = controller.adminCreateUserMapping(request(), "a@b@c", "john", "password")

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body!!.contains("Email is invalid."))
        verify(exactly = 0) { adminUserService.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `create refuses an invalid username`() {
        val response = controller.adminCreateUserMapping(request(), "john@example.com", "john smith", "password")

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body!!.contains("Username contains invalid characters."))
        verify(exactly = 0) { adminUserService.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `create refuses an invalid password`() {
        val response = controller.adminCreateUserMapping(request(), "john@example.com", "john", "abc")

        assertEquals(400, response.statusCode.value())
        assertTrue(response.body!!.contains("Password is too short."))
        verify(exactly = 0) { adminUserService.createUser(any(), any(), any(), any()) }
    }

    @Test
    fun `create accepts valid input`() {
        every { passwordEncoder.encode("password") } returns "hash"
        every { adminUserService.createUser(any(), any(), any(), any()) } returns Result.ok(UlidCreator.getUlid())

        val response = controller.adminCreateUserMapping(request(), "john@example.com", "john", "password")

        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { adminUserService.createUser(any(), "john@example.com", "john", any()) }
    }
}
