package org.filemat.server.module.file.controller

import io.mockk.every
import io.mockk.mockk
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest

/**
 * A thumbnail or video preview that can't be generated is an error status without cache headers,
 * never `200 image/jpeg` that a browser could keep (fixes 18).
 */
class FileUtilControllerThumbnailTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val thumbnailService = mockk<ThumbnailService>()
    private val controller = FileUtilController(
        fileService = fileService,
        thumbnailService = thumbnailService,
        logService = mockk(relaxed = true),
        rateLimiter = mockk(relaxed = true),
    )

    // Larger than ThumbnailService.MAX_CACHED_THUMBNAIL_SIZE, so a success carries a long cache header
    private val size = (ThumbnailService.MAX_CACHED_THUMBNAIL_SIZE + 1).toString()

    private fun setUp(): String {
        val file = Files.writeString(tempDir.resolve("photo.png"), "x")
        val canonical = FilePath.ofAlreadyNormalized(file)
        every { fileService.resolvePathWithOptionalShare(any(), any()) } returns Result.ok(canonical)
        every { fileService.getMetadata(any(), any(), any(), any()) } returns Result.ok(
            FileMetadata(
                path = file.toString(), modifiedDate = 1, createdDate = 1, fileType = FileType.FILE,
                size = 1, isExecutable = false, isWritable = true,
            )
        )
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        return file.toString()
    }

    @Test
    fun `an image thumbnail that fails is a 500 without cache headers`() {
        val path = setUp()
        every { thumbnailService.streamImageThumbnail(any(), any(), any(), any(), any(), any()) } throws Exception("decode failed")

        val response = controller.imageThumbnailMapping(MockHttpServletRequest(), path, size, null)

        assertEquals(500, response.statusCode.value())
        assertNull(response.headers.cacheControl)
        assertFalse(response.headers.containsKey(HttpHeaders.CACHE_CONTROL))
        assertFalse(response.headers.contentType == MediaType.IMAGE_JPEG)
    }

    @Test
    fun `a video preview that fails is a 500 without cache headers`() {
        val path = setUp()
        every { thumbnailService.streamVideoPreview(any(), any(), any(), any(), any()) } throws Exception("no frame")

        val response = controller.videoPreviewMapping(MockHttpServletRequest(), path, size, null)

        assertEquals(500, response.statusCode.value())
        assertNull(response.headers.cacheControl)
        assertFalse(response.headers.contentType == MediaType.IMAGE_JPEG)
    }

    @Test
    fun `a thumbnail that works is a JPEG with the cache header for large sizes`() {
        val path = setUp()
        every { thumbnailService.streamImageThumbnail(any(), any(), any(), any(), any(), any()) } answers {
            (args[5] as OutputStream).write(byteArrayOf(1, 2, 3))
        }

        val response = controller.imageThumbnailMapping(MockHttpServletRequest(), path, size, null)

        assertEquals(200, response.statusCode.value())
        assertEquals(MediaType.IMAGE_JPEG, response.headers.contentType)
        assertEquals("private, max-age=86400", response.headers.cacheControl)
    }
}
