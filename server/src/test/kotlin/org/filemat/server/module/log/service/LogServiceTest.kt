package org.filemat.server.module.log.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.module.admin.model.LogMeta
import org.filemat.server.module.log.repository.LogRepository
import org.filemat.server.module.user.model.UserAction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogServiceTest {

    private val logRepository = mockk<LogRepository>(relaxed = true)
    private val service = LogService(logRepository, State(env = emptyMap()))

    @Test
    fun `a failed log query is logged with the requesting user as initiator`() {
        val adminId = UlidCreator.getUlid()
        val targetId = UlidCreator.getUlid()
        every { logRepository.getPage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("db down")

        val result = service.getLogs(
            RequestMeta(initiatorId = adminId, targetId = targetId, ip = "127.0.0.1", action = UserAction.GET_LOGS),
            LogMeta(page = 0, amount = 50, searchText = null, userId = null, targetId = null, ip = null, severityList = emptyList(), logTypeList = emptyList(), fromDate = null, toDate = null),
        )

        assertTrue(result.hasError)
        verify(timeout = 5_000) {
            logRepository.insertLog(
                level = any(), type = any(), action = any(), createdDate = any(), description = "Failed to get logs from database.",
                message = any(), initiatorId = adminId.toString(), initiatorIp = "127.0.0.1", targetId = any(), meta = any(),
            )
        }
    }
}
