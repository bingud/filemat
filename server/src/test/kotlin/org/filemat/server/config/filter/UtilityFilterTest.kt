package org.filemat.server.config.filter

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.controller.ResourceController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

class UtilityFilterTest {

    @Test
    fun `closes the static file stream after copying it`() {
        var closed = false
        val resource = object : ByteArrayResource("<html></html>".toByteArray()) {
            override fun getInputStream(): InputStream = object : ByteArrayInputStream(byteArray) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        val resourceController = mockk<ResourceController>()
        every { resourceController.serveStaticFiles(any()) } returns ResponseEntity.ok(resource)
        val state = State(env = emptyMap()).apply { app.isInitialized = true }
        val filter = UtilityFilter(resourceController, state)

        val request = MockHttpServletRequest("GET", "/files").apply { servletPath = "/files" }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())

        assertEquals("<html></html>", response.contentAsString)
        assertTrue(closed)
    }
}
