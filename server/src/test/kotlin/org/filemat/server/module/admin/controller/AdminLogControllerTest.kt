package org.filemat.server.module.admin.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.module.log.service.LogService
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AdminLogControllerTest {

    private val logService = mockk<LogService>(relaxed = true)
    private val controller = AdminLogController(logService)

    private fun request() = MockHttpServletRequest().apply { setAttribute("auth", testPrincipal(username = "admin")) }

    private fun get(severityList: String, logTypeList: String) = controller.adminGetLogsMapping(
        request(), page = "0", amount = null, searchText = null, rawInitiatorId = null, rawTargetId = null, rawIp = null,
        rawSeverityList = severityList, rawLogTypeList = logTypeList, rawFromDate = null, rawToDate = null,
    )

    @Test
    fun `an unknown severity is a bad request`() {
        val response = get("""["NOT_A_LEVEL"]""", "[]")

        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { logService.getLogs(any(), any()) }
    }

    @Test
    fun `a log type list that is not JSON is a bad request`() {
        val response = get("[]", "AUDIT")

        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { logService.getLogs(any(), any()) }
    }

    @Test
    fun `valid lists are passed on`() {
        every { logService.getLogs(any(), any()) } returns Result.ok(emptyList())

        val response = get("""["ERROR"]""", """["AUDIT"]""")

        assertEquals(200, response.statusCode.value())
    }
}
