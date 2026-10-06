package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FileContentMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

class FileContentMetadataServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val service = FileContentMetadataService(
        fileService = fileService,
        fileLockService = FileLockService(),
    )

    @Test
    fun `reads png dimensions from the image header`() {
        val file = tempDir.resolve("photo.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)

        val meta = service.extractFromFile(file, "photo.png")

        assertEquals(32, meta.width)
        assertEquals(16, meta.height)
        assertNull(meta.durationMs)
    }

    @Test
    fun `getContentMetadata returns png dimensions after access check`() {
        val file = tempDir.resolve("shot.png").toFile()
        ImageIO.write(BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        val result = service.getContentMetadata(user, path, null)

        assertTrue(result.isSuccessful)
        assertEquals(48, result.value.width)
        assertEquals(24, result.value.height)
        assertNull(result.value.durationMs)
    }

    @Test
    fun `cache hit returns probed values without reading the png header`() {
        val file = tempDir.resolve("cached.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        service.putFromProbe(
            canonicalPath = path,
            modifiedDate = file.lastModified(),
            fileSize = file.length(),
            meta = FileContentMetadata(width = 99, height = 88),
        )

        val result = service.getContentMetadata(user, path, null)

        assertTrue(result.isSuccessful)
        assertEquals(99, result.value.width)
        assertEquals(88, result.value.height)
    }

    @Test
    fun `different mtime key is a cache miss and extracts the png`() {
        val file = tempDir.resolve("stale.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        service.putFromProbe(
            canonicalPath = path,
            modifiedDate = 1L,
            fileSize = file.length(),
            meta = FileContentMetadata(width = 99, height = 88),
        )

        val result = service.getContentMetadata(user, path, null)

        assertTrue(result.isSuccessful)
        assertEquals(32, result.value.width)
        assertEquals(16, result.value.height)
    }

    @Test
    fun `batch omits files that fail access`() {
        val file = tempDir.resolve("ok.png").toFile()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file)
        val okPath = FilePath.of(file.absolutePath)
        val denied = FilePath.of(tempDir.resolve("denied.png").toAbsolutePath().toString())
        val user = mockk<Principal>(relaxed = true)

        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.isAllowedToAccessFile(any(), okPath, any()) } returns Result.ok()
        every { fileService.isAllowedToAccessFile(any(), denied, any()) } returns Result.reject("denied")

        val result = service.getContentMetadataBatch(user, listOf(okPath, denied), null)

        assertEquals(1, result.size)
        assertEquals(8, result[okPath.pathString]?.width)
    }

    private fun mockAccess(path: FilePath) {
        every { fileService.resolvePathWithOptionalShare(any(), any()) } returns Result.ok(path)
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } returns Result.ok(path)
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
    }
}
