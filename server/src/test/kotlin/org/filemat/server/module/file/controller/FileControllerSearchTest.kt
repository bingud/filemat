package org.filemat.server.module.file.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.RateLimiter
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Search is open to anonymous users only through a share link.
 */
class FileControllerSearchTest {

    private val fileService = mockk<FileService>()
    private val controller = FileController(
        fileService = fileService,
        tusService = mockk(),
        filesystemService = mockk(),
        fileLockService = FileLockService(),
        rateLimiter = RateLimiter(),
    )

    private fun search(user: Principal?, path: String = "/missing") = controller.searchFilesMapping(
        request = MockHttpServletRequest().apply { if (user != null) setAttribute("auth", user) },
        rawPath = path,
        shareToken = null,
        text = "a",
    ).statusCode.value()

    @Test
    fun `search without a share link needs a signed-in user`() {
        assertEquals(401, search(user = null))
        verify(exactly = 0) { fileService.resolvePathWithOptionalShare(any(), any(), any()) }
    }

    @Test
    fun `search is rate-limited per user`() {
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } returns Result.notFound()
        val user = testPrincipal()

        val codes = (1..31).map { search(user) }

        assertEquals(400, codes.first())
        assertEquals(429, codes.last())
        assertEquals(400, search(testPrincipal()))
    }
}
