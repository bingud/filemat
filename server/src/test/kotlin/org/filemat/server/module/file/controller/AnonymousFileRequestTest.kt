package org.filemat.server.module.file.controller

import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.util.RateLimiter
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Without a login and without a share link, the file endpoints answer 401 before they look at the path,
 * so the answer never tells whether a path exists.
 */
class AnonymousFileRequestTest {

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private val fileController = FileController(
        fileService = fileService,
        tusService = mockk(),
        filesystemService = filesystemService,
        fileLockService = FileLockService(),
        rateLimiter = RateLimiter(),
    )
    private val utilController = FileUtilController(
        fileService = fileService,
        thumbnailService = mockk(),
        logService = mockk(relaxed = true),
        rateLimiter = mockk(relaxed = true),
    )

    private val path = "/missing/or/not"

    private fun anonymous() = MockHttpServletRequest()

    @Test
    fun `every file endpoint answers 401 without touching the path`() {
        val codes = listOf(
            fileController.streamFileContentMapping(anonymous(), path, null, null).statusCode.value(),
            fileController.getFileLastModifiedDateMapping(anonymous(), path, null).statusCode.value(),
            utilController.imageThumbnailMapping(anonymous(), path, null, null).statusCode.value(),
            utilController.videoPreviewMapping(anonymous(), path, null, null).statusCode.value(),
            utilController.contentMetadataMapping(anonymous(), path, null).statusCode.value(),
        )

        assertEquals(listOf(401, 401, 401, 401, 401), codes)
        verify(exactly = 0) { fileService.resolvePathWithOptionalShare(any(), any(), any()) }
        verify(exactly = 0) { fileService.getContentMetadata(any(), any(), any()) }
        verify(exactly = 0) { filesystemService.getSize(any()) }
    }
}
