package org.filemat.server.module.admin.controller

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.service.SensitiveAuthService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.setting.service.component.ThumbCacheSettingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AdminSettingsControllerContentUrlTest {

    private val settingService = mockk<SettingService>(relaxed = true)
    private val sensitiveAuthService = mockk<SensitiveAuthService>(relaxed = true)
    private val controller = AdminSettingsController(
        mockk<LogService>(relaxed = true),
        settingService,
        mockk<FileVisibilityService>(relaxed = true),
        sensitiveAuthService,
        mockk<ThumbCacheSettingService>(relaxed = true),
        mockk<ThumbnailService>(relaxed = true),
    )

    private val previousUrl = State.App.ContentBaseUrl.url
    private val previousUnauth = State.App.ContentBaseUrl.forUnauthenticated
    private val previousUpload = runCatching { State.App.uploadFolderPath }.getOrNull()

    @AfterEach
    fun restore() {
        State.App.ContentBaseUrl.url = previousUrl
        State.App.ContentBaseUrl.forUnauthenticated = previousUnauth
        if (previousUpload != null) State.App.uploadFolderPath = previousUpload
    }

    @Test
    fun `state get returns the full system bundle`() {
        State.App.uploadFolderPath = "/tmp/filemat"
        State.App.ContentBaseUrl.url = "https://203.0.113.10:8080"
        State.App.ContentBaseUrl.forUnauthenticated = true

        val body = Json.parseToJsonElement(controller.adminGetSystemStateMapping(request()).body!!).jsonObject
        val contentBaseUrl = body["contentBaseUrl"]!!.jsonObject
        assertEquals("/tmp/filemat", body["uploadFolderPath"]!!.jsonPrimitive.content)
        assertEquals("https://203.0.113.10:8080", contentBaseUrl["url"]!!.jsonPrimitive.content)
        assertEquals(true, contentBaseUrl["forUnauthenticated"]!!.jsonPrimitive.boolean)
        assertTrue(contentBaseUrl.containsKey("lockedByEnv"))
        assertTrue(contentBaseUrl.containsKey("forUnauthenticatedLockedByEnv"))
    }

    @Test
    fun `set content base URL rejects an invalid OTP`() {
        every { sensitiveAuthService.verifyOtp("bad-code-bad-cod") } returns Result.reject("Code is invalid.")

        val response = controller.adminSetContentBaseUrlMapping(
            request(),
            "bad-code-bad-cod",
            "https://example.com",
        )

        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { settingService.set_contentBaseUrl(any(), any()) }
    }

    @Test
    fun `set content base URL calls the setting service after OTP`() {
        every { sensitiveAuthService.verifyOtp("ABCDEFGH12345678") } returns Result.ok(1L)
        every { settingService.set_contentBaseUrl(any(), "https://example.com") } returns Result.ok("https://example.com")

        val response = controller.adminSetContentBaseUrlMapping(
            request("https://admin-set-cors.example"),
            "ABCDEFGH12345678",
            "https://example.com",
        )

        assertEquals(200, response.statusCode.value())
        verify { settingService.set_contentBaseUrl(any(), "https://example.com") }
        assertTrue(CorsOriginRegistry.isAllowed("https://admin-set-cors.example"))
    }

    @Test
    fun `set content base URL does not record Origin when OTP fails`() {
        every { sensitiveAuthService.verifyOtp("bad-code-bad-cod") } returns Result.reject("Code is invalid.")
        val origin = "https://admin-set-cors-fail.example"

        val response = controller.adminSetContentBaseUrlMapping(
            request(origin),
            "bad-code-bad-cod",
            "https://example.com",
        )

        assertEquals(401, response.statusCode.value())
        assertFalse(CorsOriginRegistry.isAllowed(origin))
    }

    private fun request(origin: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        if (origin != null) request.addHeader("Origin", origin)
        request.setAttribute(
            "auth",
            Principal(
                userId = UlidCreator.getUlid(),
                email = "admin@test",
                username = "admin",
                mfaTotpStatus = false,
                mfaTotpRequired = false,
                isBanned = false,
                roles = mutableListOf(),
                homeFolderPath = null,
            ),
        )
        return request
    }
}
