package org.filemat.server.module.file.service.file

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.filemat.server.common.State
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.media.MediaFrame
import org.filemat.server.module.file.service.file.media.MediaPreviewBackend
import org.filemat.server.module.file.service.file.media.MediaPreviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The thumbnail reads the size from the header and lets the decoder reduce while reading (fixes 19).
 */
class ThumbnailServiceImageTest {

    @TempDir
    lateinit var tempDir: Path

    private val backend = mockk<MediaPreviewBackend>()

    private fun service(): ThumbnailService {
        val state = State(env = emptyMap(), dataFolderPath = Files.createDirectory(tempDir.resolve("data")).toRealPath())
            .apply { thumbCache.isEnabled = false }
        return ThumbnailService(
            logService = mockk(relaxed = true),
            fileContentMetadataService = mockk(relaxed = true),
            state = state,
            mediaPreviewService = mockk<MediaPreviewService> { every { backend } returns this@ThumbnailServiceImageTest.backend },
        )
    }

    private fun writeImage(name: String, width: Int, height: Int, format: String = "png"): Path {
        val file = tempDir.resolve(name)
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, file.toFile())
        return file
    }

    private fun thumbnail(file: Path, targetSize: Int): BufferedImage {
        val output = ByteArrayOutputStream()
        service().streamImageThumbnail(
            FilePath.of(file.toString()), targetSize, Files.getLastModifiedTime(file).toMillis(), Files.size(file),
            inputStreamProvider = { Files.newInputStream(file) },
            outputStream = output,
        )
        return ImageIO.read(ByteArrayInputStream(output.toByteArray()))
    }

    @Test
    fun `a large image is reduced to the target and keeps its aspect ratio`() {
        val result = thumbnail(writeImage("wide.png", 1200, 600), 100)

        assertEquals(100, result.width)
        assertEquals(50, result.height)
    }

    @Test
    fun `a large JPEG is reduced to the target and keeps its aspect ratio`() {
        val result = thumbnail(writeImage("tall.jpg", 600, 1500, "jpg"), 200)

        assertEquals(80, result.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `a small image is not upscaled`() {
        val result = thumbnail(writeImage("small.png", 40, 20), 100)

        assertEquals(40, result.width)
        assertEquals(20, result.height)
    }

    @Test
    fun `a format ImageIO cannot read goes to the FFmpeg fallback`() {
        val file = Files.write(tempDir.resolve("photo.heic"), byteArrayOf(1, 2, 3))
        every { backend.firstFrame(any()) } returns MediaFrame(BufferedImage(300, 150, BufferedImage.TYPE_INT_RGB), null)

        val result = thumbnail(file, 100)

        verify(exactly = 1) { backend.firstFrame(any()) }
        assertEquals(100, result.width)
        assertEquals(50, result.height)
    }
}
