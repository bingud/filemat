package org.filemat.server.common.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.config.platform.FilenameRules
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class StateControllerTest {

    private val state = State(env = emptyMap())
    private val controller = StateController(state)

    @BeforeEach
    fun setup() {
        state.app.isSetup = true
        state.app.contentBaseUrl.url = "https://203.0.113.10:8080"
        state.app.contentBaseUrl.forUnauthenticated = false
    }

    @Test
    fun `authenticated select receives the stored content base URL even when the unauthenticated toggle is off`() {
        val request = MockHttpServletRequest()
        request.setAttribute("auth", testPrincipal())

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
        state.app.contentBaseUrl.forUnauthenticated = true
        val body = selectApp(MockHttpServletRequest())
        assertEquals("https://203.0.113.10:8080", body)
    }

    @Test
    fun `select sends the platform with its filename rules`() {
        val platform = selectAppObject(MockHttpServletRequest()).getValue("platform").jsonObject

        assertEquals(Platform.pathStyle, platform.getValue("pathStyle").jsonPrimitive.content)
        assertEquals(Platform.separator, platform.getValue("separator").jsonPrimitive.content)
        assertEquals(PathPolicy.filenameRules, Json.decodeFromJsonElement<FilenameRules>(platform.getValue("filenameRules")))
    }

    @Test
    fun `unauthenticated select hides the paths after setup`() {
        assertNull(selectAppObject(MockHttpServletRequest())["paths"])
    }

    @Test
    fun `unauthenticated select receives the paths before setup`() {
        state.app.isSetup = false

        val paths = selectAppObject(MockHttpServletRequest()).getValue("paths").jsonObject

        assertEquals(Props.setupCodeFile, paths.getValue("setupCodeFile").jsonPrimitive.content)
        assertEquals(Props.authCodeFile, paths.getValue("authCodeFile").jsonPrimitive.content)
        assertEquals(Props.defaultUploadFolderPath, paths.getValue("defaultUploadFolder").jsonPrimitive.content)
        assertEquals(Props.defaultThumbnailCacheFolderPath, paths.getValue("defaultThumbnailCacheFolder").jsonPrimitive.content)
    }

    @Test
    fun `authenticated select receives the paths after setup`() {
        val request = MockHttpServletRequest()
        request.setAttribute("auth", testPrincipal())

        assertNotNull(selectAppObject(request)["paths"])
    }

    private fun selectApp(request: MockHttpServletRequest): String? {
        return selectAppObject(request)["contentBaseUrl"]?.jsonPrimitive?.content
    }

    private fun selectAppObject(request: MockHttpServletRequest): JsonObject {
        val response = controller.optionalStateMapping(
            request,
            null,
            null,
            null,
            null,
            "true",
            null,
        )
        val root = Json.parseToJsonElement(requireNotNull(response.body)).jsonObject
        return root.getValue("app").jsonObject.getValue("value").jsonObject
    }

}
