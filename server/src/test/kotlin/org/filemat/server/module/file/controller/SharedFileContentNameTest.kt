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
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * A single-file share is requested as `/`, so `/content` names the response after the shared file
 */
class SharedFileContentNameTest {

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

    private fun sharedFile(): Path {
        val file = Files.write(tempDir.resolve("holiday clip.mp4"), ByteArray(64) { it.toByte() })
        val canonical = FilePath.ofAlreadyNormalized(file)
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } returns Result.ok(canonical)
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.getSize(any()) } returns Result.ok(64L)
        every { fileService.getFileContent(any(), any(), any(), any(), any(), any()) } answers { Result.ok(Files.newInputStream(file)) }
        return file
    }

    @Test
    fun `a single-file share downloads with the file's name`() {
        sharedFile()

        val response = controller.streamFileContentMapping(MockHttpServletRequest(), "/", "share-token", "true")

        assertEquals(200, response.statusCode.value())
        assertEquals("attachment; filename*=UTF-8''holiday%20clip.mp4", response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
    }

    @Test
    fun `a range of a single-file share has the file's type`() {
        sharedFile()
        val request = MockHttpServletRequest().apply { addHeader("Range", "bytes=0-15") }

        val response = controller.streamFileContentMapping(request, "/", "share-token", null)

        assertEquals(206, response.statusCode.value())
        assertEquals("video/mp4", response.headers.contentType?.toString())
    }
}
