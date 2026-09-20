package org.filemat.server.common.controller

import com.github.f4b6a3.ulid.UlidCreator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.filemat.server.common.State
import org.filemat.server.module.auth.model.Principal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class StateControllerContentBaseUrlTest {

    private val controller = StateController()
    private val previousUrl = State.App.ContentBaseUrl.url
    private val previousUnauth = State.App.ContentBaseUrl.forUnauthenticated
    private val previousSetup = runCatching { State.App.isSetup }.getOrNull()

    @BeforeEach
    fun setup() {
        State.App.isSetup = true
        State.App.ContentBaseUrl.url = "https://203.0.113.10:8080"
        State.App.ContentBaseUrl.forUnauthenticated = false
    }

    @AfterEach
    fun restore() {
        State.App.ContentBaseUrl.url = previousUrl
        State.App.ContentBaseUrl.forUnauthenticated = previousUnauth
        if (previousSetup != null) State.App.isSetup = previousSetup
    }

    @Test
    fun `authenticated select receives the stored content base URL even when the unauthenticated toggle is off`() {
        val request = MockHttpServletRequest()
        request.setAttribute("auth", principal())

        val body = selectApp(request)
        assertEquals("https://203.0.113.10:8080", body)
    }

    @Test
    fun `unauthenticated select hides the content base URL when the unauthenticated toggle is off`() {
        val body = selectApp(MockHttpServletRequest())
        assertEquals(null, body)
    }

    @Test
    fun `unauthenticated select receives the URL when the unauthenticated toggle is on`() {
        State.App.ContentBaseUrl.forUnauthenticated = true
        val body = selectApp(MockHttpServletRequest())
        assertEquals("https://203.0.113.10:8080", body)
    }

    private fun selectApp(request: MockHttpServletRequest): String? {
        val response = controller.optionalStateMapping(
            request,
            null,
            null,
            null,
            null,
            "true",
            null,
        )
        val root = Json.parseToJsonElement(response.body!!).jsonObject
        return root["app"]!!.jsonObject["value"]!!.jsonObject["contentBaseUrl"]?.jsonPrimitive?.content
    }

    private fun principal() = Principal(
        userId = UlidCreator.getUlid(),
        email = "user@test",
        username = "user",
        mfaTotpStatus = false,
        mfaTotpRequired = false,
        isBanned = false,
        roles = mutableListOf(),
        homeFolderPath = null,
    )
}
