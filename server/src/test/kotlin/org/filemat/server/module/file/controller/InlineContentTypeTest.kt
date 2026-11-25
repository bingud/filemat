package org.filemat.server.module.file.controller

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.RateLimiter
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * A file opened inline must not run scripts on the app's origin (R13)
 */
class InlineContentTypeTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private val controller = FileController(
        fileService = fileService,
        tusService = mockk(),
        filesystemService = filesystemService,
        fileLockService = FileLockService(),
        rateLimiter = RateLimiter(),
    )

    private fun serve(name: String, content: String, download: String? = null, range: String? = null): Pair<String?, String?> {
        val file = Files.writeString(tempDir.resolve(name), content)
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } returns Result.ok(FilePath.ofAlreadyNormalized(file))
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.getSize(any()) } returns Result.ok(Files.size(file))
        every { fileService.getFileContent(any(), any(), any(), any(), any(), any()) } answers { Result.ok(Files.newInputStream(file)) }

        val request = MockHttpServletRequest().apply { range?.let { addHeader("Range", it) } }
        val response = controller.streamFileContentMapping(request, "/$name", "share-token", download)
        return response.headers.contentType?.toString() to response.headers.getFirst("X-Content-Type-Options")
    }

    private val page = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>"

    @Test
    fun `an HTML file named like a PDF is served as plain text`() {
        val (type, nosniff) = serve("invoice.pdf", page)

        assertEquals("text/plain;charset=UTF-8", type)
        assertEquals("nosniff", nosniff)
    }

    @Test
    fun `a range of an HTML or SVG file is served as plain text`() {
        assertEquals("text/plain;charset=UTF-8", serve("page.html", page, range = "bytes=0-9").first)
        assertEquals("text/plain;charset=UTF-8", serve("logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", range = "bytes=0-9").first)
    }

    @Test
    fun `a download keeps the file's type`() {
        assertEquals("text/html", serve("page.html", page, download = "true").first)
    }

    @Test
    fun `other types are served as they are`() {
        assertEquals("text/plain", inlineSafeMimeType("text/plain"))
        assertEquals("application/pdf", inlineSafeMimeType("application/pdf"))
        assertEquals("image/png", inlineSafeMimeType("image/png"))
        assertEquals("text/plain;charset=UTF-8", inlineSafeMimeType("application/rss+xml"))
        assertEquals("text/plain;charset=UTF-8", inlineSafeMimeType("TEXT/HTML; charset=ISO-8859-1"))
    }
}
